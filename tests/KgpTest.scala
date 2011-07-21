package com.saucelabs.kgp.tests

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.EasyMockSugar

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
import com.saucelabs.kgp._

class KgpTest extends Spec with ShouldMatchers with EasyMockSugar {

  describe("KgpConn") {
    it("should not be shut down when initialized") {
      val client = mock[KgpClient]
      val conn = new KgpConn(1, client)
      conn.isRemoteShutdown should be (false)
      conn.isLocalShutdown should be (false)
    }

    it("should shut down both sides when the remote side is shut down") {
      val client = mock[KgpClient]
      expecting {
        client.kgpChannel.andReturn(new KgpChannel())
        client.close_sub(1)
      }

      whenExecuting(client) {
        val conn = new KgpConn(1, client)
        conn.remoteShutdown()
        conn.isRemoteShutdown should be (true)
        conn.isLocalShutdown should be (true)
      }
    }

    it("should only shut down locally when the local side is shut down") {
      val client = mock[KgpClient]
      expecting {
        client.kgpChannel.andReturn(new KgpChannel())
        client.close_sub(1)
      }

      whenExecuting(client) {
        val conn = new KgpConn(1, client)
        conn.localShutdown()
        conn.isRemoteShutdown should be (false)
        conn.isLocalShutdown should be (true)
      }
    }
  }


  describe("KgpChannel") {
    describe("when sending the first packet on the channel") {
      val c = new KgpChannel()
      val packet = c.keepalivePacket()
      it("starts at sequence number 1") {
        packet._2 should be (1L)
      }
      it("has nothing to ack, so uses 0") {
        packet._3 should be (0L)
      }
    }

    describe("when sending its second packet") {
      val c = new KgpChannel()
      c.keepalivePacket()
      val packet = c.keepalivePacket()
      it("increments the sequence number to 2") {
        packet._2 should be (2L)
      }
    }

    describe("when rolling over the sequence modulus") {
      val c = new KgpChannel()
      it("rolls over to to 0 when it reaches the modulus, and not before") {
        c.outSeq = Kgp.MODULUS - 2
        var packet = c.keepalivePacket()
        packet._2 should be (Kgp.MODULUS - 2)
        packet = c.keepalivePacket()
        packet._2 should be (Kgp.MODULUS - 1)
        packet = c.keepalivePacket()
        packet._2 should be (0L)
      }
    }

    describe("when sending a keepalive") {
      val c = new KgpChannel()
      val packet = c.keepalivePacket()
      it("uses channel 0") {
        packet._1 should be (0L)
      }
      it("uses no control flags") {
        packet._4 should be (0)
      }
      it("sends 'k' as the packet body") {
        packet._5 should be (wrappedBuffer(Array[Byte]('k')))
      }
      it("doesn't buffer the packet for retransmission") {
        c.outBuffer.length should be (0)
      }
    }

    describe("when sending a keepalive ack") {
      val c = new KgpChannel()
      c.inSeq = 2
      c.inAcked = 0
      val packet = c.ackPacket()
      it("uses channel 0") {
        packet._1 should be (0L)
      }
      it("uses no control flags") {
        packet._4 should be (0)
      }
      it("sends 'a' as the packet body") {
        packet._5 should be (wrappedBuffer(Array[Byte]('a')))
      }
      it("acks the latest incoming packet") {
        packet._3 should be (2)
        c.inAcked should be (2)
      }
      it("doesn't buffer the packet for retransmission") {
        c.outBuffer.length should be (0)
      }
    }

    describe("when sending a close conn packet") {
      val c = new KgpChannel()
      val packet = c.closeConnPacket(3)
      it("sends the packet on the channel being closed") {
        packet._1 should be (3)
      }
      it("uses a control flag of 1") {
        packet._4 should be (1)
      }
      it("buffers the packet for retransmission") {
        c.outBuffer.length should be (1)
        c.outBuffer(0) should be (packet)
      }
      it("sends no data") {
        packet._5.readableBytes should be (0)
      }
    }

    describe("when sending a data packet") {
      val c = new KgpChannel()
      val buf = wrappedBuffer(Array[Byte]('h', 'i'))
      val packet = c.nextPacket(4, buf)
      it("sends the packet on the requested channel") {
        packet._1 should be (4)
      }
      it("sets no control flags") {
        packet._4 should be (0)
      }
      it("buffers the packet for retransmission") {
        c.outBuffer.length should be (1)
        c.outBuffer(0) should be (packet)
      }
      it("sends the correct data") {
        packet._5 should be (buf)
      }
    }

    describe("when pruning the transmission buffer") {
      val c = new KgpChannel()
      val buf = wrappedBuffer(Array[Byte]('h', 'i'))
      it("removes all packets that have been acked, and none that haven't") {
        val packet1 = c.nextPacket(5, buf)
        val packet2 = c.nextPacket(6, buf)
        c.outAcked = packet1._2 // seq for this packet
        c.pruneOutBuffer()
        c.outBuffer.length should be (1)
        c.outBuffer(0) should be (packet2)
      }
    }
  }

  describe("KgpPacketDecoder") {
    describe("when created") {
      it("is not yet initialized by an announcement") {
        val kpd = new KgpPacketDecoder()
        kpd.initialized should be (false)
      }
    }

    describe("when not initialized") {
      val kpd = new KgpPacketDecoder()
      val ctx = mock[ChannelHandlerContext]
      val channel = mock[Channel]

      it("passes until it gets enough bytes for an announcement") {
        val buf = buffer(Kgp.INTRO_LEN - 1)
        for (i <- 0 until Kgp.INTRO_LEN - 1) {
          buf.writeByte('x')
        }
        val readerIndex = buf.readerIndex
        kpd.decode(ctx, channel, buf) should be (null)
        buf.readerIndex should be (readerIndex)
        kpd.initialized should be (false)
      }
      it("rejects invalid protocols in announcements") {
        val buf = buffer(Kgp.INTRO_LEN)
        for (i <- 0 until Kgp.INTRO_LEN) {
          buf.writeByte('x')
        }
        evaluating {
          kpd.decode(ctx, channel, buf)
        } should produce [CorruptedFrameException]
        kpd.initialized should be (false)
      }
      it("initializes when it gets a valid announcement") {
        val kpe = new KgpPacketEncoder()
        val c = new KgpChannel()
        val buf = kpe.encode(ctx, channel, ("announce", c))
        buf match {
          case buf: ChannelBuffer => {
            val (version, id) = kpd.decode(ctx, channel, buf)
            version should be (Kgp.VERSION)
            id should be (c.id)
          }
        }
        kpd.initialized should be (true)
      }
    }

    describe("when initialized") {
      val kpd = new KgpPacketDecoder()
      val ctx = mock[ChannelHandlerContext]
      val channel = mock[Channel]
      kpd.initialized = true

      it("passes until it gets enough bytes for a packet header") {
        val buf = buffer(Kgp.HEADER_LEN - 1)
        for (i <- 0 until Kgp.HEADER_LEN - 1) {
          buf.writeByte('x')
        }
        val readerIndex = buf.readerIndex
        kpd.decode(ctx, channel, buf) should be (null)
        buf.readerIndex should be (readerIndex)
      }
      it("passes until it gets enough bytes for a packet") {
        val buf = buffer(Kgp.HEADER_LEN)
        buf.writeInt(1)
        buf.writeInt(0)
        buf.writeInt(0)
        buf.writeShort(0.toShort)
        buf.writeShort(1)
        val readerIndex = buf.readerIndex
        kpd.decode(ctx, channel, buf) should be (null)
        buf.readerIndex should be (readerIndex)
      }
      it("decodes packets that a KpgPacketEncoder produces") {
        val kpe = new KgpPacketEncoder()
        val packet = (1L, 0L, 0L, 0, wrappedBuffer(Array[Byte]('x')))
        val buf = kpe.encode(ctx, channel, packet)
        buf match {
          case buf: ChannelBuffer => {
            val readerIndex = buf.readerIndex
            kpd.decode(ctx, channel, buf) should be (packet)
            buf.readerIndex should not be (readerIndex)
          }
        }
      }
    }

  }

  describe("KgpPacketEncoder") {
    it("writes announcements that a KgpPacketDecoder can decode") {
      val kpe = new KgpPacketEncoder()
      val c = new KgpChannel()
      val kpd = new KgpPacketDecoder()
      val ctx = mock[ChannelHandlerContext]
      val channel = mock[Channel]
      val buf = kpe.encode(ctx, channel, ("announce", c))
      buf match {
        case buf: ChannelBuffer => {
          val (version, id) = kpd.decode(ctx, channel, buf)
          version should be (Kgp.VERSION)
          id should be (c.id)
        }
      }
    }
    it("writes packets that a KgpPacketDecoder can decode") {
      val kpe = new KgpPacketEncoder()
      val c = new KgpChannel()
      val kpd = new KgpPacketDecoder()
      kpd.initialized = true
      val ctx = mock[ChannelHandlerContext]
      val channel = mock[Channel]
      val packet1 = (1L, 0L, 0L, 0, wrappedBuffer(Array[Byte]('x')))
      val buf = kpe.encode(ctx, channel, packet1)
      buf match {
        case buf: ChannelBuffer => {
          val packet2 = kpd.decode(ctx, channel, buf)
          packet2 should be (packet1)
        }
      }
    }
  }
}
