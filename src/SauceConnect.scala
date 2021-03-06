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
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.bouncycastle.util.encoders.Base64
import util.parsing.json.JSON
import org.python.core.PyList
import org.python.core.PyString
import org.python.util.PythonInterpreter

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net._
import java.util.ArrayList
import java.lang.reflect.InvocationTargetException

import collection.mutable
import io.Source
import scala.tools.util.SignalManager

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
  private val log = LogFactory.getLog(this.getClass)
  var _interpreter:PythonInterpreter = null

  val BUILD = 44
  val RELEASE = 29
  var commandLineArguments:CommandLine = null
  var standaloneMode:Boolean = true
  var logfile = ""
  var logfilesize = ""
  var sePort = ""
  var restURL = ""
  var username = ""
  var apikey = ""
  var proxyConf = new Array[String](2)
  var proxyUser = ""
  var proxyPassword = ""
  var strippedArgs = new PyList()
  var tunnel:Tunnel = null
  var sauceProxy:SauceProxy = null

  def main(args: Array[String]) {
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    for (sig <- Array("ABRT", "BREAK", "HUP", "INT", "QUIT", "TERM")) {
      try {
        SignalManager(sig) += log.warn("received SIG" + sig)
      } catch {
        case e: InvocationTargetException => {}
      }
    }
    storeCommandLineArgs(args)
    if (proxyConf(0) != null && proxyConf(1) != null) {
      System.setProperty("http.proxyHost", proxyConf(0))
      System.setProperty("https.proxyHost", proxyConf(0))
      System.setProperty("http.proxyPort", proxyConf(1))
      System.setProperty("https.proxyPort", proxyConf(1))
      System.setProperty("http.nonProxyHosts", "localhost")
    }
    if (proxyUser != "") {
      System.setProperty("http.proxyUser", proxyUser)
      System.setProperty("https.proxyUser", proxyUser)
    }
    if (proxyPassword != "") {
      System.setProperty("http.proxyPassword", proxyPassword)
      System.setProperty("https.proxyPassword", proxyPassword)
    }
    if (proxyUser != "" && proxyPassword != "") {
      Authenticator.setDefault(
         new Authenticator {
            override def getPasswordAuthentication = new PasswordAuthentication(proxyUser, proxyPassword.toCharArray())
         }
      )
    }
    for (s <- args) {
      strippedArgs.add(new PyString(s))
    }
    openConnection()
    System.exit(0)
  }


  def storeCommandLineArgs(args: Array[String]) {
    val options = new Options()
    val readyfile = new Option("f", "readyfile", true, "Ready file that will be touched when tunnel is ready.")
    readyfile.setArgName("FILE")
    options.addOption(readyfile)

    options.addOption("x", "rest-url", true, "Advanced feature: Connect to Sauce REST API at alternative URL." +
                                             " Use only if directed to do so by Sauce Labs support.")
    OptionBuilder.withArgName("HOSTNAME")
    options.addOption("h", "help", false, "Display this help text.")
    options.addOption("v", "version", false, "Print the version and exit.")
    options.addOption("d", "debug", false, "Enable verbose debugging.")

    val logfileOpt = new Option("l", "logfile", true, null)
    logfileOpt.setArgName("LOGFILE")
    options.addOption(logfileOpt)

    val logfilesizeOpt = new Option("g", "logfilesize", true, null)
    logfilesizeOpt.setArgName("LOGFILESIZE")
    options.addOption(logfilesizeOpt)

    val proxyOpt = new Option("p", "proxy", true, "Proxy host and port that Sauce Connect should use to send connections from browsers" +
                                                  " in the Sauce Labs cloud to be able to access the AUT.")
    proxyOpt.setArgName("PROXYHOST:PROXYPORT")
    options.addOption(proxyOpt)

    val proxyAuthOpt = new Option("u", "proxy-user", true, "Username required to access proxy host")
    proxyAuthOpt.setArgName("PROXYUSER")
    options.addOption(proxyAuthOpt)

    val proxyPassOpt = new Option("X", "proxy-password", true, "Password required to access proxy host")
    proxyPassOpt.setArgName("PROXYPASS")
    options.addOption(proxyPassOpt)

    val sePortOpt = new Option("P", "se-port", true, "Port in which Sauce Connect's Selenium relay will listen for requests." +
                                                     " Selenium commands reaching Connect on this port will be relayed to Sauce Labs" +
                                                     " securely and reliably through Connect's tunnel.")
    sePortOpt.setArgName("PORT")
    options.addOption(sePortOpt)

    val fastFail = new Option("F", "fast-fail-regexps", true, "Comma-separated list of regular expressions." +
                                                              " Requests matching one of these will get dropped instantly and will not" +
                                                              " go through the tunnel.")
    fastFail.setArgName("REGEXP1,REGEXP2")
    options.addOption(fastFail)

    val directDomains = new Option("D", "direct-domains", true, "Comma-separated list of domains." +
                                                            " Requests whose host matches one of these will be relayed directly through" +
                                                            " the internet, instead of through the tunnel.")
    directDomains.setArgName("DOMAIN1,DOMAIN2")
    options.addOption(directDomains)

    val noSSLBumpDomains = new Option("B", "no-ssl-bump-domains", true, "Comma-separated list of domains." +
                                                            " Requests whose host matches one of these will not be SSL re-encrypted")
    noSSLBumpDomains.setArgName("DOMAIN1,DOMAIN2")
    options.addOption(noSSLBumpDomains)

    val sharedTunnel = new Option("s", "shared-tunnel", false, "Let sub-accounts of the tunnel owner use the tunnel if requested.")
    options.addOption(sharedTunnel)

    val tunnelIdentifier = new Option("i", "tunnel-identifier", true, "Don't automatically assign jobs to this tunnel. Jobs will use it only" +
                                                                      " by explicitly providing the right identifier.")
    tunnelIdentifier.setArgName("TUNNELIDENTIFIER")
    options.addOption(tunnelIdentifier)

    val vmVersion = new Option("V", "vm-version", true, "Request a special VM version to be booted for the Sauce Labs end of the tunnel.")
    vmVersion.setArgName("VMVERSION")
    options.addOption(vmVersion)

    val squidOpts = new Option("S", "squid-opts", true, "Configuration used for the Squid reverse proxy in our end." +
                                                        " Use only if directed to do so by Sauce Labs support.")
    squidOpts.setArgName("SQUID-OPTIONS")
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

      println("Sauce Connect 3.0-r" + RELEASE + ", build " + BUILD)
      if (result.hasOption("version")) {
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

      logfile = commandLineArguments.getOptionValue("logfile", "sauce_connect.log")
      logfilesize = commandLineArguments.getOptionValue("logfilesize", "31457280")
      sePort = commandLineArguments.getOptionValue("se-port", "4445")
      restURL = commandLineArguments.getOptionValue("rest-url", "https://saucelabs.com/rest/v1")
      username = commandLineArguments.getArgs()(0)
      apikey = commandLineArguments.getArgs()(1)
      if (apikey.length < 36) {
        throw new ParseException("""|Invalid API_KEY provided. Please use your Access Key to start Sauce Connect, not your password.
                                    |You can find your Access Key in your account page: https://saucelabs.com/account""".stripMargin)
      }
      val proxyString : String = commandLineArguments.getOptionValue("proxy", "")
      if (proxyString != "") {
        proxyConf = proxyString.split(":")
      }
      proxyUser = commandLineArguments.getOptionValue("proxy-user", "")
      proxyPassword = commandLineArguments.getOptionValue("proxy-password", "")
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
    args.add(new PyString("-s"))
    args.add(new PyString("127.0.0.1"))
    args.add(new PyString("-t"))
    args.add(new PyString("80"))
    args.add(new PyString("--ssh-port"))
    args.add(new PyString("443"))
    args.add(new PyString("-b"))
    args.add(new PyString("--rest-url"))
    args.add(new PyString(restURL))
    args.add(new PyString("--se-port"))
    args.add(new PyString(sePort))
    args.add(new PyString("--logfile"))
    args.add(new PyString(logfile))
    args.add(new PyString("--logfilesize"))
    args.add(new PyString(logfilesize))
    if (options != null) {
      if (!options.hasOption('i')) {
        args.add(new PyString("-d"))
        args.add(new PyString(domain))
      }
      if (options.hasOption('f')) {
        args.add(new PyString("--readyfile"))
        args.add(new PyString(options.getOptionValue('f')))
      }
      if (options.hasOption('F')) {
        args.add(new PyString("--fast-fail-regexps"))
        args.add(new PyString(options.getOptionValue('F')))
      }
      if (options.hasOption('D')) {
        args.add(new PyString("--direct-domains"))
        args.add(new PyString(options.getOptionValue('D')))
      }
      if (options.hasOption('B')) {
        args.add(new PyString("--no-ssl-bump-domains"))
        args.add(new PyString(options.getOptionValue('B')))
      }
      if (options.hasOption('s')) {
        args.add(new PyString("--shared-tunnel"))
      }
      if (options.hasOption('i')) {
        args.add(new PyString("--tunnel-identifier"))
        args.add(new PyString(options.getOptionValue('i')))
      }
      if (options.hasOption('V')) {
        args.add(new PyString("--vm-version"))
        args.add(new PyString(options.getOptionValue('V')))
      }
      args.add(new PyString("--squid-opts"))
      if (options.hasOption('S')) {
        args.add(new PyString(options.getOptionValue('S')))
      } else {
        args.add(new PyString(""))
      }
    }

    return new PyList(args)
  }

  def openConnection() {
    versionCheck()
    setupArgumentList()
    setupTunnel()
    startProxy()
    addShutdownHandler()
    startTunnel()
    if (sauceProxy != null) {
      sauceProxy.stop()
    }
  }

  def startTunnel() {
    try {
      SauceConnect.interpreter.exec("from com.saucelabs.sauceconnect import KgpTunnel as JavaReverseSSH")
      var startCommand: String = null
      startCommand = ("sauce_connect.run(options,"
                      + "setup_signal_handler=setup_java_signal_handler,"
                      + "reverse_ssh=JavaReverseSSH,"
                      + "release=\"3.0-r" + RELEASE + "\","
                      + "build=\"" + BUILD + "\")")
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
          closeTunnel()
        }
      })
    }
  }

  def setupArgumentList() {
    var domain = "sauce-connect.proxy"

    SauceConnect.interpreter.set(
      "arglist",
      generateArgsForSauceConnect(domain, commandLineArguments))
    SauceConnect.interpreter.exec("options = sauce_connect.get_options(arglist)")
  }

  def startProxy() {
    try {
      val noSSLBumpDomains = commandLineArguments.getOptionValue("no-ssl-bump-domains")
      var array = Array[String]()
      if (noSSLBumpDomains != null && !noSSLBumpDomains.eq("")) {
         array = noSSLBumpDomains.split(",")
      }
      sauceProxy = new SauceProxy(0, "", 0, array)
      sauceProxy.start()
      SauceConnect.interpreter.exec("options.ports = ['" + sauceProxy.getPort + "']")
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
    setupSignalHandler()
  }

  def setupSignalHandler() {
    SauceConnect.interpreter.exec("tunnel_for_java_to_kill = None")
    SauceConnect.interpreter.exec("def setup_java_signal_handler(tunnel, options):\n"
                     + "  global tunnel_for_java_to_kill\n" + "  tunnel_for_java_to_kill = tunnel\n")
  }

  def setupLogging() {
    SauceConnect.interpreter.exec("sauce_connect.setup_logging(options.logfile, options.quiet, options.logfilesize)")
    PythonLogHandler.install()
  }

  def closeTunnel() {
    SauceConnect.interpreter.exec("sauce_connect.peace_out(tunnel_for_java_to_kill, atexit=True)")
  }

  def removeHandler() {
    SauceConnect.interpreter.exec("sauce_connect.logger.removeHandler(sauce_connect.fileout)")
  }

  def versionCheck() {
    try {
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
