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

import java.io.{File, IOException}

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.python.core._

import com.saucelabs.kgp.{KgpClient, Connect, Close, ProxyServer}

class KgpTunnel {
  private val MAX_RECONNECT_ATTEMPTS = 3
  private val log = LogFactory.getLog(this.getClass)

  /* kwargs to constructor called from Python cause fields to be set */
  var host = ""
  var tunnel:PyObject = null
  var ports:Array[String] = null
  var tunnel_ports:Array[String] = null
  var use_ssh_config = false
  var debug = false
  var ssh_port = 0
  var se_port = 0

  private var tunnelConnection:KgpClient = null
  private var readyfile:File = null

  private def getTunnelSetting(name:String) : String = {
    return this.tunnel.__getattr__(name).asString()
  }

  def run(readyfile:String) = {
    if (readyfile != null) {
      this.readyfile = new File(readyfile)
      this.readyfile.delete()
      this.readyfile.deleteOnExit()
    }
    log.info("Starting KGP connection...")
    connect()
    if (this.readyfile != null) {
      this.readyfile.createNewFile()
    }
    val forwarded_health = new HealthChecker(this.host, ports)
    val health_check_interval = SauceConnect.getHealthCheckInterval()
    while (true) {
      forwarded_health.check()
      val start = System.currentTimeMillis()
      val t = new Timeout(health_check_interval){
        @throws(classOf[Exception])
        override def longRunningTask() = {
          //val s = tunnelConnection.wtf()
          //s.close()
        }
      }
      t.go()
      if(t.isSuccess()){
        if ((health_check_interval - (System.currentTimeMillis()-start)) > 0) {
          Thread.sleep(health_check_interval - (System.currentTimeMillis()-start))
        }
      } else {
        log.info("health check failed")
        //reconnect()
      }
    }
  }

  private def connect() = {
    val host = getTunnelSetting("host")
    val user = getTunnelSetting("user")
    val password = getTunnelSetting("password")
    log.info("KGP connecting to " + host + " as " + user)

    tunnelConnection = new KgpClient(host,
                                     this.ssh_port,
                                     this.ports(0).toInt,
                                     "{\"username\": \"" + user + "\", \"access_key\": \"" + password + "\"}")
    tunnelConnection.start()
    tunnelConnection.connect()
    val proxyServer = new ProxyServer(tunnelConnection, this.se_port)
    proxyServer.serve()
    log.info("Selenium proxy listening on port " + this.se_port)
    //tunnelConnection.authenticateWithPassword(user, password)
    for (index <- 0 until ports.length) {
      val remotePort = Integer.valueOf(tunnel_ports(index))
      val localPort = Integer.valueOf(ports(index))
      //tunnelConnection.requestRemotePortForwarding("0.0.0.0", remotePort, this.host,
      //                                             localPort)
    }
    log.info("KGP Connected. You may start your tests.")
  }

  private def reconnect() : Unit = {
    log.info("KGP connection failed. Re-connecting ..")
    for(attempts <- 0 until MAX_RECONNECT_ATTEMPTS) {
      Thread.sleep(3000)
      val connector = new Timeout(10000) {
        @throws(classOf[Exception])
        override def longRunningTask() = {
          connect()
        }
      }
      connector.go()
      if(connector.isSuccess()){
        log.info("KGP successfully re-connected")
        return
      } else {
        log.info("Re-connect failed. Trying again ..")
      }
    }
    log.info("Unable to re-establish connection to Sauce Labs")
    throw new RuntimeException("Unable to re-establish connection to Sauce Labs")
  }

  def stop() = {
    if (this.readyfile != null) {
      this.readyfile.delete()
    }
    tunnelConnection.close()
  }
}