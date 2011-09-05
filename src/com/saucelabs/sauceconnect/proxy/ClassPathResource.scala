/*
 * Copyright 2006 ThoughtWorks, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
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

import org.eclipse.jetty.util.resource.Resource
import org.mortbay.util.IO


import java.io._
import java.net.MalformedURLException
import java.net.URL

/**
 * Represents resource file off of the classpath.
 *
 * @author Patrick Lightbody (plightbo at gmail dot com)
 */
class ClassPathResource(path: String) extends Resource {

  var os: ByteArrayOutputStream = null

  val is = ResourceExtractor.getSeleniumResourceAsStream(path)
  if (is != null) {
    os = new ByteArrayOutputStream()
    try {
      IO.copy(is, os)
    } catch {
      case e:IOException => e.printStackTrace()
    }
  }


  override def isContainedIn(resource: Resource) = false

  def release() = {}
  def exists() = os != null
  def isDirectory() = false

  /**
   * Returns the lastModified time, which is always in the distant future to
   * prevent caching.
   */
  def lastModified() = System.currentTimeMillis() + 1000L * 3600L * 24L * 365L

  def length(): Long = {
    if (os != null) {
      return os.size
    }

    return 0
  }

  def getURL(): URL = null
  def getFile(): File = null
  def getName() = path

  def getInputStream(): InputStream = {
    if (os != null) {
      return new ByteArrayInputStream(os.toByteArray())
    }
    return null
  }

  def getOutputStream(): OutputStream = null

  def delete() = false
  def renameTo(dest: Resource) = false

  def list() = new Array[String](0)

  def addPath(pathParm: String) = new ClassPathResource(path + "/" + pathParm)

  override def toString() = getName()
}
