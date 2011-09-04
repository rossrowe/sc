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

import scala.util.control.Breaks._


object ModifiedIO {

  val BUFFER_SIZE = 8192

  /**
   * Copy Stream in to Stream out until EOF or exception.
   */
  def copy(in: InputStream, out: OutputStream): Long = copy(in, out, -1)
  def copy(in: Reader, out: Writer): Long = copy(in, out, -1)

  /**
   * Copy Stream in to Stream for count bytes or until EOF or exception.
   *
   * @return Copied bytes count or -1 if no bytes were read *and* EOF was reached
   */
  def copy(in: InputStream, out: OutputStream, count: Long): Long = {
    val buffer = new Array[Byte](BUFFER_SIZE)
    var len = 0
    var numCopied = 0
    var c = count

    // count < 0 will read until EOF
    while (c != 0) breakable {
      len = in.read(buffer, 0, math.min(c, BUFFER_SIZE).toInt)

      if (len == -1) {
        break
      }

      out.write(buffer, 0, len)
      numCopied += len
      if (c > 0) { c -= len }
    }

    return numCopied
  }

  /**
   * Copy Reader to Writer for count characters or until EOF or exception.
   */
  def copy(in: Reader, out: Writer, count: Long): Long = {
    val buffer = new Array[Char](BUFFER_SIZE)
    var len = 0
    var numCopied = 0L
    var c = count

    // count < 0 will read until EOF
    while (c != 0) breakable {
      len = in.read(buffer, 0, math.min(c, BUFFER_SIZE).toInt)

      if (len == -1) {
        break
      }

      out.write(buffer, 0, len)
      numCopied += len
      if (c > 0) { c -= len }
    }

    return numCopied
  }
}
