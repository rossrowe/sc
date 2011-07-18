package com.saucelabs.kgp.tests

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.EasyMockSugar
import org.jboss.netty.buffer.ChannelBuffers._
import com.saucelabs.kgp._

class KgpTest extends Spec with ShouldMatchers with EasyMockSugar {

  describe("KgpConn") {
    it("should not be shut down when initialized") {
      val client = mock[KgpClient]
      val conn = new KgpConn(1, client)
      conn.isRemoteShutdown should equal (false)
      conn.isLocalShutdown should equal (false)
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
        conn.isRemoteShutdown should equal (true)
        conn.isLocalShutdown should equal (true)
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
        conn.isRemoteShutdown should equal (false)
        conn.isLocalShutdown should equal (true)
      }
    }
  }


  describe("KgpChannel") {
    describe("when sending the first packet on the channel") {
      val c = new KgpChannel()
      val packet = c.keepalivePacket()
      it("starts at sequence number 1") {
        packet._2 should equal (1L)
      }
      it("has nothing to ack, so uses 0") {
        packet._3 should equal (0L)
      }
    }

    describe("when sending its second packet") {
      val c = new KgpChannel()
      c.keepalivePacket()
      val packet = c.keepalivePacket()
      it("increments the sequence number to 2") {
        packet._2 should equal (2L)
      }
    }

    describe("when rolling over the sequence modulus") {
      val c = new KgpChannel()
      it("rolls over to to 0 when it reaches the modulus, and not before") {
        c.outSeq = Kgp.MODULUS - 2
        var packet = c.keepalivePacket()
        packet._2 should equal (Kgp.MODULUS - 2)
        packet = c.keepalivePacket()
        packet._2 should equal (Kgp.MODULUS - 1)
        packet = c.keepalivePacket()
        packet._2 should equal (0L)
      }
    }

    describe("when sending a keepalive") {
      val c = new KgpChannel()
      val packet = c.keepalivePacket()
      it("uses channel 0") {
        packet._1 should equal (0L)
      }
      it("uses no control flags") {
        packet._4 should equal (0)
      }
      it("sends 'k' as the packet body") {
        packet._5 should equal (wrappedBuffer(Array[Byte]('k')))
      }
      it("doesn't buffer the packet for retransmission") {
        c.outBuffer.length should equal (0)
      }
    }

    describe("when sending a keepalive ack") {
      val c = new KgpChannel()
      c.inSeq = 2
      c.inAcked = 0
      val packet = c.ackPacket()
      it("uses channel 0") {
        packet._1 should equal (0L)
      }
      it("uses no control flags") {
        packet._4 should equal (0)
      }
      it("sends 'a' as the packet body") {
        packet._5 should equal (wrappedBuffer(Array[Byte]('a')))
      }
      it("acks the latest incoming packet") {
        packet._3 should equal (2)
        c.inAcked should equal (2)
      }
      it("doesn't buffer the packet for retransmission") {
        c.outBuffer.length should equal (0)
      }
    }

    describe("when sending a close conn packet") {
      val c = new KgpChannel()
      val packet = c.closeConnPacket(3)
      it("sends the packet on the channel being closed") {
        packet._1 should equal (3)
      }
      it("uses a control flag of 1") {
        packet._4 should equal (1)
      }
      it("buffers the packet for retransmission") {
        c.outBuffer.length should equal (1)
        c.outBuffer(0) should equal (packet)
      }
      it("sends no data") {
        packet._5.readableBytes should equal (0)
      }
    }

    describe("when sending a data packet") {
      val c = new KgpChannel()
      val buf = wrappedBuffer(Array[Byte]('h', 'i'))
      val packet = c.nextPacket(4, buf)
      it("sends the packet on the requested channel") {
        packet._1 should equal (4)
      }
      it("sets no control flags") {
        packet._4 should equal (0)
      }
      it("buffers the packet for retransmission") {
        c.outBuffer.length should equal (1)
        c.outBuffer(0) should equal (packet)
      }
      it("sends the correct data") {
        packet._5 should equal (buf)
      }
    }
  }
}
