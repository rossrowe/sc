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

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

import scala.util.control.Breaks._

object Zip {
  val BUF_SIZE = 16384 // "big"

  def zip(inputDir: File, output: File): Unit = {
    if (output.exists) {
      throw new IOException("File already exists: " + output)
    }

    var fos: FileOutputStream = null
    try {
      fos = new FileOutputStream(output)
      zip(inputDir, fos)
    } finally {
      Cleanly.close(fos)
    }
  }

  def zip(inputDir: File): String = {
    val bos = new ByteArrayOutputStream()

    try {
      zip(inputDir, bos)
      return Base64Encoder.encode(bos.toByteArray)
    } finally {
      Cleanly.close(bos)
    }
  }

  def zip(inputDir: File, writeTo: OutputStream): Unit = {
    var zos: ZipOutputStream = null
    try {
      zos = new ZipOutputStream(writeTo)
      addToZip(inputDir.getAbsolutePath, zos, inputDir)
    } finally {
      Cleanly.close(zos)
    }
  }

  def addToZip(basePath: String, zos: ZipOutputStream, toAdd: File): Unit = {
    if (toAdd.isDirectory) {
      for (file <- toAdd.listFiles()) {
        addToZip(basePath, zos, file)
      }
    } else {
      val fis = new FileInputStream(toAdd)
      val name = toAdd.getAbsolutePath().substring(basePath.length() + 1)

      val entry = new ZipEntry(name)
      zos.putNextEntry(entry)

      val buffer = new Array[Byte](4096)
      while (true) breakable {
        val len = fis.read(buffer)
        if (len == -1) break
        zos.write(buffer, 0, len)
      }

      fis.close()
      zos.closeEntry()
    }
  }

  def unzip(source: String, outputDir: File): Unit = {
    val bytes = Base64Encoder.decode(source)

    var bis: ByteArrayInputStream = null
    try {
      bis = new ByteArrayInputStream(bytes)
      unzip(bis, outputDir)
    } finally {
      Cleanly.close(bis)
    }
  }

  def unzip(source: File, outputDir: File): Unit = {
    var fis: FileInputStream = null

    try {
      fis = new FileInputStream(source)
      unzip(fis, outputDir)
    } finally {
      Cleanly.close(fis)
    }
  }

  def unzip(source: InputStream, outputDir: File): Unit = {
    val zis = new ZipInputStream(source)

    try {
      while (true) breakable {
        val entry = zis.getNextEntry()
        if (entry == null) break
        val file = new File(outputDir, entry.getName)
        if (entry.isDirectory) {
          FileHandler.createDir(file)
        } else {
          unzipFile(outputDir, zis, entry.getName)
        }
      }
    } finally {
      Cleanly.close(zis)
    }
  }

  def unzipFile(output: File, zipStream: InputStream, name: String) = {
    val toWrite = new File(output, name)

    if (!FileHandler.createDir(toWrite.getParentFile())) {
       throw new IOException("Cannot create parent director for: " + name)
    }

    val out = new BufferedOutputStream(new FileOutputStream(toWrite), BUF_SIZE)
    try {
      val buffer = new Array[Byte](BUF_SIZE)
      while (true) breakable {

        val read = zipStream.read(buffer)
        if (read == -1) break

        out.write(buffer, 0, read)
      }
    } finally {
      out.close()
    }
  }
}
