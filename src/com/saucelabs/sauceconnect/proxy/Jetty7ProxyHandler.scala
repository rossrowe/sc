package com.saucelabs.sauceconnect.proxy

import cybervillains.ca.KeyStoreManager
import org.eclipse.jetty.http._
import org.eclipse.jetty.io.Buffer
import org.eclipse.jetty.io.Connection
import org.eclipse.jetty.io.EndPoint
import org.eclipse.jetty.io.nio.IndirectNIOBuffer
import org.eclipse.jetty.server.HttpConnection
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ConnectHandler
import org.eclipse.jetty.server.ssl.SslSocketConnector
import org.eclipse.jetty.util.IO
import org.eclipse.jetty.util.StringMap
import org.eclipse.jetty.util.log.Log
import org.eclipse.jetty.util.log.Logger

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.nio.channels.SocketChannel
import java.util.Enumeration
import java.util.LinkedHashMap
import java.util.Map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import scala.util.control.Breaks._
import scala.collection.immutable.HashSet
import scala.io.Source

class InsensitiveStringSet extends scala.collection.mutable.HashSet[String] {
  override def contains(elem:String) : Boolean = {
    this.iterator exists (elem.toLowerCase == _.toLowerCase)
  }
}

class Jetty7ProxyHandler(trustAllSSLCertificates:Boolean) extends ConnectHandler {
  protected def _logger = Log.getLogger(getClass().getName())

  protected def useCyberVillains = true

  /** * This lock is very important to ensure that SeleniumServer and
  the underlying Jetty instance * shuts down properly. It ensures that
  ProxyHandler does not add an SslRelay to the Jetty server *
  dynamically (needed for SSL proxying) if the server has been shut
  down or is in the process * of getting shut down.  */
  protected def shutdownLock = new Object()

  protected var _anonymous = false
  @transient
  protected var _chained = false

  // Map of leg by leg headers (not end to end)
  protected val _DontProxyHeaders = new InsensitiveStringSet()
  _DontProxyHeaders += HttpHeaders.PROXY_CONNECTION
  _DontProxyHeaders += HttpHeaders.CONNECTION
  _DontProxyHeaders += HttpHeaders.KEEP_ALIVE
  _DontProxyHeaders += HttpHeaders.TRANSFER_ENCODING
  _DontProxyHeaders += HttpHeaders.TE
  _DontProxyHeaders += HttpHeaders.TRAILER
  _DontProxyHeaders += HttpHeaders.UPGRADE

  // Map of leg by leg headers (not end to end).
  protected val _ProxyAuthHeaders = new InsensitiveStringSet()
  _ProxyAuthHeaders += HttpHeaders.PROXY_AUTHORIZATION
  _ProxyAuthHeaders += HttpHeaders.PROXY_AUTHENTICATE

  // Map of allowable schemes to proxy
  protected val _ProxySchemes = new InsensitiveStringSet()
  _ProxySchemes += HttpSchemes.HTTP
  _ProxySchemes += HttpSchemes.HTTPS
  _ProxySchemes += "ftp"

  protected val _sslMap = new LinkedHashMap[String, SslRelay]();

  override def handle(target:String, baseRequest:Request, request:javax.servlet.http.HttpServletRequest, response:javax.servlet.http.HttpServletResponse) {
    if (HttpMethods.CONNECT.equalsIgnoreCase(request.getMethod())) {
      _logger.debug("CONNECT request for {}", request.getRequestURI())
      handleConnect(baseRequest, request, response, request.getRequestURI())
      return
    }
    val host = request.getRequestURI()
    println("proxying for " + host)
    try {

      // Has the requested resource been found?
      // if ("True".equals(response.getAttribute("NotFound"))) {
      //   response.removeAttribute("NotFound")
      //   sendNotFound(response)
      //   return
      // }

      // Do we proxy this?
      if (!validateDestination(host)) {
        Log.info("ProxyHandler: Forbidden destination " + host)
        response.setStatus(HttpServletResponse.SC_FORBIDDEN)
        baseRequest.setHandled(true)
        return
      }

      // is this URL a /selenium URL?
      if (isSeleniumUrl(host)) {
        baseRequest.setHandled(false)
        return
      }

      proxyPlainTextRequest(baseRequest, request, response)
    }
    catch {
      case e:Exception => {
        _logger.debug("Could not proxy " + host, e)
        if (!response.isCommitted())
          response.sendError(400, "Could not proxy " + host + "\n" + e)
      }
    }
  }


  protected def isSeleniumUrl(url:String) : Boolean = {
    val slashSlash = url.indexOf("//")
    if (slashSlash == -1) {
      return false
    }

    val nextSlash = url.indexOf("/", slashSlash + 2)
    if (nextSlash == -1) {
      return false
    }

    val seleniumServer = url.indexOf("/selenium-server/")
    if (seleniumServer == -1) {
      return false
    }

    // we do this complex checking because sometimes some sites/pages
    // (such as ominture ads) embed the referrer URL, which will
    // include selenium stuff, in to the query parameter, which would
    // fake out a simple String.contains() call. This method is more
    // robust and will catch this stuff.
    return seleniumServer == nextSlash
  }

  /**
   * <p>Handles a CONNECT request.</p>
   * <p>CONNECT requests may have authentication headers such as <code>Proxy-Authorization</code>
   * that authenticate the client with the proxy.</p>
   *
   * @param baseRequest   Jetty-specific http request
   * @param request       the http request
   * @param response      the http response
   * @param serverAddress the remote server address in the form {@code host:port}
   * @throws ServletException if an application error occurs
   * @throws IOException      if an I/O error occurs
   */
  protected override def handleConnect(baseRequest:Request, request:HttpServletRequest, response:HttpServletResponse, serverAddress:String) : Unit = {
    if (!handleAuthentication(request, response, serverAddress)) return

    var host = serverAddress
    var port = 80
    // When logging, we'll attempt to send messages to hosts that don't exist
    if (host.endsWith(".selenium.doesnotexist:443")) {
      // so we have to do set the host to be localhost (you can't new up an IAP with a non-existent hostname)
      port = 443
      host = "localhost"
    } else {
      val colon = serverAddress.indexOf(':')
      if (colon > 0) {
        host = serverAddress.substring(0, colon)
        port = Integer.parseInt(serverAddress.substring(colon + 1))
      }
    }

    if (!validateDestination(baseRequest, host)) {
      Log.info("ProxyHandler: Forbidden destination " + host)
      response.setStatus(HttpServletResponse.SC_FORBIDDEN)
      baseRequest.setHandled(true)
      return
    }

    // Transfer unread data from old connection to new connection
    // We need to copy the data to avoid races:
    // 1. when this unread data is written and the server replies before the clientToProxy
    // connection is installed (it is only installed after returning from this method)
    // 2. when the client sends data before this unread data has been written.
    val httpConnection = HttpConnection.getCurrentConnection()

    val server = httpConnection.getServer()
    val listener = getSslRelayOrCreateNew(baseRequest.getUri(), port, server)

    val channel = connectToServer(request, host, port)

    //val listernerPort = listener.getPort()


    val headerBuffer = httpConnection.getParser().asInstanceOf[HttpParser].getHeaderBuffer()
    val bodyBuffer = httpConnection.getParser().asInstanceOf[HttpParser].getBodyBuffer()
    var length = if (headerBuffer == null) 0 else headerBuffer.length()
    length += (if (bodyBuffer == null) 0 else bodyBuffer.length())
    var buffer:IndirectNIOBuffer = null
    if (length > 0) {
      buffer = new IndirectNIOBuffer(length)
      if (headerBuffer != null) {
        buffer.put(headerBuffer)
        headerBuffer.clear()
      }
      if (bodyBuffer != null) {
        buffer.put(bodyBuffer)
        bodyBuffer.clear()
      }
    }

    var context:ConcurrentMap[String, Object] = new ConcurrentHashMap[String, Object]()
    prepareContext(request, context)

    val clientToProxy = prepareConnections(context, channel, buffer)

    // CONNECT expects a 200 response
    response.setStatus(HttpServletResponse.SC_OK)

    // Prevent close
    baseRequest.getConnection().getGenerator().setPersistent(true)

    // Close to force last flush it so that the client receives it
    response.getOutputStream().close()

    upgradeConnection(request, response, clientToProxy)
  }


  def validateDestination(request:Request, host:String) : Boolean = {
    return _ProxySchemes.contains(request.getScheme()) && super.validateDestination(host)
  }

  protected def upgradeConnection(request:HttpServletRequest, response:HttpServletResponse, connection:Connection) = {
    // Set the new connection as request attribute and change the status to 101
    // so that Jetty understands that it has to upgrade the connection
    request.setAttribute("org.eclipse.jetty.io.Connection", connection)
    response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS)
    _logger.debug("Upgraded connection to {}", connection)
  }

  protected def prepareConnections(context:ConcurrentMap[String, Object], channel:SocketChannel, buffer:Buffer) = {
    val httpConnection = HttpConnection.getCurrentConnection()
    val proxyToServer = newProxyToServerConnection(context, buffer)
    val clientToProxy = newClientToProxyConnection(context, channel, httpConnection.getEndPoint(), httpConnection.getTimeStamp())
    clientToProxy.setConnection(proxyToServer)
    proxyToServer.setConnection(clientToProxy)
    clientToProxy
  }

  protected def connectToServer(request:HttpServletRequest, host:String, port:Int) : SocketChannel = {
    val channel = connect(request, host, port)
    channel.configureBlocking(false)
    return channel
  }

  protected def proxyPlainTextRequest(request:Request, httpRequest:javax.servlet.http.HttpServletRequest, response:javax.servlet.http.HttpServletResponse) : Long = {
    val url = new URL(request.getRequestURL().toString())
    val connection = url.openConnection()
    connection.setAllowUserInteraction(false)

    // Set method
    var http:HttpURLConnection = null
    if (connection.isInstanceOf[HttpURLConnection]) {
      http = connection.asInstanceOf[HttpURLConnection]
      http.setRequestMethod(request.getMethod())
      http.setInstanceFollowRedirects(false)
      if (trustAllSSLCertificates && connection.isInstanceOf[HttpsURLConnection]) {
        TrustEverythingSSLTrustManager.trustAllSSLCertificates(connection.asInstanceOf[HttpsURLConnection])
      }
    }

    // check connection header
    var connectionHdr = request.getHeader(HttpHeaders.CONNECTION)
    if (connectionHdr != null && (connectionHdr.equalsIgnoreCase(HttpHeaders.KEEP_ALIVE) || connectionHdr.equalsIgnoreCase("close")))
      connectionHdr = null

    // copy headers
    var xForwardedFor = false
    var isGet = "GET".equals(request.getMethod())
    var hasContent = false
    val enm = request.getHeaderNames()
    while (enm.hasMoreElements()) breakable {
      // TODO could be better than this!
      val hdr = enm.nextElement().asInstanceOf[String]

      if (_DontProxyHeaders.contains(hdr) || !_chained && _ProxyAuthHeaders.contains(hdr))
        break
      if (connectionHdr != null && connectionHdr.indexOf(hdr) >= 0)
        break

      if (!isGet && HttpHeaders.CONTENT_TYPE.equals(hdr))
        hasContent = true

      val vals = request.getHeaders(hdr)
      while (vals.hasMoreElements()) breakable {
        val v = vals.nextElement().asInstanceOf[String]
        if (v != null) {
          // don't proxy Referer headers if the referer is Selenium!
          if (("Referer".equals(hdr) && (-1 != v.indexOf("/selenium-server/"))))
            break
          if (!isGet && HttpHeaders.CONTENT_LENGTH.equals(hdr) && Integer.parseInt(v) > 0) {
            hasContent = true
          }

          connection.addRequestProperty(hdr, v)
          xForwardedFor |= HttpHeaders.X_FORWARDED_FOR.equalsIgnoreCase(hdr)
        }
      }
    }

    // Proxy headers
    if (!_anonymous)
      connection.setRequestProperty("Via", "1.1 (Sauce Connect)")
    if (!xForwardedFor)
      connection.addRequestProperty(HttpHeaders.X_FORWARDED_FOR, request.getRemoteAddr())

    // a little bit of cache control
    val cache_control = request.getHeader(HttpHeaders.CACHE_CONTROL)
    if (cache_control != null && (cache_control.indexOf("no-cache") >= 0 || cache_control.indexOf("no-store") >= 0))
      connection.setUseCaches(false)

    // customize Connection
    customizeConnection(request, connection)

    try {
      connection.setDoInput(true)

      // do input thang!
      val in = request.getInputStream()
      if (hasContent) {
        connection.setDoOutput(true)
        IO.copy(in, connection.getOutputStream())
      }

      // Connect
      connection.connect()
    }
    catch {
      case e:Exception => {
        //LogSupport.ignore(log, e)
      }
    }

    var proxy_in:InputStream = null

    // handler status codes etc.
    var code = -1
    if (http != null) {
      proxy_in = http.getErrorStream()

      try {
        code = http.getResponseCode()
      } catch {
        case e:SSLHandshakeException => {
          throw new RuntimeException("Couldn't establish SSL handshake.  Try using trustAllSSLCertificates.\n" + e.getLocalizedMessage(), e)
        }
      }
      response.setStatus(code)
      //response.setReason(http.getResponseMessage())

      val contentType = http.getContentType()
      if (_logger.isDebugEnabled()) {
        _logger.debug("Content-Type is: " + contentType)
      }
    }

    if (proxy_in == null) {
      try {
        proxy_in = connection.getInputStream()
      }
      catch {
        case e:Exception => {
          //                LogSupport.ignore(log, e)
          proxy_in = http.getErrorStream()
        }
      }
    }

    // clear response defaults.
    response.setHeader(HttpHeaders.DATE, null)
    response.setHeader(HttpHeaders.SERVER, null)

    // set response headers
    var h = 0
    var hdr = connection.getHeaderFieldKey(h)
    var v = connection.getHeaderField(h)
    while (hdr != null || v != null) {
      if (hdr != null && v != null && !_DontProxyHeaders.contains(hdr) && (_chained || !_ProxyAuthHeaders.contains(hdr)))
        response.setHeader(hdr, v)
      h += 1
      hdr = connection.getHeaderFieldKey(h)
      v = connection.getHeaderField(h)
    }
    if (!_anonymous)
      response.setHeader("Via", "1.1 (Sauce Connect)")

    response.setHeader(HttpHeaders.ETAG, null) // possible cksum?  Stop caching...
    response.setHeader(HttpHeaders.LAST_MODIFIED, null) // Stop caching...

    // Handled
    var bytesCopied:Long = -1
    request.setHandled(true)
    if (proxy_in != null) {
      bytesCopied = ModifiedIO.copy(proxy_in, response.getOutputStream())
    }
    return bytesCopied
  }

  protected def customizeConnection(request:Request, connection:URLConnection) = {}


  protected def getSslRelayOrCreateNew(uri:HttpURI, addrPort:Int, server:Server) : SslRelay = {
    var connector:SslRelay = null
    _sslMap.synchronized {
      val host = uri.getHost()
      connector = _sslMap.get(host)
      if (connector == null) {
        // we do this because the URI above doesn't actually have the host broken up (it returns null on getHost())

        connector = new SslRelay(addrPort)

        if (useCyberVillains) {
          wireUpSslWithCyberVilliansCA(host, connector)
        } else {
          wireUpSslWithRemoteService(host, connector)
        }

        connector.setPassword("password")
        connector.setKeyPassword("password")
        server.addConnector(connector)

        shutdownLock.synchronized {
          try {
            if (server.isStarted()) {
              connector.start()
            } else {
              throw new RuntimeException("Can't start SslRelay: server is not started (perhaps it was just shut down?)")
            }
          }
          catch {
            case e:Exception => {
              e.printStackTrace()
              throw new IOException("test")
            }
          }
        }
        _sslMap.put(host, connector)
      }
    }
    return connector
  }

  protected def wireUpSslWithRemoteService(host:String, listener:SslRelay) = {
    // grab a keystore that has been signed by a CA cert that has already been imported in to the browser
    // note: this logic assumes the tester is using *custom and has imported the CA cert in to IE/Firefox/etc
    // the CA cert can be found at http://dangerous-certificate-authority.openqa.org
    val keystore = File.createTempFile("selenium-rc-" + host, "keystore")
    val urlString = "http://dangerous-certificate-authority.openqa.org/genkey.jsp?padding=" + _sslMap.size() + "&domain=" + host

    val url = new URL(urlString)
    val conn = url.openConnection()
    conn.connect()
    val is = conn.getInputStream()
    val buffer = new Array[Byte](1024)
    var length = 0
    val fos = new FileOutputStream(keystore)
    do {
      length = is.read(buffer)
      if (length != -1) fos.write(buffer, 0, length)
    } while (length != -1)
    fos.close()
    is.close()

    listener.setKeystore(keystore.getAbsolutePath())
    //listener.setKeystore("c:\\" + (_sslMap.size() + 1) + ".keystore")
    listener.setNukeDirOrFile(keystore)
  }

  protected def wireUpSslWithCyberVilliansCA(host:String, listener:SslRelay) = {
    try {
      val root = File.createTempFile("seleniumSslSupport", host)
      root.delete()
      root.mkdirs()

      ResourceExtractor.extractResourcePath(getClass(), "/sslSupport", root)

      val mgr = new KeyStoreManager(root)
      mgr.getCertificateByHostname(host)
      mgr.getKeyStore().deleteEntry(KeyStoreManager._caPrivKeyAlias)
      mgr.persist()

      listener.setKeystore(new File(root, "cybervillainsCA.jks").getAbsolutePath())
      listener.setNukeDirOrFile(root)
    } catch {
      case e:Exception => throw new RuntimeException(e)
    }
  }

  class SslRelay(addr:Int) extends SslSocketConnector {
    var nukeDirOrFile:File = null

    def setNukeDirOrFile(nukeDirOrFile:File) = {
      this.nukeDirOrFile = nukeDirOrFile
    }

    override def customize(endpoint:EndPoint, request:Request) = {
      super.customize(endpoint, request)
      val uri = request.getUri()

      // Convert the URI to a proxy URL
      //
      // NOTE: Don't just add a host + port to the request URI, since this causes the URI to
      // get "dirty" and be rewritten, potentially breaking the proxy slightly. Instead,
      // create a brand new URI that includes the protocol, the host, and the port, but leaves
      // intact the path + query string "as is" so that it does not get rewritten.
      try {
        val uriField = classOf[Request].getDeclaredField("_uri")
        uriField.setAccessible(true)
        //uriField.set(request, new HttpURI("https://" + addr.getHost() + ":" + addr + uri.toString()))
      } catch {
        case e:Exception => e.printStackTrace()
      }
    }

    override def doStop() = {
      super.doStop()

      if (nukeDirOrFile != null) {
        if (nukeDirOrFile.isDirectory()) {
          FileHandler.delete(nukeDirOrFile)
        } else {
          nukeDirOrFile.delete()
        }
      }
    }
  }
}
