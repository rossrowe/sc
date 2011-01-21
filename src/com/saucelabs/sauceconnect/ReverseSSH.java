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

import java.io.File;
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
    public int ssh_port;

    private Connection tunnelConnection;
    private File readyfile = null;

    private String getTunnelSetting(String name) {
        return ((PyString) this.tunnel.__getattr__(name)).asString();
    }

    public void run(String readyfile) throws InterruptedException, IOException {
        if (readyfile != null) {
            this.readyfile = new File(readyfile);
            this.readyfile.delete();
            this.readyfile.deleteOnExit();
        }
        log.info("Starting SSH connection...");
        String host = getTunnelSetting("host");
        String user = getTunnelSetting("user");
        String password = getTunnelSetting("password");
        tunnelConnection = new Connection(host, this.ssh_port);
        tunnelConnection.connect();
        tunnelConnection.authenticateWithPassword(user, password);
        for (int index = 0; index < ports.length; index++) {
            int remotePort = Integer.valueOf(tunnel_ports[index]);
            int localPort = Integer.valueOf(ports[index]);
            tunnelConnection.requestRemotePortForwarding("0.0.0.0", remotePort, this.host,
                    localPort);
        }
        log.info("SSH Connected. You may start your tests.");
        if (this.readyfile != null) {
            this.readyfile.createNewFile();
        }
        HealthChecker forwarded_health = new HealthChecker(this.host, ports);
        HealthChecker tunnel_health = new HealthChecker(host,
                new String[] { String.valueOf(this.ssh_port) },
                "!! Your tests may fail because your network cannot get to the " + "tunnel host ("
                        + host + ":" + this.ssh_port + ").");
        int health_check_interval = SauceConnect.getHealthCheckInterval();
        for (;;) {
            forwarded_health.check();
            tunnel_health.check();
            Thread.sleep(health_check_interval);
        }
    }

    public void stop() {
        if (this.readyfile != null) {
            this.readyfile.delete();
        }
        tunnelConnection.close();
    }
}
