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

import scala.util.control.Breaks._

import org.mortbay.util.IO
import java.io._

object IOProxy {
  // Copy Stream in to Stream out until EOF or exception.
  def proxy(in:InputStream, out:OutputStream) : Long = proxy(in, out, -1)
  def proxy(in:Reader, out:Writer) : Long = proxy(in, out, -1)


  def proxy(in:InputStream, out:OutputStream, byteCount:Long) : Long = {
    val buffer = new Array[Byte](IO.bufferSize)
    var len = 0
    var returnVal:Long = 0
    var c = byteCount

    if (c >= 0) {
      while (c > 0) {
        if (c < IO.bufferSize)
          len = in.read(buffer, 0, c.asInstanceOf[Int])
        else
          len = in.read(buffer, 0, IO.bufferSize)

        if (len == -1) {
          return returnVal
        }
        returnVal += len

        c -= len
        out.write(buffer, 0, len)
      }
    } else {
      while (true) {
        len = in.read(buffer, 0, IO.bufferSize)
        if (len == -1) {
          return returnVal
        }
        returnVal += len
        out.write(buffer, 0, len)
      }
    }

    return returnVal
  }

  def proxy(in:Reader, out:Writer, byteCount:Long) : Long = {
    val buffer = new Array[Char](IO.bufferSize)
    var len = 0
    var returnVal:Long = 0
    var c = byteCount

    if (c >= 0) {
      while (c > 0) {
        if (c < IO.bufferSize)
          len = in.read(buffer, 0, c.asInstanceOf[Int])
        else
          len = in.read(buffer, 0, IO.bufferSize)

        if (len == -1) {
          return returnVal
        }
        returnVal += len

        c -= len
        out.write(buffer, 0, len)
      }
    } else {
      while (true) {
        len = in.read(buffer, 0, IO.bufferSize)
        if (len == -1) {
          return returnVal
        }
        returnVal += len
        out.write(buffer, 0, len)
      }
    }

    return returnVal
  }

}
