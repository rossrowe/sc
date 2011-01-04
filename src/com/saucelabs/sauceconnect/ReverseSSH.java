package com.saucelabs.sauceconnect;

import java.io.IOException;

import org.python.core.*;

import ch.ethz.ssh2.Connection;

public class ReverseSSH {
	public String host;
	public PyObject tunnel;
	public String[] ports;
	public String[] tunnel_ports;
	public boolean use_ssh_config;
	public boolean debug;

	public void run(String readyfile) throws InterruptedException{
		SauceConnect.log("Starting SSH connection...");
		String host = ((PyString)tunnel.__getattr__("host")).asString();
		String user = ((PyString)tunnel.__getattr__("user")).asString();
		String password = ((PyString)tunnel.__getattr__("password")).asString();
		Connection makiConnection = new Connection(host, 443);
		try {
			Thread.sleep(1000);
			makiConnection.connect();
			makiConnection.authenticateWithPassword(user, password);
			for(int index = 0; index < ports.length; index++){
				int remotePort = Integer.valueOf(tunnel_ports[index]);
				int localPort = Integer.valueOf(ports[index]);
				makiConnection.requestRemotePortForwarding("0.0.0.0", remotePort, this.host, localPort);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		SauceConnect.log("SSH Connected. You may start your tests.");
		for(;;){
			Thread.sleep(60000);
		}
	}

	public void stop(){ }
}
