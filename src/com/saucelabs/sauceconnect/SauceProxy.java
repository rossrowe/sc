package com.saucelabs.sauceconnect;

import org.mortbay.http.HttpContext;
import org.mortbay.http.SocketListener;
import org.mortbay.jetty.Server;

public class SauceProxy {

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
        final SocketListener socketListener;

        Server server = new Server();
        socketListener = new SocketListener();
        socketListener.setMaxIdleTimeMs(60000);
        socketListener.setMaxThreads(512);
        socketListener.setPort(4445);
        server.addListener(socketListener);

        HttpContext root;
        root = new HttpContext();
        root.setContextPath("/");
        ProxyHandler proxyHandler = new ProxyHandler(true, "", "", false, false);

        // see docs for the lock object for information on this and why it is IMPORTANT!
        root.addHandler(proxyHandler);
        server.addContext(root);
        server.start();
	}

}
