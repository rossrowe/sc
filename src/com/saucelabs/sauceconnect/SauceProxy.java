package com.saucelabs.sauceconnect;

import org.mortbay.http.HttpContext;
import org.mortbay.http.SocketListener;
import org.mortbay.jetty.Server;

public class SauceProxy {
	private Server server;
	
	public SauceProxy(){
        server = new Server();
        SocketListener socketListener = new SocketListener();
        socketListener.setMaxIdleTimeMs(60000);
        socketListener.setMaxThreads(512);
        socketListener.setPort(4445);
        server.addListener(socketListener);

        HttpContext root;
        root = new HttpContext();
        root.setContextPath("/");
        ProxyHandler proxyHandler = new ProxyHandler(true, "", "", false, false);

        root.addHandler(proxyHandler);
        server.addContext(root);
	}
	
	public void start() throws Exception{
		this.server.start();
	}
	
	public static void main(String[] args) throws Exception {
		SauceProxy proxy = new SauceProxy();
        proxy.start();
	}

}
