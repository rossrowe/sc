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

import com.saucelabs.sauceconnect.proxy.SauceProxy

import org.apache.commons.cli._
import org.bouncycastle.util.encoders.Base64
import util.parsing.json.JSON
import org.python.core.PyList
import org.python.core.PyString
import org.python.util.PythonInterpreter

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.ArrayList

import collection.mutable
import io.Source

object Json {
  def quote(s: String): String = {
    "\"" +
    s
    .replace("\\", "\\\\")
    .replace("\b", "\\b")
    .replace("\f", "\\f")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t") +
    "\""
  }

  def encode(m: Map[String, Any]): String = {
    "{" +
    m.fold("") {
      case (acc: String, (k: String, v: String)) => {
        acc +
        (if (acc.length > 0) ", " else "") +
        quote(k) + ": " + quote(v)
      }
      case (acc: String, (k: String, v: Map[String, Any])) => {
        acc +
        (if (acc.length > 0) ", " else "") +
        quote(k) + ": " + encode(v)
      }
      case (acc: String, (k: String, v: Any)) => {
        acc +
        (if (acc.length > 0) ", " else "") +
        quote(k) + ": " + v
      }
    } +
    "}"
  }

  def decode(s: String): Map[String, Any] = {
    return JSON.parseFull(s).get.asInstanceOf[Map[String, Any]]
  }
}

/**
 * Third party libraries wishing to invoke the SauceConnect library should first invoke {@link #openConnection()},
 * which will create the SSH Tunnel.  The tunnel can be closed by first invoking the {@link #removeHandler()} and then
 * the {@link #closeTunnel()} methods.
 */
object SauceConnect {
  var _interpreter:PythonInterpreter = null

  val BUILD = 25
  val RELEASE = 17
  var commandLineArguments:CommandLine = null
  var liteMode:Boolean = false
  var standaloneMode:Boolean = true
  var restURL = ""
  var sePort = ""
  var username = ""
  var apikey = ""
  var strippedArgs = new PyList()
  var tunnel:Tunnel = null

  def main(args: Array[String]) {
    storeCommandLineArgs(args)
    for (s <- args) {
      if (s.equals("-l") || s.equals("--lite")) {
        liteMode = true
      } else {
        strippedArgs.add(new PyString(s))
      }
    }
    openConnection()
  }


  def storeCommandLineArgs(args: Array[String]) {
    val options = new Options()
    val readyfile = new Option("f", "readyfile", true, "Ready file that will be touched when tunnel is ready")
    readyfile.setArgName("FILE")
    options.addOption(readyfile)
    options.addOption("x", "rest-url", true, "Advanced feature: Connect to Sauce OnDemand at alternative URL." +
                      " Use only if directed to by Sauce Labs support.")

    OptionBuilder.withArgName("HOSTNAME")
    OptionBuilder.hasArg
    OptionBuilder.withDescription("Set 'proxy-host' field on jobs to the same " +
                                  "value to use this Sauce Connect connection. " +
                                  "Defaults to sauce-connect.proxy.")
    OptionBuilder.withLongOpt("proxy-host")
    options.addOption(OptionBuilder.create("p"))

    OptionBuilder.withLongOpt("dont-update-proxy-host")
    OptionBuilder.withDescription("Don't update default proxy-host value for " +
                                  "this account while tunnel is running.")
    options.addOption(OptionBuilder.create())

    options.addOption("h", "help", false, "Display this help text")
    options.addOption("v", "version", false, "Print the version and exit")
    options.addOption("b", "boost-mode", false, null)
    options.addOption("s", "ssh", false, null)
    options.addOption("d", "debug", false, "Enable verbose debugging")
    options.addOption("l", "lite", false, null)
    val sePortOpt = new Option("P", "se-port", true, null)
    sePortOpt.setArgName("PORT")
    options.addOption(sePortOpt)
    val fastFail = new Option("F", "fast-fail-regexps", true, null)
    fastFail.setArgName("REGEXPS")
    options.addOption(fastFail)
    val squidOpts = new Option("S", "squid-opts", true, null)
    fastFail.setArgName("OPTIONS")
    options.addOption(squidOpts)
    try {
      val parser = new PosixParser()
      val result = parser.parse(options, args)
      if (result.hasOption("help")) {
        val help = new HelpFormatter()
        help.printHelp("java -jar Sauce-Connect.jar USERNAME API_KEY [OPTIONS]", options)
        if (standaloneMode) {
          System.exit(0)
        }
      }
      if (result.hasOption("version")) {
        println("Version: Sauce Connect 3.0-r" + RELEASE)
        if (standaloneMode) {
          System.exit(0)
        }
      }
      if (result.getArgs.length == 0) {
        throw new ParseException("Missing required arguments USERNAME, API_KEY")
      }
      if (result.getArgs.length == 1) {
        throw new ParseException("Missing required argument API_KEY")
      }

      commandLineArguments = result

      sePort = commandLineArguments.getOptionValue("se-port", "4445")
      restURL = commandLineArguments.getOptionValue("rest-url", "https://saucelabs.com/rest/v1")
      username = commandLineArguments.getArgs()(0)
      apikey = commandLineArguments.getArgs()(1)
    } catch {
      case e:ParseException => {
        System.err.println(e.getMessage)
        System.err.println()
        val help = new HelpFormatter()
        help.printHelp("java -jar Sauce-Connect.jar USERNAME API_KEY [OPTIONS]", options)
        if (standaloneMode) {
          System.exit(1)
        }
      }
    }
  }

  // Not threadsafe
  def interpreter(): PythonInterpreter = {
    this.synchronized {
      if (_interpreter == null) {
        _interpreter = new PythonInterpreter()
        _interpreter.exec("import sauce_connect")
      }
      return _interpreter
    }
  }

  def setInterpreterIfNull(anInterpreter: PythonInterpreter) {
    this.synchronized {
      if (_interpreter == null) {
        _interpreter = anInterpreter
      }
    }
  }

  // Not threadsafe
  def getHealthCheckInterval(): Long = {
    return interpreter.eval("sauce_connect.HEALTH_CHECK_INTERVAL").asInt * 1000
  }



  def generateArgsForSauceConnect(domain: String,
                                  options: CommandLine): PyList = {
    val args = new ArrayList[PyString]()
    args.add(new PyString("-u"))
    args.add(new PyString(username))
    args.add(new PyString("-k"))
    args.add(new PyString(apikey))
    args.add(new PyString("-d"))
    args.add(new PyString(domain))
    args.add(new PyString("-s"))
    args.add(new PyString("127.0.0.1"))
    args.add(new PyString("-p"))
    args.add(new PyString("80"))
    args.add(new PyString("--ssh-port"))
    args.add(new PyString("443"))
    args.add(new PyString("-b"))
    args.add(new PyString("--rest-url"))
    args.add(new PyString(restURL))
    args.add(new PyString("--se-port"))
    args.add(new PyString(sePort))
    if (options != null) {
      if (options.hasOption('f')) {
        args.add(new PyString("--readyfile"))
          args.add(new PyString(options.getOptionValue('f')))
      }
      if (options.hasOption('s')) {
        args.add(new PyString("--ssh"))
      }
      if (options.hasOption('F')) {
        args.add(new PyString("--fast-fail-regexps"))
        args.add(new PyString(options.getOptionValue('F')))
      }
      args.add(new PyString("--squid-opts"))
      if (options.hasOption('S')) {
        args.add(new PyString(options.getOptionValue('S')))
      } else {
        args.add(new PyString(""))
      }
    }

    println(args)
    return new PyList(args)
  }

  def openConnection() {
    versionCheck()
    setupArgumentList()
    setupTunnel()
    addShutdownHandler()
    startTunnel()
  }

  def startTunnel() {
    try {
      if (commandLineArguments.hasOption("s")) {
        SauceConnect.interpreter.exec("from com.saucelabs.sauceconnect import ReverseSSH as JavaReverseSSH")
      } else {
        SauceConnect.interpreter.exec("from com.saucelabs.sauceconnect import KgpTunnel as JavaReverseSSH")
      }
      var startCommand: String = null
      if (liteMode) {
        startCommand = ("sauce_connect.run(options,"
                        + "setup_signal_handler=setup_java_signal_handler,"
                        + "reverse_ssh=JavaReverseSSH)")
      } else {
        startCommand = ("sauce_connect.run(options,"
                        + "setup_signal_handler=setup_java_signal_handler,"
                        + "reverse_ssh=JavaReverseSSH,do_check_version=False,"
                        + "release=\"3.0-r" + RELEASE + "\","
                        + "build=\"" + BUILD + "\")")
      }
      SauceConnect.interpreter.exec(startCommand)
    } catch {
      case e:Exception => {
        if (commandLineArguments.hasOption("d")) {
          e.printStackTrace()
        }
        //only invoke a System.exit() if we are in standalone mode
        if (standaloneMode) {
          System.exit(3)
        }
      }
    }
  }

  def addShutdownHandler() {
    //only add the shutdown handlers if we are in standalone mode
    if (standaloneMode) {
      val mainThread = Thread.currentThread()
      Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
          removeHandler()
          if (liteMode) {
            mainThread.interrupt()
          }
          closeTunnel()
        }
      })
    }
  }

  def setupArgumentList() {
    if (liteMode) {
      SauceConnect.interpreter.set("arglist", strippedArgs)
    } else {
      var domain = "sauce-connect.proxy"

      if (commandLineArguments.hasOption("proxy-host")) {
        domain = commandLineArguments.getOptionValue("proxy-host")
      }
      if (!commandLineArguments.hasOption("dont-update-proxy-host")) {
        val port = 33128
        //updateDefaultProxyHost(domain, port)
      }
      SauceConnect.interpreter.set(
        "arglist",
        generateArgsForSauceConnect(domain, commandLineArguments))
    }
  }

  def startProxy() {
    try {
      val proxy = new SauceProxy(0, "", 0)
      proxy.start()
      SauceConnect.interpreter.exec("options.ports = ['" + proxy.getPort + "']")
    } catch {
      case e:Exception => {
        System.err.println("Error starting proxy: " + e.getMessage)
        //only invoke a System.exit() if we are in standalone mode
        if (standaloneMode) {
          System.exit(2)
        }
      }
    }
  }

  def setupTunnel() {
    setupLogging()
    if (!liteMode) {
      startProxy()
    }
    setupSignalHandler()
  }

  def setupSignalHandler() {
    SauceConnect.interpreter.exec("tunnel_for_java_to_kill = None")
    SauceConnect.interpreter.exec("def setup_java_signal_handler(tunnel, options):\n"
                     + "  global tunnel_for_java_to_kill\n" + "  tunnel_for_java_to_kill = tunnel\n")
  }

  def setupLogging() {
    SauceConnect.interpreter.exec("options = sauce_connect.get_options(arglist)")
    SauceConnect.interpreter.exec("sauce_connect.setup_logging(options.logfile, options.quiet)")
    PythonLogHandler.install()
  }

  def closeTunnel() {
    SauceConnect.interpreter.exec("sauce_connect.peace_out(tunnel_for_java_to_kill, atexit=True)")
  }

  def removeHandler() {
    if (!liteMode) {
      if (commandLineArguments != null && !commandLineArguments.hasOption("dont-update-proxy-host")) {
        updateDefaultProxyHost(null, 0)
      }
    }
    SauceConnect.interpreter.exec("sauce_connect.logger.removeHandler(sauce_connect.fileout)")
  }

  def updateDefaultProxyHost(proxyHost: String,
                             proxyPort: Int) {
    try {
      val restEndpoint = new URL(restURL + "/" + username + "/defaults")
      var auth = username + ":" + apikey
      auth = "Basic " + new String(Base64.encode(auth.getBytes))
      val connection = restEndpoint.openConnection()
      connection.setRequestProperty("Authorization", auth)
      val data = Source.fromInputStream(connection.getInputStream).mkString("")
      val currentDefaults = mutable.Map(Json.decode(data).toSeq: _*)
      if (proxyHost != null) {
        currentDefaults("proxy-host") = proxyHost + ":" + proxyPort
      } else {
        currentDefaults.remove("proxy-host")
      }

      val postBack = restEndpoint.openConnection.asInstanceOf[HttpURLConnection]
      postBack.setDoOutput(true)
      postBack.setRequestMethod("PUT")
      postBack.setRequestProperty("Authorization", auth)
      val newDefaults = Json.encode(currentDefaults.toMap)
      postBack.getOutputStream.write(newDefaults.getBytes)
      postBack.getInputStream.close()
    } catch {
      case e:IOException => {
        System.err.println("Error connecting to Sauce OnDemand REST API: ")
        e.printStackTrace()
        if (standaloneMode) {
          System.exit(5)
        }
      }
      case e:org.json.simple.parser.ParseException => {
        System.err.println("Error reading from Sauce OnDemand REST API: ")
        e.printStackTrace()
        if (standaloneMode) {
          System.exit(5)
        }
      }
    }
  }

  def versionCheck() {
    try {
      if (liteMode) {
        return
      }
      val downloadURL = getDownloadURL(RELEASE)
      if (downloadURL != null) {
        System.err.println("** This version of Sauce Connect is outdated.\n" +
                           "** Please update with " + downloadURL)
      }
    } catch {
      case e:IOException => {
        System.err.println("Error checking Sauce Connect version:")
        e.printStackTrace()
      }
      case e:org.json.simple.parser.ParseException => {
        System.err.println("Error checking Sauce Connect version:")
        e.printStackTrace()
      }
    }
  }

  /**
   * Get the download URL for the newer release of Sauce Connect if this version is outdated.
   *
   * @param localRelease
   * @return The download URL or null if the release is current.
   * @throws IOException
   * @throws org.json.simple.parser.ParseException
   *
   */
  def getDownloadURL(localRelease: Int): String = {
    val versionsURL = new URL("https://saucelabs.com/versions.json")
    val data = Source.fromInputStream(versionsURL.openStream()).mkString("")
    val versions = Json.decode(data)
    if (!versions.contains("Sauce Connect 2")) {
      return "https://saucelabs.com/downloads/Sauce-Connect-2-latest.zip"
    }
    val versionDetails = versions.get("Sauce Connect 2").get.asInstanceOf[Map[String, Any]]
    val remoteVersion = versionDetails.get("version").get.asInstanceOf[String]
    val remoteRelease = remoteVersion.substring(remoteVersion.indexOf("-r") + 2).toInt
    if (localRelease < remoteRelease) {
      return versionDetails.get("download_url").get.asInstanceOf[String]
    } else {
      return null
    }
  }

  def setStandaloneMode(standaloneMode: Boolean) {
    this.standaloneMode = standaloneMode
  }

  def reportError(info: String): Boolean = {
    if (tunnel == null) {
      return false
    }
    return tunnel.reportError(info)
  }
}


abstract class Tunnel {
    def reportError(info: String): Boolean
}
