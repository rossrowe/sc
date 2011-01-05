package com.saucelabs.sauceconnect;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.mortbay.log.LogFactory;
import org.python.core.*;

import ch.ethz.ssh2.Connection;

public class ReverseSSH {
    private static Log log = LogFactory.getLog(ReverseSSH.class);

    /* named arguments to constructor cause fields to be set */
    public String host;
    public PyObject tunnel;
    public String[] ports;
    public String[] tunnel_ports;
    public boolean use_ssh_config;
    public boolean debug;
    
    private Connection tunnelConnection;
    
    private String getTunnelSetting(String name){
        return ((PyString) this.tunnel.__getattr__(name)).asString();
    }

    public void run(String readyfile) throws InterruptedException, IOException {
        log.info("Starting SSH connection...");
        String host = getTunnelSetting("host");
        String user = getTunnelSetting("user");
        String password = getTunnelSetting("password");
        tunnelConnection = new Connection(host, 443);
        tunnelConnection.connect();
        tunnelConnection.authenticateWithPassword(user, password);
        for (int index = 0; index < ports.length; index++) {
            int remotePort = Integer.valueOf(tunnel_ports[index]);
            int localPort = Integer.valueOf(ports[index]);
            tunnelConnection.requestRemotePortForwarding("0.0.0.0", remotePort, this.host, localPort);
        }
        log.info("SSH Connected. You may start your tests.");
        HealthChecker forwarded_health = new HealthChecker(this.host, ports);
        HealthChecker tunnel_health = new HealthChecker(host, new String[] { "443" },
                "!! Your tests may fail because your network cannot get to the " + "tunnel host ("
                        + host + ":443).");
        int health_check_interval = SauceConnect.getHealthCheckInterval();
        for (;;) {
            forwarded_health.check();
            tunnel_health.check();
            Thread.sleep(health_check_interval);
        }
    }

    public void stop() {
        tunnelConnection.close();
    }
}
