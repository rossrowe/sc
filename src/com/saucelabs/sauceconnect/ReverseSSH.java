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
	
	public void run(String readyfile){
		System.err.println("Starting SSH process...");
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
				System.err.println("Forwarded " + remotePort + " to " + this.host + ":" + localPort);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.err.println("Connected!");
		try {
			Thread.sleep(5*60000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
