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

import org.bouncycastle.util.encoders.Base64
import java.io.{File, IOException, OutputStreamWriter}
import java.net.{HttpURLConnection, URL}
import io.Source

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.python.core._

import com.saucelabs.sauceconnect.proxy.SauceProxy
import com.saucelabs.kgp.{KgpClient, ProxyServer}

class KgpTunnel extends Tunnel {
  private val MAX_RECONNECT_ATTEMPTS = 3
  private val log = LogFactory.getLog(this.getClass)

  /* kwargs to constructor called from Python cause fields to be set */
  var host = ""
  var tunnel: PyObject = null
  var ports: Array[String] = null
  var tunnel_ports: Array[String] = null
  var use_ssh_config = false
  var debug = false
  var ssh_port = 0
  var se_port = ""

  private var client: KgpClient = null
  private var readyfile: File = null

  var proxyServer: ProxyServer = null
  var sauceProxy: SauceProxy = null

  SauceConnect.tunnel = this

  private def getTunnelSetting(name:String) : String = {
    return this.tunnel.__getattr__(name).asString()
  }

  def checkProxy(): Boolean = {
    try {
      val proxyURL = new URL("http://localhost:%s/" format (proxyServer.getPort))
      val data = Source.fromInputStream(proxyURL.openStream()).mkString("")
      return (data == "OK,ondemand alive")
    } catch {
      case e:IOException => return false
    }
  }

  def restCall(method: String, path: String,
               data: Map[String, Any]): Map[String, Any] = {
    val restURL = new URL("%s/%s%s" format
                          (SauceConnect.restURL,
                           SauceConnect.username,
                           path))
    var auth = SauceConnect.username + ":" + SauceConnect.apikey
    auth = "Basic " + new String(Base64.encode(auth.getBytes))
    val conn = restURL.openConnection.asInstanceOf[HttpURLConnection]
    conn.setDoOutput(true)
    conn.setRequestMethod(method)
    conn.setRequestProperty("Authorization", auth)
    conn.setRequestProperty("Content-Type", "application/json")
    if (data != null) {
      val body = Json.encode(data)
      val os = conn.getOutputStream
      os.write(body.getBytes("utf-8"))
      os.flush()
      os.close()
    }
    val code = conn.getResponseCode
    val stream = if (200 <= code && code < 300) {
      conn.getInputStream
    } else {
      conn.getErrorStream
    }
    val result = Source.fromInputStream(stream).mkString("")
    stream.close()
    return Json.decode(result)
  }

  def reportConnectedStatus() = {
    try {
      val path = "/tunnels/%s/connected" format (getTunnelSetting("id"))
      restCall("POST", path, null)
    } catch {
      case e:IOException => {
        System.err.println("Error connecting to Sauce OnDemand REST API: ")
        e.printStackTrace()
      }
    }
  }

  def checkTunnelStatus(): Boolean = {
    try {
      val path = "/tunnels/%s" format (getTunnelSetting("id"))
      val result = restCall("GET", path, null)
      if (!result.contains("status")) {
        log.error("no status field in tunnel GET result: " + result)
        return false
      }
      if (result("status") != "running") {
        log.info("status check found tunnel in " + result("status"))
        return false
      }
    } catch {
      case e:IOException => {
        System.err.println("Error connecting to Sauce OnDemand REST API: ")
        e.printStackTrace()
      }
    }
    return true
  }

  def reportError(info: String): Boolean = {
    try {
      log.error("REPORTING an error to Sauce Labs with info: " + info)
      val data = restCall("POST", "/errors",
                          Map("Tunnel" -> getTunnelSetting("id"),
                              "Info" -> info))
      val status = (data contains "result") && (data("result") == true)
      if (!status) {
        log.error("ERROR during reporting to Sauce Labs: " + data)
      }
      return status
    } catch {
      case e:IOException => {
        System.err.println("Error reporting to Sauce OnDemand REST API: ")
        e.printStackTrace()
      }
    }
    return false
  }

  def run(readyfile:String): Unit = {
    if (readyfile != null) {
      this.readyfile = new File(readyfile)
      this.readyfile.delete()
      this.readyfile.deleteOnExit()
    }

    val forwarded_health = new HealthChecker(this.host, ports)
    forwarded_health.check()

    log.info("Starting connection to tunnel host...")
    connect()
    if (this.readyfile != null) {
      this.readyfile.createNewFile()
    }
    val health_check_interval = SauceConnect.getHealthCheckInterval
    val report_interval = 60
    var last_report = 0L
    try {
      while (true) {
        val start = System.currentTimeMillis()

        if (!checkTunnelStatus()) {
          log.warn("Remote tunnel VM no longer running, shutting down")
          stop()
          return
        }

        forwarded_health.check()

        if (start - last_report > report_interval) {
          if (checkProxy()) {
            reportConnectedStatus()
          } else {
            log.warn("End-to-end connection check failed")
          }
          last_report = start
        }
        if ((health_check_interval - (System.currentTimeMillis()-start)) > 0) {
          Thread.sleep(health_check_interval - (System.currentTimeMillis()-start))
        }
      }
    } catch {
      case e:Exception => {
        System.err.println("Error checking tunnel status: " + e.getMessage)
        throw e;
      }
    }
  }

  private def connect() = {
    val host = getTunnelSetting("host")
    val user = getTunnelSetting("user")
    val password = getTunnelSetting("password")
    log.info("Connecting to tunnel host " + host + " as " + user)

    client = new KgpClient(host,
                           this.ssh_port,
                           this.ports(0).toInt,
                           "{\"username\": \"" + user + "\", \"access_key\": \"" + password + "\"}")
    client.start()
    client.connect()

    proxyServer = new ProxyServer(client, 0)
    proxyServer.serve()
    log.info("Forwarding Selenium with ephemeral port " + proxyServer.getPort)

    if (se_port == "off") {
      log.info("Selenium HTTP proxy disabled")
    } else {

      try {
        sauceProxy = new SauceProxy(se_port.toInt, "localhost",
                                   proxyServer.getPort)
        sauceProxy.start()
      } catch {
        case e:Exception => {
          System.err.println("Error starting proxy: " + e.getMessage)
        }
      }
      log.info("Selenium HTTP proxy listening on port " + se_port)
    }

    //client.authenticateWithPassword(user, password)
    for (index <- 0 until ports.length) {
      val remotePort = tunnel_ports(index).toInt
      val localPort = ports(index).toInt
      //client.requestRemotePortForwarding("0.0.0.0", remotePort, this.host,
      //                                             localPort)
    }
    client.waitForConnection()
    log.info("Connected! You may start your tests.")
  }

  private def reconnect() : Unit = {
    log.info("Connection to tunnel host failed. Re-connecting ..")
    for(attempts <- 0 until MAX_RECONNECT_ATTEMPTS) {
      Thread.sleep(3000)
      val connector = new Timeout(10000) {
        override def longRunningTask() = {
          connect()
        }
      }
      connector.go()
      if(connector.isSuccess()){
        log.info("Successfully re-connected to tunnel host")
        return
      } else {
        log.info("Re-connect failed. Trying again...")
      }
    }
    log.info("Unable to re-establish connection to tunnel host")
    throw new RuntimeException("Unable to re-establish connection to tunnel host")
  }

  def stop() = {
    if (this.readyfile != null) {
      this.readyfile.delete()
    }
    client.close()
    if (sauceProxy != null) {
      sauceProxy.stop()
    }
    if (proxyServer != null) {
      proxyServer.stop()
    }
  }
}
