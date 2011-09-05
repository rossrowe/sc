/*
 * Created on Oct 17, 2006
 *
 */
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

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import scala.collection.JavaConversions._

object ResourceExtractor {
  val BUF_SIZE = 8192

  def extractResourcePath(resourcePath: String, dest: File): File = {
    return extractResourcePath(ResourceExtractor.getClass, resourcePath, dest)
  }

  def extractResourcePath(cl: Class[_], resourcePath: String, dest: File): File = {
    val alwaysExtract = true
    val url = cl.getResource(resourcePath)
    if (url == null) {
      throw new IllegalArgumentException("Resource not found: " + resourcePath)
    }
    if ("jar".equalsIgnoreCase(url.getProtocol())) {
      val jarFile = getJarFileFromUrl(url)
      extractResourcePathFromJar(cl, jarFile, resourcePath, dest)
    } else {
      try {
        val resourceFile = new File(new URI(url.toExternalForm()))
        if (!alwaysExtract) {
          return resourceFile
        }
        FileHandler.copy(resourceFile, dest)
      } catch {
        case e:URISyntaxException => {
          throw new RuntimeException("Couldn't convert URL to File:" + url, e)
        }
      }
    }
    return dest
  }

   def extractResourcePathFromJar(cl: Class[_],
                                  jarFile: File,
                                  resourcePath: String,
                                  dest: File) = {
     val z = new ZipFile(jarFile, ZipFile.OPEN_READ)
     val zipStyleResourcePath = resourcePath.substring(1) + "/"
     if (z.getEntry(zipStyleResourcePath) != null) {
       // DGF If it's a directory, then we need to look at all the entries
       for (ze <- z.entries) {
         if (ze.getName.startsWith(zipStyleResourcePath)) {
           val relativePath = ze.getName.substring(zipStyleResourcePath.length)
           val destFile = new File(dest, relativePath)
           if (ze.isDirectory) {
             destFile.mkdirs()
           } else {
             val fos = new FileOutputStream(destFile)
             copyStream(z.getInputStream(ze), fos)
           }
         }
       }
     } else {
       val fos = new FileOutputStream(dest)
       copyStream(getSeleniumResourceAsStream(resourcePath), fos)
     }
   }

  def getSeleniumResourceAsStream(resourceFile: String): InputStream = {
    var clazz: Class[_] = classOf[ClassPathResource]
    var input = clazz.getResourceAsStream(resourceFile)
    if (input == null) {
      try {
        // This is hack for the OneJar version of Selenium-Server.
        // Examine the contents of the jar made by
        // https://svn.openqa.org/svn/selenium-rc/trunk/selenium-server-onejar/build.xml
        clazz = Class.forName("OneJar")
        input = clazz.getResourceAsStream(resourceFile)
      } catch { case e:ClassNotFoundException => }
    }
    return input
  }

  def getJarFileFromUrl(url: URL): File = {
    if (!"jar".equalsIgnoreCase(url.getProtocol()))
      throw new IllegalArgumentException("This is not a Jar URL:"
                                         + url.toString())
    val resourceFilePath = url.getFile()
    val index = resourceFilePath.indexOf("!")
    if (index == -1) {
      throw new RuntimeException("Bug! " + url.toExternalForm()
                                 + " does not have a '!'")
    }
    val jarFileURI = resourceFilePath.substring(0, index).replace(" ", "%20")
    try {
      val jarFile = new File(new URI(jarFileURI))
      return jarFile
    } catch {
      case e:URISyntaxException => {
        throw new RuntimeException("Bug! URI failed to parse: " + jarFileURI, e)
      }
    }
  }

  def copyStream(in: InputStream, out: OutputStream) = {
    try {
      val buffer = new Array[Byte](BUF_SIZE)
      var count = 0
       do {
         out.write(buffer, 0, count)
         count = in.read(buffer, 0, buffer.length)
       } while (count != -1)
    } finally {
      Cleanly.close(out)
      Cleanly.close(in)
    }
  }
}
