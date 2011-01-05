package com.saucelabs.sauceconnect;

import org.python.core.*;

public class HealthChecker {
	private PyObject pyHealthChecker;
	
	public HealthChecker(String host, String[] ports){
		PyType healthCheckerClass = (PyType)SauceConnect.interpreter.eval("sauce_connect.HealthChecker");
		PyString[] pythonArgs = new PyString[ports.length];
		for(int index = 0; index < ports.length; index++){
			pythonArgs[index] = new PyString(ports[index]);
		}
		this.pyHealthChecker = healthCheckerClass.__call__(new PyString(host), new PyList(pythonArgs));
	}
	
	public HealthChecker(String host, String[] ports, String failMsg){
		PyType healthCheckerClass = (PyType)SauceConnect.interpreter.eval("sauce_connect.HealthChecker");
		PyString[] pythonArgs = new PyString[ports.length];
		for(int index = 0; index < ports.length; index++){
			pythonArgs[index] = new PyString(ports[index]);
		}
		this.pyHealthChecker = healthCheckerClass.__call__(new PyString(host), new PyList(pythonArgs), new PyString(failMsg));
	}
	
	public void check(){
		try{
			this.pyHealthChecker.invoke("check");
		} catch (PyException e){
			e.printStackTrace();
		}
	}
}
