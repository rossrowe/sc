package com.saucelabs.sauceconnect.proxy;

import cybervillains.ca.KeyStoreManager;
import org.eclipse.jetty.http.*;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.SocketChannel;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Ross Rowe
 */
public class Jetty7ProxyHandler extends ConnectHandler {

    private final Logger _logger = Log.getLogger(getClass().getName());

    private boolean trustAllSSLCertificates = false;
    private boolean _anonymous = false;
    private transient boolean _chained = false;
    private boolean useCyberVillains = true;

    /**
     * This lock is very important to ensure that SeleniumServer and the underlying Jetty instance
     * shuts down properly. It ensures that ProxyHandler does not add an SslRelay to the Jetty server
     * dynamically (needed for SSL proxying) if the server has been shut down or is in the process
     * of getting shut down.
     */
    private final Object shutdownLock = new Object();

    /* ------------------------------------------------------------ */
    /**
     * Map of leg by leg headers (not end to end). Should be a set, but more efficient string map is
     * used instead.
     */
    protected StringMap _DontProxyHeaders = new StringMap();

    {
        Object o = new Object();
        _DontProxyHeaders.setIgnoreCase(true);
        _DontProxyHeaders.put(HttpHeaders.PROXY_CONNECTION, o);
        _DontProxyHeaders.put(HttpHeaders.CONNECTION, o);
        _DontProxyHeaders.put(HttpHeaders.KEEP_ALIVE, o);
        _DontProxyHeaders.put(HttpHeaders.TRANSFER_ENCODING, o);
        _DontProxyHeaders.put(HttpHeaders.TE, o);
        _DontProxyHeaders.put(HttpHeaders.TRAILER, o);
        _DontProxyHeaders.put(HttpHeaders.UPGRADE, o);
    }

    /* ------------------------------------------------------------ */
    /**
     * Map of leg by leg headers (not end to end). Should be a set, but more efficient string map is
     * used instead.
     */
    protected StringMap _ProxyAuthHeaders = new StringMap();

    {
        Object o = new Object();
        _ProxyAuthHeaders.put(HttpHeaders.PROXY_AUTHORIZATION, o);
        _ProxyAuthHeaders.put(HttpHeaders.PROXY_AUTHENTICATE, o);
    }

    /* ------------------------------------------------------------ */
    /**
     * Map of allows schemes to proxy Should be a set, but more efficient string map is used
     * instead.
     */
    protected StringMap _ProxySchemes = new StringMap();

    {
        Object o = new Object();
        _ProxySchemes.setIgnoreCase(true);
        _ProxySchemes.put(HttpSchemes.HTTP, o);
        _ProxySchemes.put(HttpSchemes.HTTPS, o);
        _ProxySchemes.put("ftp", o);
    }

    /* ------------------------------------------------------------ */
    private final Map<String, SslRelay> _sslMap = new LinkedHashMap<String, SslRelay>();

    public Jetty7ProxyHandler(boolean trustAllSSLCertificates) {
        super();
        this.trustAllSSLCertificates = trustAllSSLCertificates;
    }

    @Override
    public void handle(String target, Request baseRequest, javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response) throws ServletException, IOException {
        if (HttpMethods.CONNECT.equalsIgnoreCase(request.getMethod())) {
            _logger.debug("CONNECT request for {}", request.getRequestURI());
            handleConnect(baseRequest, request, response, request.getRequestURI());
            return;
        }
        String host = request.getRequestURI();
        try {

            // Has the requested resource been found?
//        	if ("True".equals(response.getAttribute("NotFound"))) {
//        		response.removeAttribute("NotFound");
//        		sendNotFound(response);
//        		return;
//        	}

            // Do we proxy this?
            if (!validateDestination(host)) {
                Log.info("ProxyHandler: Forbidden destination " + host);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                baseRequest.setHandled(true);
                return;
            }

            // is this URL a /selenium URL?
            if (isSeleniumUrl(host)) {
                baseRequest.setHandled(false);
                return;
            }

            proxyPlainTextRequest(baseRequest, request, response);
        }
        catch (Exception e) {
            _logger.debug("Could not proxy " + host, e);
            if (!response.isCommitted())
                response.sendError(400, "Could not proxy " + host + "\n" + e);
        }
    }

    protected long proxyPlainTextRequest(Request request, javax.servlet.http.HttpServletRequest httpRequest, javax.servlet.http.HttpServletResponse response) throws IOException {
        URL url = new URL(request.getRequestURL().toString());
        URLConnection connection = url.openConnection();
        connection.setAllowUserInteraction(false);

        // Set method
        HttpURLConnection http = null;
        if (connection instanceof HttpURLConnection) {
            http = (HttpURLConnection) connection;
            http.setRequestMethod(request.getMethod());
            http.setInstanceFollowRedirects(false);
            if (trustAllSSLCertificates && connection instanceof HttpsURLConnection) {
                TrustEverythingSSLTrustManager.trustAllSSLCertificates((HttpsURLConnection) connection);
            }
        }

        // check connection header
        String connectionHdr = request.getHeader(HttpHeaders.CONNECTION);
        if (connectionHdr != null && (connectionHdr.equalsIgnoreCase(HttpHeaders.KEEP_ALIVE) || connectionHdr.equalsIgnoreCase("close")))
            connectionHdr = null;

        // copy headers
        boolean xForwardedFor = false;
        boolean isGet = "GET".equals(request.getMethod());
        boolean hasContent = false;
        Enumeration enm = request.getHeaderNames();
        while (enm.hasMoreElements()) {
            // TODO could be better than this!
            String hdr = (String) enm.nextElement();

            if (_DontProxyHeaders.containsKey(hdr) || !_chained && _ProxyAuthHeaders.containsKey(hdr))
                continue;
            if (connectionHdr != null && connectionHdr.indexOf(hdr) >= 0)
                continue;

            if (!isGet && HttpHeaders.CONTENT_TYPE.equals(hdr))
                hasContent = true;

            Enumeration vals = request.getHeaders(hdr);
            while (vals.hasMoreElements()) {
                String val = (String) vals.nextElement();
                if (val != null) {
                    // don't proxy Referer headers if the referer is Selenium!
                    if ("Referer".equals(hdr) && (-1 != val.indexOf("/selenium-server/"))) {
                        continue;
                    }
                    if (!isGet && HttpHeaders.CONTENT_LENGTH.equals(hdr) && Integer.parseInt(val) > 0) {
                        hasContent = true;
                    }

                    connection.addRequestProperty(hdr, val);
                    xForwardedFor |= HttpHeaders.X_FORWARDED_FOR.equalsIgnoreCase(hdr);
                }
            }
        }

        // Proxy headers
        if (!_anonymous)
            connection.setRequestProperty("Via", "1.1 (Sauce Connect)");
        if (!xForwardedFor)
            connection.addRequestProperty(HttpHeaders.X_FORWARDED_FOR, request.getRemoteAddr());

        // a little bit of cache control
        String cache_control = request.getHeader(HttpHeaders.CACHE_CONTROL);
        if (cache_control != null && (cache_control.indexOf("no-cache") >= 0 || cache_control.indexOf("no-store") >= 0))
            connection.setUseCaches(false);

        // customize Connection
        customizeConnection(request, connection);

        try {
            connection.setDoInput(true);

            // do input thang!
            InputStream in = request.getInputStream();
            if (hasContent) {
                connection.setDoOutput(true);
                IO.copy(in, connection.getOutputStream());
            }

            // Connect
            connection.connect();
        }
        catch (Exception e) {
            //LogSupport.ignore(log, e);
        }

        InputStream proxy_in = null;

        // handler status codes etc.
        int code = -1;
        if (http != null) {
            proxy_in = http.getErrorStream();

            try {
                code = http.getResponseCode();
            } catch (SSLHandshakeException e) {
                throw new RuntimeException("Couldn't establish SSL handshake.  Try using trustAllSSLCertificates.\n" + e.getLocalizedMessage(), e);
            }
            response.setStatus(code);
            //response.setReason(http.getResponseMessage());

            String contentType = http.getContentType();
            if (_logger.isDebugEnabled()) {
                _logger.debug("Content-Type is: " + contentType);
            }
        }

        if (proxy_in == null) {
            try {
                proxy_in = connection.getInputStream();
            }
            catch (Exception e) {
//                LogSupport.ignore(log, e);
                proxy_in = http.getErrorStream();
            }
        }

        // clear response defaults.
        response.setHeader(HttpHeaders.DATE, null);
        response.setHeader(HttpHeaders.SERVER, null);

        // set response headers
        int h = 0;
        String hdr = connection.getHeaderFieldKey(h);
        String val = connection.getHeaderField(h);
        while (hdr != null || val != null) {
            if (hdr != null && val != null && !_DontProxyHeaders.containsKey(hdr) && (_chained || !_ProxyAuthHeaders.containsKey(hdr)))
                response.setHeader(hdr, val);
            h++;
            hdr = connection.getHeaderFieldKey(h);
            val = connection.getHeaderField(h);
        }
        if (!_anonymous)
            response.setHeader("Via", "1.1 (Sauce Connect)");

        response.setHeader(HttpHeaders.ETAG, null); // possible cksum?  Stop caching...
        response.setHeader(HttpHeaders.LAST_MODIFIED, null); // Stop caching...

        // Handled
        long bytesCopied = -1;
        request.setHandled(true);
        if (proxy_in != null) {
            bytesCopied = ModifiedIO.copy(proxy_in, response.getOutputStream());
        }
        return bytesCopied;
    }

    private void customizeConnection(Request request, URLConnection connection) {
    }

    private boolean isSeleniumUrl(String url) {
        int slashSlash = url.indexOf("//");
        if (slashSlash == -1) {
            return false;
        }

        int nextSlash = url.indexOf("/", slashSlash + 2);
        if (nextSlash == -1) {
            return false;
        }

        int seleniumServer = url.indexOf("/selenium-server/");
        if (seleniumServer == -1) {
            return false;
        }

        // we do this complex checking because sometimes some sites/pages (such as ominture ads) embed the referrer URL,
        // which will include selenium stuff, in to the query parameter, which would fake out a simple String.contains()
        // call. This method is more robust and will catch this stuff.
        return seleniumServer == nextSlash;
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
    protected void handleConnect(Request baseRequest, HttpServletRequest request, HttpServletResponse response, String serverAddress) throws ServletException, IOException {
        boolean proceed = handleAuthentication(request, response, serverAddress);
        if (!proceed)
            return;

        String host = serverAddress;
        int port = 80;
        // When logging, we'll attempt to send messages to hosts that don't exist
        if (host.endsWith(".selenium.doesnotexist:443")) {
            // so we have to do set the host to be localhost (you can't new up an IAP with a non-existent hostname)
            port = 443;
            host = "localhost";
        } else {
            int colon = serverAddress.indexOf(':');
            if (colon > 0) {
                host = serverAddress.substring(0, colon);
                port = Integer.parseInt(serverAddress.substring(colon + 1));
            }
        }

        if (!validateDestination(baseRequest, host)) {
            Log.info("ProxyHandler: Forbidden destination " + host);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            baseRequest.setHandled(true);
            return;
        }

        // Transfer unread data from old connection to new connection
        // We need to copy the data to avoid races:
        // 1. when this unread data is written and the server replies before the clientToProxy
        // connection is installed (it is only installed after returning from this method)
        // 2. when the client sends data before this unread data has been written.
        HttpConnection httpConnection = HttpConnection.getCurrentConnection();

        Server server = httpConnection.getServer();
        SslRelay listener = getSslRelayOrCreateNew(baseRequest.getUri(), port, server);

        SocketChannel channel = connectToServer(request, host, port);

        //int listernerPort = listener.getPort();


        Buffer headerBuffer = ((HttpParser) httpConnection.getParser()).getHeaderBuffer();
        Buffer bodyBuffer = ((HttpParser) httpConnection.getParser()).getBodyBuffer();
        int length = headerBuffer == null ? 0 : headerBuffer.length();
        length += bodyBuffer == null ? 0 : bodyBuffer.length();
        IndirectNIOBuffer buffer = null;
        if (length > 0) {
            buffer = new IndirectNIOBuffer(length);
            if (headerBuffer != null) {
                buffer.put(headerBuffer);
                headerBuffer.clear();
            }
            if (bodyBuffer != null) {
                buffer.put(bodyBuffer);
                bodyBuffer.clear();
            }
        }

        ConcurrentMap<String, Object> context = new ConcurrentHashMap<String, Object>();
        prepareContext(request, context);

        ClientToProxyConnection clientToProxy = prepareConnections(context, channel, buffer);

        // CONNECT expects a 200 response
        response.setStatus(HttpServletResponse.SC_OK);

        // Prevent close
        baseRequest.getConnection().getGenerator().setPersistent(true);

        // Close to force last flush it so that the client receives it
        response.getOutputStream().close();

        upgradeConnection(request, response, clientToProxy);
    }

    public boolean validateDestination(Request request, String host) {
        return _ProxySchemes.containsKey(request.getScheme()) && super.validateDestination(host);
    }

    private void upgradeConnection(HttpServletRequest request, HttpServletResponse response, Connection connection) throws IOException {
        // Set the new connection as request attribute and change the status to 101
        // so that Jetty understands that it has to upgrade the connection
        request.setAttribute("org.eclipse.jetty.io.Connection", connection);
        response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        _logger.debug("Upgraded connection to {}", connection);
    }

    private ClientToProxyConnection prepareConnections(ConcurrentMap<String, Object> context, SocketChannel channel, Buffer buffer) {
        HttpConnection httpConnection = HttpConnection.getCurrentConnection();
        ProxyToServerConnection proxyToServer = newProxyToServerConnection(context, buffer);
        ClientToProxyConnection clientToProxy = newClientToProxyConnection(context, channel, httpConnection.getEndPoint(), httpConnection.getTimeStamp());
        clientToProxy.setConnection(proxyToServer);
        proxyToServer.setConnection(clientToProxy);
        return clientToProxy;
    }

    private SocketChannel connectToServer(HttpServletRequest request, String host, int port) throws IOException {
        SocketChannel channel = connect(request, host, port);
        channel.configureBlocking(false);
        return channel;
    }

    protected SslRelay getSslRelayOrCreateNew(HttpURI uri, int addrPort, Server server) throws IOException {
        SslRelay connector;
        synchronized (_sslMap) {
            String host = uri.getHost();
            connector = _sslMap.get(host);
            if (connector == null) {
                // we do this because the URI above doesn't actually have the host broken up (it returns null on getHost())

                connector = new SslRelay(addrPort);

                if (useCyberVillains) {
                    wireUpSslWithCyberVilliansCA(host, connector);
                } else {
                    wireUpSslWithRemoteService(host, connector);
                }

                connector.setPassword("password");
                connector.setKeyPassword("password");
                server.addConnector(connector);

                synchronized (shutdownLock) {
                    try {
                        if (server.isStarted()) {
                            connector.start();
                        } else {
                            throw new RuntimeException("Can't start SslRelay: server is not started (perhaps it was just shut down?)");
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        throw new IOException(e);
                    }
                }
                _sslMap.put(host, connector);
            }
        }
        return connector;
    }

    protected void wireUpSslWithRemoteService(String host, SslRelay listener) throws IOException {
        // grab a keystore that has been signed by a CA cert that has already been imported in to the browser
        // note: this logic assumes the tester is using *custom and has imported the CA cert in to IE/Firefox/etc
        // the CA cert can be found at http://dangerous-certificate-authority.openqa.org
        File keystore = File.createTempFile("selenium-rc-" + host, "keystore");
        String urlString = "http://dangerous-certificate-authority.openqa.org/genkey.jsp?padding=" + _sslMap.size() + "&domain=" + host;

        URL url = new URL(urlString);
        URLConnection conn = url.openConnection();
        conn.connect();
        InputStream is = conn.getInputStream();
        byte[] buffer = new byte[1024];
        int length;
        FileOutputStream fos = new FileOutputStream(keystore);
        while ((length = is.read(buffer)) != -1) {
            fos.write(buffer, 0, length);
        }
        fos.close();
        is.close();

        listener.setKeystore(keystore.getAbsolutePath());
        //listener.setKeystore("c:\\" + (_sslMap.size() + 1) + ".keystore");
        listener.setNukeDirOrFile(keystore);
    }

    protected void wireUpSslWithCyberVilliansCA(String host, SslRelay listener) {
        try {
            File root = File.createTempFile("seleniumSslSupport", host);
            root.delete();
            root.mkdirs();

            ResourceExtractor.extractResourcePath(getClass(), "/sslSupport", root);

            KeyStoreManager mgr = new KeyStoreManager(root);
            mgr.getCertificateByHostname(host);
            mgr.getKeyStore().deleteEntry(KeyStoreManager._caPrivKeyAlias);
            mgr.persist();

            listener.setKeystore(new File(root, "cybervillainsCA.jks").getAbsolutePath());
            listener.setNukeDirOrFile(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class SslRelay extends SslSocketConnector {
        int _addr;
        File nukeDirOrFile;

        SslRelay(int addr) {
            _addr = addr;
        }

        public void setNukeDirOrFile(File nukeDirOrFile) {
            this.nukeDirOrFile = nukeDirOrFile;
        }

        @Override
        public void customize(EndPoint endpoint, Request request) throws IOException {
            super.customize(endpoint, request);
            HttpURI uri = request.getUri();

            // Convert the URI to a proxy URL
            //
            // NOTE: Don't just add a host + port to the request URI, since this causes the URI to
            // get "dirty" and be rewritten, potentially breaking the proxy slightly. Instead,
            // create a brand new URI that includes the protocol, the host, and the port, but leaves
            // intact the path + query string "as is" so that it does not get rewritten.
            try {
                Field uriField = Request.class.getDeclaredField("_uri");
                uriField.setAccessible(true);
                //uriField.set(request, new HttpURI("https://" + _addr.getHost() + ":" + _addr + uri.toString()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void doStop() throws Exception {
            super.doStop();

            if (nukeDirOrFile != null) {
                if (nukeDirOrFile.isDirectory()) {
                    FileHandler.delete(nukeDirOrFile);
                } else {
                    nukeDirOrFile.delete();
                }
            }
        }
    }
}
