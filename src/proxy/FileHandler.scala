/*
Copyright 2007-2009 WebDriver committers
Copyright 2007-2009 Google Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

// Copyright 2008 Google Inc.  All Rights Reserved.
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

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.channels.FileChannel

import scala.util.control.Breaks._

/**
 * Utility methods for common filesystem activities
 */
object FileHandler {
  val JDK6_SETWRITABLE = findJdk6SetWritableMethod()
  val CHMOD_SETWRITABLE = findChmodCommand()


  def copyResource(outputDir: File,
                   forClassLoader: Class[_],
                   names: String*) = {
    for (name <- names) {
      val is = locateResource(forClassLoader, name)
      try {
        Zip.unzipFile(outputDir, is, name)
      } finally {
        Cleanly.close(is)
      }
    }
  }

  def locateResource(forClassLoader: Class[_], name: String): InputStream = {
    val arch = System.getProperty("os.arch").toLowerCase() + "/"
    val alternatives = Array(name, "/" + name, arch + name, "/" + arch + name)

    // First look using our own classloader
    for (possibility <- alternatives) {
      var stream = this.getClass.getResourceAsStream(possibility)
      if (stream != null) {
        return stream
      }
      stream = forClassLoader.getResourceAsStream(possibility)
      if (stream != null) {
        return stream
      }
    }

    throw new IOException("Unable to locate: " + name)
  }


  def createDir(dir: File): Boolean = {
    if ((dir.exists || dir.mkdirs()) && dir.canWrite) {
      return true
    }

    if (dir.exists) {
      FileHandler.makeWritable(dir)
      return dir.canWrite
    }

    // Iterate through the parent directories until we find that exists,
    // then sink down.
    return createDir(dir.getParentFile())
  }

  def makeWritable(file: File): Boolean = {
    if (file.canWrite) {
      return true
    }

    if (JDK6_SETWRITABLE != null) {
      try {
        return JDK6_SETWRITABLE.invoke(
          file,
          true.asInstanceOf[AnyRef]).asInstanceOf[Boolean]
      } catch {
        case e:IllegalAccessException => {}
        case e:InvocationTargetException => {}
      }
    } else if (CHMOD_SETWRITABLE != null) {
      try {
        val process = Runtime.getRuntime().exec(
            Array(CHMOD_SETWRITABLE.getAbsolutePath, "+x", file.getAbsolutePath))
        process.waitFor()
        return file.canWrite
      } catch {
        case e1:InterruptedException => throw new IOException("test")
      }
    }
    return false
  }

  def isZipped(file: String) = file.endsWith(".zip") || file.endsWith(".xpi")

  def delete(toDelete: File): Boolean = {
    var deleted = true

    if (toDelete.isDirectory) {
      for (child <- toDelete.listFiles()) {
        deleted &= child.canWrite && delete(child)
      }
    }

    return deleted && toDelete.canWrite && toDelete.delete()
  }

  def copy(from: File, to: File): Unit = copy(from, to, new NoFilter())

  def copy(source: File, dest: File, suffix: String): Unit = {
    val filter = if (suffix == null) {
      new NoFilter()
    } else {
      new FileSuffixFilter(suffix)
    }
    copy(source, dest, filter)
  }

  def copy(source: File, dest: File, onlyCopy: Filter): Unit = {
    if (!source.exists) {
      return
    }

    if (source.isDirectory) {
      copyDir(source, dest, onlyCopy)
    } else {
      copyFile(source, dest, onlyCopy)
    }
  }

  def copyDir(from: File, to: File, onlyCopy: Filter): Unit = {
    if (!onlyCopy.isRequired(from)) {
      return
    }

    // Create the target directory.
    createDir(to)

    // List children.
    for (child <- from.list()) {
      if (!".parentlock".equals(child) && !"parent.lock".equals(child)) {
        copy(new File(from, child), new File(to, child), onlyCopy)
      }
    }
  }

  def copyFile(from: File, to: File, onlyCopy: Filter): Unit = {
    if (!onlyCopy.isRequired(from)) {
      return
    }

    var out: FileChannel = null
    var in: FileChannel = null
    try {
      in = new FileInputStream(from).getChannel()
      out = new FileOutputStream(to).getChannel()
      val length = in.size()

      val copied = in.transferTo(0, in.size, out)
      if (copied != length) {
        throw new IOException("Could not transfer all bytes.")
      }
    } finally {
      Cleanly.close(out)
      Cleanly.close(in)
    }
  }

  /**
   * File.setWritable appears in Java 6. If we find the method,
   * we can use it
   */
  def findJdk6SetWritableMethod(): Method = {
    try {
      return classOf[File].getMethod("setWritable", classOf[Boolean])
    } catch {
      case e:NoSuchMethodException => return null
    }
  }

  /**
   * In JDK5 and earlier, we have to use a chmod command from the path.
   */
  def findChmodCommand(): File = {
    // Search the path for chmod
    val allPaths = System.getenv("PATH")
    val paths = allPaths.split(File.pathSeparator)
    for (path <- paths) {
      val chmod = new File(path, "chmod")
      if (chmod.exists) {
        return chmod
      }
    }
    return null
  }

  /**
   * Used by file operations to determine whether or not to make use of a file.
   */
  abstract class Filter {
    /**
     * @param file File to be considered.
     * @return Whether or not to make use of the file in this oprtation.
     */
    def isRequired(file: File): Boolean
  }

  class FileSuffixFilter(suffix: String) extends Filter {
    def isRequired(file: File): Boolean = {
      return file.isDirectory || file.getAbsolutePath.endsWith(suffix)
    }
  }

  class NoFilter extends Filter {
    def isRequired(file: File) = true
  }

  def readAsString(toRead: File): String = {
    var reader: Reader = null
    try {
      reader = new BufferedReader(new FileReader(toRead))
      val builder = new StringBuilder()

      val buffer = new Array[Char](4096)
      var read = 0
      breakable {
        while (true) {
          read = reader.read(buffer)
          if (read == -1) break
          val target = new Array[Char](read)
          System.arraycopy(buffer, 0, target, 0, read)
          builder.appendAll(target)
        }
      }

      return builder.toString
    } finally {
      Cleanly.close(reader)
    }
  }
}
