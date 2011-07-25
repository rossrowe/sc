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
import actors.Actor
import actors.Actor._

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

  val MODULUS = math.pow(2, 32).toLong
  val MIN_CHAN = 1  // 0 is reserved for per-TCP-connection communication
  val MAX_CHAN = MODULUS - 1

  val VERSION = (0, 1, 0)
}

class KgpConn(id: Long, client: KgpClient) {
  private val log = LogFactory.getLog(this.getClass)
  var isRemoteShutdown = false
  var isLocalShutdown = false
  val kgpChannel = client.kgpChannel

  if (id < Kgp.MIN_CHAN || id > Kgp.MAX_CHAN) {
    throw new Exception("invalid connection ID: " + id)
  }

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
      client.closeSub(id)
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

  def closeConnPacket(id: Long): Packet = {
    val packet = (id, outSeq, inSeq, 1, buffer(0))
    tickOutbound()
    outBuffer += packet
    return packet
  }

  def packetize(id: Long, data: ChannelBuffer): ListBuffer[Packet] = {
    val packets = ListBuffer[Packet]()
    //log.info("sending on " + id + " #" + outSeq + " acking " + inSeq + " len " + msg.readableBytes)
    while (data.readableBytes > 0) {
      val msg = data.readBytes(math.min(data.readableBytes,
                                        Kgp.MAX_PACKET_SIZE))
      val packet = (id, outSeq, inSeq, 0, msg)
      packets += packet
      tickOutbound()
    }
    outBuffer ++= packets
    return packets
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
      outBuffer += msg
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

class KgpClient(host: String, port: Int, forwardPort: Int) extends Actor {
  case object Connect
  case class HandleConnected(channel: Channel)
  case class HandleClosed(channel: Channel)
  case class Send(conn_id: Long,  msg: ChannelBuffer)
  case class CloseSub(conn_id: Long)
  case object Close

  private val log = LogFactory.getLog(this.getClass)

  val cf = new NioClientSocketChannelFactory(
    Executors.newSingleThreadScheduledExecutor,
    Executors.newSingleThreadScheduledExecutor,
    1)
  // Configure the server.
  val bootstrap = new ClientBootstrap(cf)
  val timer = new HashedWheelTimer()
  var currentChannel: Channel = null
  val kgpChannel = new KgpChannel()
  val _r = new Random()

  implicit def ftotimertask(f: () => Unit) = new TimerTask {
    def run(timeout: Timeout) = f()
  }

  def after(ms:Int)(f: => Unit) {
    timer.newTimeout(f _, ms, TimeUnit.MILLISECONDS)
  }

  // the actor API is not testable with EasyMock, and kind of poorly
  // thought out in general
  def connect() { this ! Connect }
  def handleConnected(channel: Channel) { this ! HandleConnected(channel) }
  def handleClosed(channel: Channel) { this ! HandleClosed(channel) }
  def send(conn_id: Long, msg: ChannelBuffer) { this ! Send(conn_id, msg) }
  def closeSub(conn_id: Long) { this ! CloseSub(conn_id) }
  def close() { this ! Close }

  def act() {
    loop {
      react {
        case Connect => {
          log.info("connecting to Sauce Connect server")
          def mkconn(id: Long, channel: Channel): KgpConn = {
            return new ProxyConn(id, this, cf, forwardPort)
          }

          val handler = new KgpClientHandler(this, mkconn)
          // Set up the pipeline factory.
          bootstrap.setPipelineFactory(new ChannelPipelineFactory {
            override def getPipeline: ChannelPipeline = {
              Channels.pipeline(new KgpPacketDecoder(),
                                handler,
                                new KgpPacketEncoder())
            }
          })

          // Start the connection attempt.
          bootstrap.connect(new InetSocketAddress(host, port))
        }

        case HandleConnected(channel) => {
          if (currentChannel != null) {
            log.warn("got a new connection while we still had an old one!")
            if (currentChannel != channel) {
              log.warn("got new connection that is different from old one!  closing the old one and switching...")
              currentChannel.close()
            }
          }
          currentChannel = channel
          log.info("resending " + kgpChannel.outBuffer.length + " packets")
          for (packet <- kgpChannel.outBuffer) {
            currentChannel.write(packet)
          }
        }

        case HandleClosed(channel) => {
          if (this.currentChannel == channel ||
              this.currentChannel == null) {
            this.currentChannel = null
            after(1000) { this ! Connect }
          }
        }

        case Send(conn_id: Long,  msg: ChannelBuffer) => {
          val packets = kgpChannel.packetize(conn_id, msg)
          if (currentChannel != null) {
            for (packet <- packets) {
              currentChannel.write(packet)
            }
            //if (_r.nextFloat < 0.05) {
            //  currentChannel.close()
            //}
          }
        }

        case CloseSub(conn_id: Long) => {
          val packet = kgpChannel.closeConnPacket(conn_id)
          if (currentChannel != null) {
            currentChannel.write(packet)
            //if (_r.nextFloat < 0.05) {
            //  currentChannel.close()
            //}
          }
        }

        case Close => {
          log.info("asked to close connection to Sauce Connect server")
          if (currentChannel != null) {
            currentChannel.close()
          }
        }
      }
    }
  }
}


class KgpClientHandler(val client: KgpClient, mkconn: (Long, Channel) => KgpConn) extends SimpleChannelUpstreamHandler {
  type Packet = (Long, Long, Long, Int, ChannelBuffer)
  private val log = LogFactory.getLog(this.getClass)

  var lastIncoming = 0L
  var lastKeepaliveTime = 0L
  var keepaliveOutSeq = 0L
  var keepaliveOutAcked = 0L
  var keepaliveInSeq = 0L
  var keepaliveInAcked = 0L
  var minAckTime = 5000L // a reasonable maximum to start with
  var calculatedTimeout = minAckTime
  var keepaliveTimer: Timer = null
  val kgpChannel = client.kgpChannel
  val _r = new Random()

  implicit def ftotimertask(f: () => Unit) = new TimerTask {
    def run(timeout: Timeout) = f()
  }

  def keepalivePacket(): Packet = {
    keepaliveOutSeq += 1
    keepaliveOutSeq %= Kgp.MODULUS
    val packet = (0L, keepaliveOutSeq, kgpChannel.inSeq, 0, wrappedBuffer(Array[Byte]('k')))
    return packet
  }

  def ackPacket(): Packet = {
    val packet = (0L, keepaliveInSeq, kgpChannel.inSeq, 0, wrappedBuffer(Array[Byte]('a')))
    if (kgpChannel.inSeq %> kgpChannel.inAcked) {
      kgpChannel.inAcked = kgpChannel.inSeq
    }
    keepaliveInAcked = keepaliveInSeq
    return packet
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
    lastIncoming = System.currentTimeMillis
    def keepaliveHandler() {
      if (keepaliveOutSeq %> keepaliveOutAcked) {
        val now = System.currentTimeMillis
        if (now - lastIncoming > calculatedTimeout) {
          log.info("Sauce Connect connection stalled! " + keepaliveOutAcked + '/' + keepaliveOutSeq + " " + (now - lastIncoming) + " " + calculatedTimeout + " " + minAckTime + " " + ctime())
          channel.write("close").addListener(ChannelFutureListener.CLOSE)
          return
        } else {
          //log.info("LIVE!" + " " + keepaliveOutAcked + " " + '/' + " " + keepaliveOutSeq + " " + (now - lastIncoming) + " " + calculatedTimeout + " " + (calculatedTimeout - (now - lastIncoming)) + " " + minAckTime + " " + ctime())
        }
      }
      val packet = keepalivePacket()
      channel.write(packet)
      lastKeepaliveTime = System.currentTimeMillis

      keepaliveTimer.newTimeout(keepaliveHandler _, 1000, TimeUnit.MILLISECONDS)
    }

    keepaliveTimer.newTimeout(keepaliveHandler _, 1000, TimeUnit.MILLISECONDS)

    client.handleConnected(channel)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) = {
    log.info("disconnected from Sauce Connect server")
  }

  implicit def longToKgpModLong(x: Long): KgpModLong = new KgpModLong(x)

  def ctime(): String = {
    return new Date().toString
  }

  def sendAck(channel: Channel) {
    val packet = ackPacket()
    channel.write(packet)
  }

  def handleConnPacket(channel: Channel,
                       seq: Long,
                       ack: Long,
                       msg: ChannelBuffer) = {
    if (msg == wrappedBuffer(Array[Byte]('k'))) {
      keepaliveInSeq = seq
      sendAck(channel)
    } else if (msg == wrappedBuffer(Array[Byte]('a'))) {
      if (seq %>= keepaliveOutSeq) {
        if (seq %> keepaliveOutSeq) {
          log.warn("got a keepalive seq greater than what we last sent! " + seq + " / " + keepaliveOutSeq)
        }
        if (seq %> keepaliveOutAcked) {
          keepaliveOutAcked = seq
        }
        //log.info("got keepalive ack" + " " + seq + " " + ctime())
        val ackTime = System.currentTimeMillis - lastKeepaliveTime
        minAckTime = math.min(minAckTime, ackTime)
        calculatedTimeout = (System.currentTimeMillis - lastKeepaliveTime) + minAckTime + 2000
        //log.info("new calculated timeout: " + calculatedTimeout)
      } else {
        log.info("received an out-of-date ack " + seq + " " + keepaliveOutSeq)
      }
    } else {
      log.info("unknown control message:" + " " + msg.toString(UTF_8))
    }
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    // Send back the received message to the remote peer.
    val c = e.getChannel
    if (!c.isConnected) {
      //log.info("messageReceived for closed channel " + c)
      return
    }
    //if (_r.nextFloat < 0.005) {
    //  c.close()
    //}
    e.getMessage match {
      case (ver: (Int, Int, Int), id: Array[Byte]) => {
        log.info("got announcement:" + ver + " " + ChannelBuffers.hexDump(wrappedBuffer(id)))
      }
      case (conn_id: Long, seq: Long, ack: Long, ctrl: Int, msg: ChannelBuffer) => {
        lastIncoming = System.currentTimeMillis
        //log.info("got packet " + seq + " on " + conn_id + " " + ctime())

        if (ack %> kgpChannel.outAcked) {
          //log.info("got an ack on my output up to " + ack + " - was " + kgpChannel.outAcked)
          kgpChannel.outAcked = ack
        }
        if (conn_id == 0) {
          handleConnPacket(c, seq, ack, msg)
        } else if (seq %> kgpChannel.inSeq) {
          val nextSeq = (kgpChannel.inSeq + 1) % Kgp.MODULUS
          if (seq %> nextSeq) {
            log.info("packet skip to " + seq + " expected " + kgpChannel.inSeq)
          }

          kgpChannel.inSeq = seq

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
          log.info("got old packet " + seq + " expected >" + kgpChannel.inSeq)
        }

        kgpChannel.pruneOutBuffer()
      }
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) = {
    log.info("connection to Sauce Connect server closed")
    client.handleClosed(e.getChannel)
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
