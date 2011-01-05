package com.saucelabs.sauceconnect;

import java.io.IOException;

import org.python.core.*;

import ch.ethz.ssh2.Connection;

public class ReverseSSH {
	/* named arguments to constructor cause fields to be set */
	public String host;
	public PyObject tunnel;
	public String[] ports;
	public String[] tunnel_ports;
	public boolean use_ssh_config;
	public boolean debug;

	public void run(String readyfile) throws InterruptedException, IOException {
		SauceConnect.log("Starting SSH connection...");
		String host = ((PyString)tunnel.__getattr__("host")).asString();
		String user = ((PyString)tunnel.__getattr__("user")).asString();
		String password = ((PyString)tunnel.__getattr__("password")).asString();
		Connection makiConnection = new Connection(host, 443);
		makiConnection.connect();
		makiConnection.authenticateWithPassword(user, password);
		for(int index = 0; index < ports.length; index++){
			int remotePort = Integer.valueOf(tunnel_ports[index]);
			int localPort = Integer.valueOf(ports[index]);
			makiConnection.requestRemotePortForwarding("0.0.0.0", remotePort, this.host, localPort);
		}
		SauceConnect.log("SSH Connected. You may start your tests.");
		HealthChecker forwarded_health = new HealthChecker(this.host, ports);
		HealthChecker tunnel_health = new HealthChecker(host, new String[]{"443"}, 
				"!! Your tests may fail because your network cannot get to the " +
				"tunnel host ("+host+":443).");
		int health_check_interval = ((PyInteger)SauceConnect.interpreter.eval("sauce_connect.HEALTH_CHECK_INTERVAL")).asInt() * 1000;
		for(;;){
			forwarded_health.check();
			tunnel_health.check();
			Thread.sleep(health_check_interval);
		}
	}
	
	public void stop(){ }
}
