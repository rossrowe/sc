// ========================================================================
// Copyright 2011 Sauce Labs, Inc
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
// ========================================================================

package com.saucelabs.sauceconnect

import com.saucelabs.sauceconnect.proxy.Jetty7ProxyHandler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ConnectHandler
import org.eclipse.jetty.server.nio.SelectChannelConnector
import org.eclipse.jetty.util.thread.QueuedThreadPool

class SauceProxy {
  val server = new Server()

  val connector = new SelectChannelConnector()
  // val connector = new SocketConnector()
  connector.setPort(0)
  connector.setMaxIdleTime(20000)
  connector.setThreadPool(new QueuedThreadPool(256))

  server.addConnector(connector)

  val handler = new Jetty7ProxyHandler(true)
  server.setHandler(handler)

  // Returns the local port of the first connector for the server.
  def getPort() = this.server.getConnectors()(0).getLocalPort()

  def start() = this.server.start()

  def main(args:Array[String]) = {
        new SauceProxy().start()
  }
}