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

package com.saucelabs.sauceconnect;

//import com.saucelabs.sauceconnect.proxy.ProxyHandler;
//import org.mortbay.http.HttpContext;
//import org.mortbay.http.SocketListener;
//import org.mortbay.jetty.Server;

import com.saucelabs.sauceconnect.proxy.Jetty7ProxyHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;


public class SauceProxy {
    private Server server;

    public SauceProxy() {
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
//        SocketConnector connector = new SocketConnector();
        connector.setPort(0);
        connector.setMaxIdleTime(20000);
        connector.setThreadPool(new QueuedThreadPool(256));

        server.addConnector(connector);


        ConnectHandler handler = new Jetty7ProxyHandler(true);
        server.setHandler(handler);

//        SocketListener socketListener = new SocketListener();
//        socketListener.setMaxIdleTimeMs(20000);
//        socketListener.setMaxThreads(256);
//        socketListener.setPort(0);
//        server.addListener(socketListener);
//
//        HttpContext root;
//        root = new HttpContext();
//        root.setContextPath("/");
//        ProxyHandler proxyHandler = new ProxyHandler(true, false);
//        root.addHandler(proxyHandler);
//        server.addContext(root);
    }

    public int getPort() {
//        return this.server.getListeners()[0].getPort();
        return this.server.getConnectors()[0].getLocalPort();
    }

    public void start() throws Exception {
        this.server.start();
//        this.server.join();
    }

    public static void main(String[] args) throws Exception {
        SauceProxy proxy = new SauceProxy();
        proxy.start();
    }
}
