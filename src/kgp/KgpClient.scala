package com.saucelabs.kgp

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory

import org.jboss.netty.bootstrap.{ClientBootstrap, ServerBootstrap}
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
import org.jboss.netty.channel.socket.nio.{NioClientSocketChannelFactory,
                                           NioServerSocketChannelFactory}
import org.jboss.netty.util.HashedWheelTimer
import org.jboss.netty.handler.ssl.SslHandler
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import java.security.KeyStore
import java.security.cert.{X509Certificate, CertificateException}
import java.nio.ByteOrder
import java.nio.channels.{ClosedChannelException, UnresolvedAddressException}
import java.net.{InetSocketAddress, ConnectException}
import java.io.{IOException, ByteArrayInputStream}
import java.util.concurrent.{TimeUnit, Executors}
import java.util.Date

import java.util.Collections
import java.util.ArrayList
import java.io.FileInputStream
import java.security.cert.{CertPath,
                           CertPathValidator,
                           Certificate,
                           CertificateFactory,
                           PKIXCertPathValidatorResult,
                           PKIXParameters,
                           TrustAnchor,
                           X509Certificate}

import util.control.Breaks._
import util.Random
import scala.util.parsing.json.JSON
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

  val ROOT_CERT = """-----BEGIN CERTIFICATE-----
MIIDyzCCAzSgAwIBAgIJAJIWskl30Xj/MA0GCSqGSIb3DQEBBAUAMIGgMRcwFQYD
VQQKEw5TYXVjZSBMYWJzIEluYzETMBEGA1UECxMKT3BlcmF0aW9uczEhMB8GCSqG
SIb3DQEJARYSaGVscEBzYXVjZWxhYnMuY29tMRYwFAYDVQQHEw1TYW4gRnJhbmNp
c2NvMQswCQYDVQQIEwJDQTELMAkGA1UEBhMCVVMxGzAZBgNVBAMTEm1ha2kuc2F1
Y2VsYWJzLmNvbTAeFw0xMTA3MjkwMjA0NTNaFw0yMTA3MjYwMjA0NTNaMIGgMRcw
FQYDVQQKEw5TYXVjZSBMYWJzIEluYzETMBEGA1UECxMKT3BlcmF0aW9uczEhMB8G
CSqGSIb3DQEJARYSaGVscEBzYXVjZWxhYnMuY29tMRYwFAYDVQQHEw1TYW4gRnJh
bmNpc2NvMQswCQYDVQQIEwJDQTELMAkGA1UEBhMCVVMxGzAZBgNVBAMTEm1ha2ku
c2F1Y2VsYWJzLmNvbTCBnzANBgkqhkiG9w0BAQEFAAOBjQAwgYkCgYEAvWe6aUbw
kzNBLFZvWg85S6CIhpyFS2Gm9+LphTcbjaNmLBYbim6m/YjEqxFR+ca+Ad5UxMDF
oGEGj4mlf949UeN+IBvRkDPLVlGX6SPGlYSOreEQq9F51nOLWqCK4Xko1GzVob8Z
nu911AgYTRWvBrIJ449MlXkP8IbB19Pj/50CAwEAAaOCAQkwggEFMAwGA1UdEwQF
MAMBAf8wHQYDVR0OBBYEFGQkIoAxvVJoOKjFx3cCXGdVkX7TMIHVBgNVHSMEgc0w
gcqAFGQkIoAxvVJoOKjFx3cCXGdVkX7ToYGmpIGjMIGgMRcwFQYDVQQKEw5TYXVj
ZSBMYWJzIEluYzETMBEGA1UECxMKT3BlcmF0aW9uczEhMB8GCSqGSIb3DQEJARYS
aGVscEBzYXVjZWxhYnMuY29tMRYwFAYDVQQHEw1TYW4gRnJhbmNpc2NvMQswCQYD
VQQIEwJDQTELMAkGA1UEBhMCVVMxGzAZBgNVBAMTEm1ha2kuc2F1Y2VsYWJzLmNv
bYIJAJIWskl30Xj/MA0GCSqGSIb3DQEBBAUAA4GBALN0FPHaecUO9moA5CHb5wK+
X6Lpo0c3Q4Gu2NWtDsuvy70j5KCRGXG89truhcCPxsiYwk9Qvu3dt7u8WgEzcWHY
82/XJDMI9VLIJadknI7qyl7nO+ES3dSFgG0C+rUhZm4CT5yKlaQgF+uU431lTRzM
mzAghC0MmMDOJIiaeL+B
-----END CERTIFICATE-----"""
}

class KgpConn(val id: Long, client: KgpClient) {
  private val log = LogFactory.getLog(this.getClass)
  var isRemoteShutdown = false
  var isLocalShutdown = false
  val kgpChannel = client.kgpChannel

  if (id < Kgp.MIN_CHAN || id > Kgp.MAX_CHAN) {
    throw new Exception("invalid connection ID: " + id)
  }

  def dataReceived(msg: ChannelBuffer) {
    log.warn("dataReceived not handled")
  }

  def close() {
    log.warn("close not handled")
  }

  def remoteShutdown() {
    log.debug("got remote shutdown for conn " + id)
    isRemoteShutdown = true
    localShutdown()
  }

  def localShutdown() {
    if (!isLocalShutdown) {
      log.debug("doing local shutdown for conn " + id)
      client.closeSub(id)
      isLocalShutdown = true
    }
    if (isRemoteShutdown) {
      log.debug("finished closing " + id + ", 1 of " + kgpChannel.conns.size + " kgp-tunneled connections")
      kgpChannel.conns -= this.id
    }
  }
}

class KgpChannel {
  type Packet = (Long, Long, Long, Int, ChannelBuffer)
  implicit def longToKgpModLong(x: Long): KgpModLong = new KgpModLong(x)

  private val log = LogFactory.getLog(this.getClass)

  val localEndpointId = new Array[Byte](16)
  val _r = new Random()
  _r.nextBytes(localEndpointId)
  val localEndpointNum = BigInt(ChannelBuffers.hexDump(wrappedBuffer(localEndpointId)), 16)
  var outSeq: Long = 1
  var outAcked: Long = 0
  var inSeq: Long = 0
  var inAcked: Long = 0
  val conns = Map[Long, KgpConn]()
  var outBuffer = ListBuffer[Packet]()
  var remoteEndpointId = Array[Byte](16)
  var remoteEndpointNum = BigInt(0)

  def genConnId(): Long = {
    var minConnId = 1L
    var maxConnId = (math.pow(2, 31).toLong) - 1L
    if (remoteEndpointNum < localEndpointNum) {
      minConnId += math.pow(2, 31).toLong
      maxConnId += math.pow(2, 31).toLong
    }
    var i = minConnId
    breakable {
      while (i < maxConnId) {
        if (!conns.contains(i)) {
          break
        }
        i += 1
      }
    }
    if (conns.contains(i)) {
      throw new Exception("channel out of connections")
    }
    return i
  }

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

      log.debug("remote announce received")
      val kgp = buffer.readSlice(3).toString(UTF_8)
      if (kgp != "kgp") {
        buffer.resetReaderIndex
        throw new CorruptedFrameException(
          "Invalid protocol ID: " + kgp)
      }

      val version = (buffer.readUnsignedInt(),
                     buffer.readUnsignedInt(),
                     buffer.readUnsignedInt())
      log.debug(version)
      val endpointId = new Array[Byte](16)
      buffer.readBytes(endpointId)
      log.debug(ChannelBuffers.hexDump(wrappedBuffer(endpointId)))

      val metadataLen = buffer.readUnsignedInt()
      if (buffer.readableBytes < metadataLen) {
        buffer.resetReaderIndex()
        return null
      }

      val metadataJson = new Array[Byte](metadataLen.toInt)
      buffer.readBytes(metadataJson)
      //log.debug(metadataJson.toString)
      val metadata = JSON.parseFull(new String(metadataJson))

      initialized = true
      return (version, endpointId, metadata)
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
      case ("announce",
            metadataJson: String,
            kgpChannel: KgpChannel) => {
        log.debug("announcing as " + ChannelBuffers.hexDump(wrappedBuffer(kgpChannel.localEndpointId)))
        val b = buffer(Kgp.INTRO_LEN + metadataJson.length)
        b.writeBytes(copiedBuffer("kgp", UTF_8))
        b.writeInt(Kgp.VERSION._1)
        b.writeInt(Kgp.VERSION._2)
        b.writeInt(Kgp.VERSION._3)
        b.writeBytes(kgpChannel.localEndpointId)
        val md = copiedBuffer(metadataJson, UTF_8)
        b.writeInt(md.readableBytes)
        b.writeBytes(md)
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
        return wrappedBuffer(Array[Byte]())
      }
    }
  }
}


class ProxyClientConn(id: Long,
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
  val outBuffer = ListBuffer[ChannelBuffer]()

  val remoteHost = "localhost"

  log.debug("connecting " + id + " to proxied tcp server " + remoteHost + ":" + remotePort)

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
      outBuffer.clear()
    } else {
      log.warn("connection " + id + " to proxied tcp server failed")
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
      log.debug("kgp-tunneled connection closed, closing proxied tcp connection")
      tcpChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      // channelClosed will shut us down when it's done
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
        log.debug("proxied tcp connection closed, closing kgp-tunneled connection")
        tcpConnected = false
        localShutdown()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      e.getCause.printStackTrace
    }
  }

}


class ProxyServerConn(id: Long,
                      client: KgpClient,
                      tcpChannel: Channel) extends KgpConn (id, client) {
  implicit def ftofuturelistener(f: () => Unit) = new ChannelFutureListener {
    override def operationComplete(future: ChannelFuture) = f()
  }

  private val log = LogFactory.getLog(this.getClass)

  var tcpConnected = false

  override def dataReceived(msg: ChannelBuffer) {
    if (tcpConnected) {
      //log.info("kgp -> tcp: " + msg.toString(UTF_8))
      tcpChannel.write(msg)
    } else {
      log.warn("FAILING kgp->tcp: "+ msg.toString(UTF_8))
    }
  }


  override def remoteShutdown() {
    if (tcpConnected) {
      log.debug("got remote shutdown for conn " + id)
      isRemoteShutdown = true

      log.debug("kgp-tunneled connection closed, closing proxied tcp connection")
      tcpChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      // channelClosed will shut us down when it's done
    } else {
      super.remoteShutdown()
    }
  }
}


class ProxyTcpHandler(client: KgpClient) extends SimpleChannelUpstreamHandler {
  private val log = LogFactory.getLog(this.getClass)

  var kgpConn: ProxyServerConn = null
  val outBuffer = ListBuffer[ChannelBuffer]()

  override def channelConnected(ctx: ChannelHandlerContext,
                                e: ChannelStateEvent) {
    val inboundChannel = e.getChannel
    val connId = client.kgpChannel.genConnId()
    log.debug("connection from tcp client, proxying through conn " + connId)

    kgpConn = new ProxyServerConn(connId, client, inboundChannel)
    kgpConn.tcpConnected = true
    client.kgpChannel.conns(connId) = kgpConn
    for (msg <- outBuffer) {
      client.send(kgpConn.id, msg)
    }
    outBuffer.clear()
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val msg = e.getMessage.asInstanceOf[ChannelBuffer]
    //System.out.log.info("<<< " + ChannelBuffers.hexDump(msg))
    if (kgpConn != null && kgpConn.tcpConnected) {
      for (bufMsg <- outBuffer) {
        // FIXME: does this actually happen?
        client.send(kgpConn.id, bufMsg)
      }
      outBuffer.clear()
      client.send(kgpConn.id, msg)
      //log.info("tcp -> kgp: " + msg.toString(UTF_8))
    } else {
      log.warn("BUFFERING in outbound proxy")
      outBuffer += msg
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    if (kgpConn != null && kgpConn.tcpConnected) {
      for (bufMsg <- outBuffer) {
        log.warn("FLUSHING BUFFER in outbound proxy")
        // FIXME: does this actually happen?
        client.send(kgpConn.id, bufMsg)
      }
    }
    outBuffer.clear()
    if (kgpConn.tcpConnected) {
      log.debug("proxied tcp connection closed, closing kgp-tunneled connection")
      kgpConn.tcpConnected = false
      kgpConn.localShutdown()
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    kgpConn.tcpConnected = false // maybe?
    e.getCause match {
      case c:ClosedChannelException => {}
      case c:Exception => e.getCause.printStackTrace
    }
  }
}


class ProxyServer(client: KgpClient, port: Int) {
  private val log = LogFactory.getLog(this.getClass)
  def serve() {
    try {
    val cf = new NioServerSocketChannelFactory(
      Executors.newSingleThreadScheduledExecutor,
      Executors.newSingleThreadScheduledExecutor,
      1)
    val bootstrap = new ServerBootstrap(cf)
    bootstrap.setPipelineFactory(new ChannelPipelineFactory {
      override def getPipeline: ChannelPipeline = {
        val handler = new ProxyTcpHandler(client)
        Channels.pipeline(handler)
      }
    })
    bootstrap.bind(new InetSocketAddress(port))
    } catch {
      case e: Exception => {
        log.warn("Exception proxying: " + e)
      }
    }
  }
}


class KgpClientTrustManager() extends X509TrustManager {
  private val log = LogFactory.getLog(this.getClass)

  // oh, java
  val certFactory = CertificateFactory.getInstance("X.509")
  val rootCert = certFactory.generateCertificate(new ByteArrayInputStream(Kgp.ROOT_CERT.getBytes))
  val anchor = new TrustAnchor(rootCert.asInstanceOf[X509Certificate], null)
  val params = new PKIXParameters(Collections.singleton(anchor))
  params.setRevocationEnabled(false)
  val certPathValidator = CertPathValidator.getInstance("PKIX")

  def getAcceptedIssuers(): Array[X509Certificate] = {
    return Array[X509Certificate]()
  }

  def checkClientTrusted(chain: Array[X509Certificate], authType: String) {
    throw new CertificateException("client certificates are not accepted")
  }

  def checkServerTrusted(chain: Array[X509Certificate], authType: String) {
    val chainlist = new ArrayList[Certificate]
    for (c <- chain) {
      log.debug(
        "Checking certificate: " + c.getSubjectDN())
      chainlist.add(c)
    }

    val certPath = certFactory.generateCertPath(chainlist)
    certPathValidator.validate(certPath, params)
    log.debug("Certificate validated")
  }
}


class KgpClient(host: String, port: Int, forwardPort: Int, val metadataJson: String) extends Actor {
  case object Connect
  case class HandleConnected(channel: Channel)
  case class HandleClosed(channel: Channel)
  case class Send(connId: Long,  msg: ChannelBuffer)
  case class CloseSub(connId: Long)
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
  val trustManager = new KgpClientTrustManager()
  var everConnected = false
  val _r = new Random()

  implicit def ftotimertask(f: () => Unit) = new TimerTask {
    def run(timeout: Timeout) = f()
  }

  def after(ms:Int)(f: => Unit) {
    timer.newTimeout(f _, ms, TimeUnit.MILLISECONDS)
  }

  def waitForConnection() {
    while (true) {
      if (everConnected) return
      Thread.sleep(1)
    }
  }

  def handleAnnounced() {
    everConnected = true
  }

  // the actor API is not testable with EasyMock, and kind of poorly
  // thought out in general
  def connect() { this ! Connect }
  def handleConnected(channel: Channel) { this ! HandleConnected(channel) }
  def handleClosed(channel: Channel) { this ! HandleClosed(channel) }
  def send(connId: Long, msg: ChannelBuffer) { this ! Send(connId, msg) }
  def closeSub(connId: Long) { this ! CloseSub(connId) }
  def close() { this ! Close }

  def act() {
    loop {
      react {
        case Connect => {
          log.debug("connecting to Sauce Connect server")
          def mkconn(id: Long, channel: Channel): KgpConn = {
            return new ProxyClientConn(id, this, cf, forwardPort)
          }

          val clientContext = SSLContext.getInstance("TLS")
          clientContext.init(null,
                             Array[TrustManager](trustManager),
                             null)
          val sslengine = clientContext.createSSLEngine()
          sslengine.setUseClientMode(true)
          val handler = new KgpClientHandler(this, mkconn)
          // Set up the pipeline factory.
          bootstrap.setPipelineFactory(new ChannelPipelineFactory {
            override def getPipeline: ChannelPipeline = {
              Channels.pipeline(new SslHandler(sslengine),
                                new KgpPacketDecoder(),
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
          log.info("Successful handshake with Sauce Connect server")
          if (kgpChannel.outBuffer.length > 0) {
            log.info("resending " + kgpChannel.outBuffer.length + " packets")
          }
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

        case Send(connId: Long,  msg: ChannelBuffer) => {
          val packets = kgpChannel.packetize(connId, msg)
          if (currentChannel != null) {
            for (packet <- packets) {
              currentChannel.write(packet)
            }
            //if (_r.nextFloat < 0.05) {
            //  currentChannel.close()
            //}
          }
        }

        case CloseSub(connId: Long) => {
          val packet = kgpChannel.closeConnPacket(connId)
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

  var announced = false
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

  implicit def ftofuturelistener(f: () => Unit) = new ChannelFutureListener {
    override def operationComplete(future: ChannelFuture) = f()
  }

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
    log.debug("KGP Connected")
    val sslHandler = ctx.getPipeline().get(classOf[SslHandler])
    sslHandler.handshake().addListener(() => {
      channelHandShook(e.getChannel)
    })
  }

  def channelHandShook(channel: Channel) = {
    if (keepaliveTimer != null) {
      log.info("keepalive timer still going when reconnecting, making a new one")
    }
    keepaliveTimer = new HashedWheelTimer()
    var localTimer = keepaliveTimer
    channel.write(("announce", client.metadataJson, kgpChannel))
    lastIncoming = System.currentTimeMillis
    def keepaliveHandler() {
      if (localTimer != keepaliveTimer) {
        log.info("old keepalive timer shutting down")
        return
      }
      if (keepaliveOutSeq %> keepaliveOutAcked) {
        val now = System.currentTimeMillis
        if (now - lastIncoming > calculatedTimeout) {
          log.warn("Sauce Connect connection stalled! " +
                   keepaliveOutAcked + '/' + keepaliveOutSeq + " acks, " +
                   (now - lastIncoming) + "ms since last recv, " +
                   calculatedTimeout + "ms timeout exceeded, " +
                   "min ack: " + minAckTime)
          channel.write("close").addListener(ChannelFutureListener.CLOSE)
          return
        } else {
          //log.info("LIVE!" + " " + keepaliveOutAcked + " " + '/' + " " + keepaliveOutSeq + " " + (now - lastIncoming) + " " + calculatedTimeout + " " + (calculatedTimeout - (now - lastIncoming)) + " " + minAckTime + " " + ctime())
        }
      }
      val packet = keepalivePacket()
      channel.write(packet)
      lastKeepaliveTime = System.currentTimeMillis

      localTimer.newTimeout(keepaliveHandler _, 1000, TimeUnit.MILLISECONDS)
    }

    localTimer.newTimeout(keepaliveHandler _, 1000, TimeUnit.MILLISECONDS)

    client.handleConnected(channel)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) = {
    log.warn("disconnected from Sauce Connect server")
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
        if (seq - keepaliveOutSeq > 50) {
          log.warn("received a very out-of-date ack " + seq + " " + keepaliveOutSeq)
        }
      }
    } else {
      log.warn("unknown control message:" + " " + msg.toString(UTF_8))
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
      case (ver: (Int, Int, Int), id: Array[Byte], metadata: Option[Any]) => {
        log.info("Tunnel host version: " + ver.productIterator.mkString(".") +
                 ", ID: " + ChannelBuffers.hexDump(wrappedBuffer(id)))
        kgpChannel.remoteEndpointId = id
        kgpChannel.remoteEndpointNum = BigInt(ChannelBuffers.hexDump(wrappedBuffer(kgpChannel.remoteEndpointId)), 16)
        announced = true
        client.handleAnnounced()
      }
      case (connId: Long, seq: Long, ack: Long, ctrl: Int, msg: ChannelBuffer) => {
        if (!announced) {
          log.error("Message recieved before announcement!  Disconnecting.")
          c.close()
        }

        lastIncoming = System.currentTimeMillis
        //log.info("got packet " + seq + " on " + connId + " " + ctime())

        if (ack %> kgpChannel.outAcked) {
          //log.info("got an ack on my output up to " + ack + " - was " + kgpChannel.outAcked)
          kgpChannel.outAcked = ack
        }
        if (connId == 0) {
          handleConnPacket(c, seq, ack, msg)
        } else if (seq %> kgpChannel.inSeq) {
          val nextSeq = (kgpChannel.inSeq + 1) % Kgp.MODULUS
          if (seq %> nextSeq) {
            log.warn("packet skip to " + seq + " expected " + kgpChannel.inSeq)
          }

          kgpChannel.inSeq = seq

          ctrl match {
            case 0 => {
              if (!kgpChannel.conns.contains(connId)) {
                kgpChannel.conns(connId) = mkconn(connId, c)
              }
              val conn = kgpChannel.conns(connId)
              if (msg.readableBytes > 0) {
                //log.info("GOT " + seq + " " + ack + " " + txt)
                conn.dataReceived(msg)
              }
            }
            case 1 => {
              if (kgpChannel.conns contains connId) {
                val conn = kgpChannel.conns(connId)
                conn.remoteShutdown()
              }
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
      keepaliveTimer = null
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
      case e: UnresolvedAddressException => {
        log.warn("Couldn't resolve Sauce Connect server address")
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
