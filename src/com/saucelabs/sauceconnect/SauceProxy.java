package com.saucelabs.sauceconnect;

import org.mortbay.http.HttpContext;
import org.mortbay.http.SocketListener;
import org.mortbay.jetty.Server;

import com.saucelabs.sauceconnect.proxy.ProxyHandler;

public class SauceProxy {
    private Server server;

    public SauceProxy() {
        server = new Server();
        SocketListener socketListener = new SocketListener();
        socketListener.setMaxIdleTimeMs(60000);
        socketListener.setMaxThreads(512);
        socketListener.setPort(0);
        server.addListener(socketListener);

        HttpContext root;
        root = new HttpContext();
        root.setContextPath("/");
        ProxyHandler proxyHandler = new ProxyHandler(true, false);
        root.addHandler(proxyHandler);
        server.addContext(root);
    }
    
    public int getPort() {
        return this.server.getListeners()[0].getPort();
    }

    public void start() throws Exception {
        this.server.start();
    }

    public static void main(String[] args) throws Exception {
        SauceProxy proxy = new SauceProxy();
        proxy.start();
    }
}
