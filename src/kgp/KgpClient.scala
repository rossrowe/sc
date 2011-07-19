package com.saucelabs.kgp

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory

import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.buffer.{ ChannelBuffer, ChannelBuffers }
import org.jboss.netty.buffer.ChannelBuffers._
import org.jboss.netty.channel.{
  Channel,
  Channels,
  ChannelEvent,
  ChannelFuture,
  ChannelFutureListener,
  ChannelHandlerContext,
  ChannelPipeline,
  ChannelPipelineFactory,
  ChannelState,
  ChannelStateEvent,
  ExceptionEvent,
  MessageEvent,
  SimpleChannelUpstreamHandler,
  WriteCompletionEvent
}
import org.jboss.netty.handler.codec.frame.{
  CorruptedFrameException,
  FrameDecoder
}
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder
import org.jboss.netty.util.{Timeout, TimerTask, Timer}
import org.jboss.netty.util.CharsetUtil._
import org.jboss.netty.channel.socket.ClientSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.util.HashedWheelTimer
import java.nio.ByteOrder
import java.net.{InetSocketAddress, ConnectException}
import java.io.IOException
import java.util.concurrent.{TimeUnit, Executors}
import java.util.Date
import util.control.Breaks._
import util.Random
import collection.mutable.{Map, ListBuffer}


class KgpModLong(x: Long) {
  def %>(curSeq: Long): Boolean = {
    val diff = (x - curSeq) % Kgp.MODULUS
    if (0 < diff && diff < Kgp.MODULUS/2) {
      return true
    }
    return false
  }

  def %>=(curSeq: Long): Boolean = {
    val diff = (x - curSeq) % Kgp.MODULUS
    if (0 <= diff && diff < Kgp.MODULUS/2) {
      return true
    }
    return false
  }
}

object Kgp {
  val INTRO_LEN = 35
  val HEADER_LEN = 4 * 4
  val MAX_PACKET_SIZE = 30 * 1024

  val MODULUS = math.pow(2, 32).toInt
  val MIN_CHAN = 1  // 0 is reserved for per-TCP-connection communication
  val MAX_CHAN = MODULUS - 1

  val VERSION = (0, 1, 0)
}

class KgpConn(id: Long, client: KgpClient) {
  private val log = LogFactory.getLog(this.getClass)
  var isRemoteShutdown = false
  var isLocalShutdown = false
  val kgpChannel = client.kgpChannel

  def dataReceived(msg: ChannelBuffer) {
    log.info("dataReceived not handled")
  }

  def close() {
    log.info("close not handled")
  }

  def remoteShutdown() {
    log.info("got remote shutdown for conn " + id)
    isRemoteShutdown = true
    localShutdown()
  }

  def localShutdown() {
    if (!isLocalShutdown) {
      log.info("doing local shutdown for conn " + id)
      client.close_sub(id)
      isLocalShutdown = true
    }
    if (isRemoteShutdown) {
      log.info("finished closing " + id + ", 1 of " + kgpChannel.conns.size + " kgp-tunneled connections")
      kgpChannel.conns -= this.id
    }
  }
}

class KgpChannel {
  type Packet = (Long, Long, Long, Int, ChannelBuffer)
  implicit def longToKgpModLong(x: Long): KgpModLong = new KgpModLong(x)

  private val log = LogFactory.getLog(this.getClass)

  val id = new Array[Byte](16)
  val _r = new Random()
  _r.nextBytes(id)
  var outSeq: Long = 1
  var outAcked: Long = 0
  var inSeq: Long = 0
  var inAcked: Long = 0
  val conns = Map[Long, KgpConn]()
  var outBuffer = ListBuffer[Packet]()

  def tickOutbound() {
    outSeq += 1
    outSeq %= Kgp.MODULUS
    if (inSeq %> inAcked) {
      inAcked = inSeq
    }
  }

  def keepalivePacket(): Packet = {
    val packet = (0L, outSeq, inSeq, 0, wrappedBuffer(Array[Byte]('k')))
    tickOutbound()
    return packet
  }

  def ackPacket(): Packet = {
    val packet = (0L, outSeq, inSeq, 0, wrappedBuffer(Array[Byte]('a')))
    tickOutbound()
    return packet
  }

  def closeConnPacket(id: Long): Packet = {
    val packet = (id, outSeq, inSeq, 1, buffer(0))
    tickOutbound()
    outBuffer.append(packet)
    return packet
  }

  def nextPacket(id: Long, msg: ChannelBuffer): Packet = {
    //log.info("sending on " + id + " #" + outSeq + " acking " + inSeq + " len " + msg.readableBytes)
    val packet = (id, outSeq, inSeq, 0, msg)
    tickOutbound()
    outBuffer.append(packet)
    return packet
  }

  def pruneOutBuffer() = {
    outBuffer = outBuffer.filter { case (_, seq: Long, _, _, _) => seq %> outAcked }
  }
}

class KgpPacketDecoder extends FrameDecoder {
  private val log = LogFactory.getLog(this.getClass)

  var initialized = false

  override def decode(ctx: ChannelHandlerContext, channel: Channel, buffer: ChannelBuffer): Object = {
    if (!initialized) {
      if (buffer.readableBytes < Kgp.INTRO_LEN) {
        return null
      }

      log.info("remote announce received")
      val kgp = buffer.readSlice(3).toString(UTF_8)
      if (kgp != "kgp") {
        buffer.resetReaderIndex
        throw new CorruptedFrameException(
          "Invalid protocol ID: " + kgp)
      }

      val version = (buffer.readUnsignedInt(),
                     buffer.readUnsignedInt(),
                     buffer.readUnsignedInt())
      log.info(version)
      buffer.readUnsignedInt()
      val endpoint_id = new Array[Byte](16)
      buffer.readBytes(endpoint_id)
      log.info(ChannelBuffers.hexDump(wrappedBuffer(endpoint_id)))

      initialized = true
      return (version, endpoint_id)
    }

    if (buffer.readableBytes < Kgp.HEADER_LEN) {
      return null
    }

    buffer.markReaderIndex()
    val (conn, seq, ack, ctrl, length) = (buffer.readUnsignedInt(),
                                          buffer.readUnsignedInt(),
                                          buffer.readUnsignedInt(),
                                          buffer.readUnsignedShort(),
                                          buffer.readUnsignedShort())
    if (buffer.readableBytes < length) {
      buffer.resetReaderIndex()
      return null
    }

    //log.info("packet on " + conn + " #" + seq + " acking " + ack + " code " + ctrl + " len " + length)

    val msg = buffer.readBytes(length.toInt)

    //log.info("body " + msg.toString(UTF_8))

    return (conn, seq, ack, ctrl, msg)
  }
}

class KgpPacketEncoder extends OneToOneEncoder {
  private val log = LogFactory.getLog(this.getClass)

  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Object): Object = {
    msg match {
      case ("announce", kgpChannel: KgpChannel) => {
        log.info("announcing as " + ChannelBuffers.hexDump(wrappedBuffer(kgpChannel.id)))
        val b = buffer(Kgp.INTRO_LEN)
        b.writeBytes(copiedBuffer("kgp", UTF_8))
        b.writeInt(Kgp.VERSION._1)
        b.writeInt(Kgp.VERSION._2)
        b.writeInt(Kgp.VERSION._3)
        b.writeInt(0)
        b.writeBytes(kgpChannel.id)
        return b
      }
      case (conn: Long, seq: Long, ack: Long, ctrl: Int, msg: ChannelBuffer) => {
        val b = buffer(Kgp.HEADER_LEN + msg.readableBytes)
        b.writeInt(conn.toInt)
        b.writeInt(seq.toInt)
        b.writeInt(ack.toInt)
        b.writeShort(ctrl.toShort)
        b.writeShort(msg.readableBytes.toShort)
        return wrappedBuffer(b, msg)
      }
      case "close" => {
        // FIXME: close this thing
        return wrappedBuffer(Array[Byte]())
      }
    }
  }
}


class ProxyConn(id: Long,
                client: KgpClient,
                cf: ClientSocketChannelFactory,
                remotePort: Int) extends KgpConn (id, client) {
  implicit def ftofuturelistener(f: () => Unit) = new ChannelFutureListener {
    override def operationComplete(future: ChannelFuture) = f()
  }

  private val log = LogFactory.getLog(this.getClass)

  @volatile
  private var tcpChannel: Channel = null
  var tcpConnected = false
  val outBuffer: ListBuffer[ChannelBuffer] = ListBuffer()

  val remoteHost = "localhost"

  log.info("connecting " + id + " to proxied tcp server " + remoteHost + ":" + remotePort)

  // Start the connection attempt.
  val cb = new ClientBootstrap(cf)
  cb.getPipeline.addLast("handler", new TcpHandler())
  val f = cb.connect(new InetSocketAddress(remoteHost, remotePort))

  tcpChannel = f.getChannel
  f.addListener(() => {
    if (f.isSuccess) {
      tcpConnected = true
      for (msg <- outBuffer) {
        tcpChannel.write(msg)
      }
    } else {
      log.info("connection " + id + " to proxied tcp server failed")
      localShutdown()
    }
  })

  override def dataReceived(msg: ChannelBuffer) {
    if (tcpConnected) {
      tcpChannel.write(msg)
    } else {
      outBuffer.append(msg)
    }
  }


  override def localShutdown {
    if (tcpConnected) {
      log.info("kgp-tunneled connection closed, closing proxied tcp connection")
      tcpChannel.close() // channelClosed will shut us down when it's done
    } else {
      super.localShutdown()
    }
  }

  private class TcpHandler() extends SimpleChannelUpstreamHandler {

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      val msg = e.getMessage.asInstanceOf[ChannelBuffer]
      //System.out.log.info("<<< " + ChannelBuffers.hexDump(msg))
      if (tcpConnected) {
        client.send(id, msg)
      }
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if (tcpConnected) {
        log.info("proxied tcp connection closed, closing kgp-tunneled connection")
        tcpConnected = false
        localShutdown()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      e.getCause.printStackTrace
    }
  }

}


class KgpClient(host: String, port: Int, forwardPort: Int) {
  private val log = LogFactory.getLog(this.getClass)

  val cf = new NioClientSocketChannelFactory(
    Executors.newSingleThreadScheduledExecutor,
    Executors.newSingleThreadScheduledExecutor,
    1)
  // Configure the server.
  val bootstrap = new ClientBootstrap(cf)
  val timer = new HashedWheelTimer()
  var currentChannel: Channel = null
  val trafficLock = new Object
  val kgpChannel = new KgpChannel()

  def connect() {
    log.info("connecting to Sauce Connect server")
    def mkconn(id: Long, channel: Channel): KgpConn = {
      return new ProxyConn(id, this, cf, forwardPort)
    }

    val client = new KgpClientHandler(this, mkconn)
    // Set up the pipeline factory.
    bootstrap.setPipelineFactory(new ChannelPipelineFactory {
      override def getPipeline: ChannelPipeline = {
        Channels.pipeline(new KgpPacketDecoder(),
                          client,
                          new KgpPacketEncoder())
      }
    })

    // Start the connection attempt.
    val future = bootstrap.connect(new InetSocketAddress(host, port))
    future.addListener(new ChannelFutureListener() {
      def operationComplete(future: ChannelFuture) {
        currentChannel = future.getChannel
      }
    })
  }

  def send(conn_id: Long,  msg: ChannelBuffer) = {
    trafficLock.synchronized {
      val packet = kgpChannel.nextPacket(conn_id, msg)
      if (currentChannel != null) {
          currentChannel.write(packet)
      }
    }
  }

  def close_sub(conn_id: Long) {
    trafficLock.synchronized {
      val packet = kgpChannel.closeConnPacket(conn_id)
      if (currentChannel != null) {
        currentChannel.write(packet)
      }
    }
  }

  def close() {
    log.info("asked to close connection to Sauce Connect server")
    if (currentChannel != null) {
      currentChannel.close()
    }
  }
}


class KgpClientHandler(val client: KgpClient, mkconn: (Long, Channel) => KgpConn) extends SimpleChannelUpstreamHandler {
  private val log = LogFactory.getLog(this.getClass)

  var lastIncoming = 0L
  var lastKeepaliveTime = 0L
  var lastKeepaliveSeq = 0L
  var minAckTime = 5000L // a reasonable maximum to start with
  var calculatedTimeout = minAckTime
  var keepaliveTimer: Timer = null
  val kgpChannel = client.kgpChannel

  implicit def ftotimertask(f: () => Unit) = new TimerTask {
    def run(timeout: Timeout) = f()
  }

  override def channelConnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) = {
    log.info("connected!")
    if (keepaliveTimer != null) {
      log.info("keepalive timer still going when reconnecting, stopping it and making a new one")
      keepaliveTimer.stop()
    }
    keepaliveTimer = new HashedWheelTimer()
    val channel = e.getChannel
    channel.write(("announce", kgpChannel))
    log.info("resending " + kgpChannel.outBuffer.length + " packets")
    for (packet <- kgpChannel.outBuffer) {
      channel.write(packet)
    }
    lastIncoming = System.currentTimeMillis
    def keepaliveHandler() {
      if (lastKeepaliveSeq %> kgpChannel.outAcked) {
        val now = System.currentTimeMillis
        if (now - lastIncoming > calculatedTimeout) {
          log.info("Sauce Connect connection stalled! " + kgpChannel.outAcked + '/' + kgpChannel.outSeq + " " + (now - lastIncoming) + " " + calculatedTimeout + " " + minAckTime + " " + ctime())
          channel.write("close").addListener(ChannelFutureListener.CLOSE)
          return
        } else {
          //log.info("LIVE!" + " " + kgpChannel.outAcked + " " + '/' + " " + kgpChannel.outSeq + " " + (now - lastIncoming) + " " + calculatedTimeout + " " + (calculatedTimeout - (now - lastIncoming)) + " " + minAckTime + " " + ctime())
          }
      }
      lastKeepaliveSeq = kgpChannel.outSeq
      val packet = kgpChannel.keepalivePacket()
      channel.write(packet)
      lastKeepaliveTime = System.currentTimeMillis

      keepaliveTimer.newTimeout(keepaliveHandler _, 1000, TimeUnit.MILLISECONDS)
    }

    keepaliveTimer.newTimeout(keepaliveHandler _, 1000, TimeUnit.MILLISECONDS)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) = {
    log.info("disconnected from Sauce Connect server")
    //client.timer.newTimeout(client.connect _, 1000, TimeUnit.MILLISECONDS)
  }

  implicit def longToKgpModLong(x: Long): KgpModLong = new KgpModLong(x)

  def ctime(): String = {
    return new Date().toString
  }

  def sendAck(channel: Channel) {
    val packet = kgpChannel.ackPacket()
    channel.write(packet)
  }

  def handleConnPacket(channel: Channel,
                       seq: Long,
                       ack: Long,
                       msg: ChannelBuffer) = {
    if (msg == wrappedBuffer(Array[Byte]('k'))) {
      sendAck(channel)
    } else if (msg == wrappedBuffer(Array[Byte]('a'))) {
      if (ack %>= lastKeepaliveSeq) {
        //log.info("got ack" + " " + ack + " " + ctime())
        val ackTime = System.currentTimeMillis - lastKeepaliveTime
        minAckTime = math.min(minAckTime, ackTime)
        calculatedTimeout = (System.currentTimeMillis - lastKeepaliveTime) + minAckTime + 2000
        //log.info("new calculated timeout: " + calculatedTimeout)
      } else {
        log.info("received an out-of-date ack " + ack + " " + lastKeepaliveSeq)
      }
    } else {
      log.info("unknown control message:" + " " + msg.toString(UTF_8))
    }
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    // Send back the received message to the remote peer.
    val c = e.getChannel
    e.getMessage match {
      case (conn_id: Long, seq: Long, ack: Long, ctrl: Int, msg: ChannelBuffer) => {
        lastIncoming = System.currentTimeMillis
        //log.info("got packet " + seq + " on " + conn_id + " " + ctime())

        if (ack %> kgpChannel.outAcked) {
          //log.info("got an ack on my output up to " + ack + " - was " + kgpChannel.outAcked)
          kgpChannel.outAcked = ack
        }
        if (seq %> kgpChannel.inSeq) {
          kgpChannel.inSeq = seq

          if (conn_id == 0) {
            handleConnPacket(c, seq, ack, msg)
            return
          }

          if (kgpChannel.inSeq > kgpChannel.inAcked + 10) {
            sendAck(c)
          }
          if (!kgpChannel.conns.contains(conn_id)) {
            kgpChannel.conns(conn_id) = mkconn(conn_id, c)
          }
          val conn = kgpChannel.conns(conn_id)
          ctrl match {
            case 0 => {
              if (msg.readableBytes > 0) {
                //log.info("GOT " + seq + " " + ack + " " + txt)
                conn.dataReceived(msg)
              }
            }
            case 1 => {
              conn.remoteShutdown()
            }
          }
        }  else {
          log.info("got old packet " + seq + " expected " + kgpChannel.inSeq)
        }

        kgpChannel.pruneOutBuffer()
      }
      case (ver: (Int, Int, Int), id: Array[Byte]) => {
        log.info("got announcement:" + ver + " " + ChannelBuffers.hexDump(wrappedBuffer(id)))
      }
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) = {
    val reconnectIn: (Int) => Unit = client.timer.newTimeout(client.connect _, _, TimeUnit.MILLISECONDS)
    log.info("connection to Sauce Connect server closed")
    client.currentChannel = null
    reconnectIn(1000)
    if (keepaliveTimer != null) {
      keepaliveTimer.stop()
    }
  }

  override def exceptionCaught(context: ChannelHandlerContext, ee: ExceptionEvent) {
    ee.getChannel match {
      case c: Channel => c.close()
    }
    ee.getCause match {
      case e: ConnectException => {
        log.warn("Connection failed")
      }
      case e: IOException => {
        log.warn("IOException: " + e)
      }
      case e => {
        e.printStackTrace
        log.warn("Unexpected exception from downstream: " + e)
      }
    }
  }
}
