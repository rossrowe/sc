/*
 * Adapted from the Selenium project under the Apache License 2.0
 *
 * Portions Copyright 2011 Sauce Labs, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.saucelabs.sauceconnect.proxy


import java.io._
import org.eclipse.jetty.io.EofException
import org.apache.commons.logging.{Log, LogFactory}

import scala.util.control.Breaks._

object IOProxy {
  protected val log = LogFactory.getLog(this.getClass)

  val BUFFER_SIZE = 8192

  /**
   * Proxy Stream in to Stream out until EOF or exception.
   */
  def proxy(in: InputStream, out: OutputStream): Long = proxy(in, out, -1)
  def proxy(in: Reader, out: Writer): Long = proxy(in, out, -1)

  /**
   * Proxy Stream in to Stream for count bytes or until EOF or exception.
   *
   * @return Copied bytes count or -1 if no bytes were read *and* EOF was reached
   */
  def proxy(in: InputStream, out: OutputStream, count: Long): Long = {
    val buffer = new Array[Byte](BUFFER_SIZE)
    var numCopied = 0
    var c = count
    var read_size = BUFFER_SIZE

    // count < 0 will read until EOF
    breakable {
      while (c != 0) {
        if (0 < c && c < BUFFER_SIZE) {
          read_size = c.toInt
        }
        val len = in.read(buffer, 0, read_size)
        if (len == -1) break

        try {
          out.write(buffer, 0, len)
        } catch {
          case e:Exception => {
            log.warn("Exception writing " + len + " bytes in IOProxy, c=" + c + ", count=" + count + ", read_size=" + read_size + ", numCopied=" + numCopied + ", exception: " + e)
            throw e
          }
        }
        numCopied += len
        if (c > 0) { c -= len }
      }
    }

    return numCopied
  }

  /**
   * Proxy Reader to Writer for count characters or until EOF or exception.
   */
  def proxy(in: Reader, out: Writer, count: Long): Long = {
    val buffer = new Array[Char](BUFFER_SIZE)
    var numCopied = 0L
    var c = count
    var read_size = BUFFER_SIZE

    // count < 0 will read until EOF
    breakable {
      while (c != 0) {
        if (0 < c && c < BUFFER_SIZE) {
          read_size = c.toInt
        }
        val len = in.read(buffer, 0, read_size)
        if (len == -1) break

        try {
          out.write(buffer, 0, len)
        } catch {
          case e:Exception => {
            log.warn("Exception writing " + len + " bytes in IOProxy, c=" + c + ", count=" + count + ", read_size=" + read_size + ", numCopied=" + numCopied + ", exception: " + e)
            throw e
          }
        }
        numCopied += len
        if (c > 0) { c -= len }
      }
    }

    return numCopied
  }
}
