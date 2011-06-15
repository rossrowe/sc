package com.saucelabs.sauceconnect;

import org.apache.commons.httpclient.ConnectMethod;
import org.apache.commons.httpclient.ProxyClient;

import java.net.Socket;

/**
 * @author Ross Rowe
 */
public class ConnectExample {

    public static void main(String args[]) {

        ProxyClient client = new ProxyClient();
        client.getParams().setParameter("http.useragent", "Proxy Test Client");

        client.getHostConfiguration().setHost("www.testing.org");
        client.getHostConfiguration().setProxy("localhost", 62942);

        Socket socket = null;

        try {
            ProxyClient.ConnectResponse response = client.connect();
            socket = response.getSocket();
            if (socket == null) {
                ConnectMethod method = response.getConnectMethod();
                System.err.println("Socket not created: " + method.getStatusLine());
            }
            // do something
        } catch (Exception e) {
            System.err.println(e);
        } finally {
            if (socket != null)
                try {
                    socket.close();
                } catch (Exception fe) {
                }
        }

    }
}
