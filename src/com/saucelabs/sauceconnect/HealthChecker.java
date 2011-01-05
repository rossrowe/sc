package com.saucelabs.sauceconnect;

import org.python.core.*;

public class HealthChecker {
    private PyObject pyHealthChecker;
    
    private PyType getHealthCheckerClass(){
        return (PyType) SauceConnect.getInterpreter().eval("sauce_connect.HealthChecker");
    }
    
    private PyList stringArrayToPyList(String[] strings){
        PyString[] pythonStrings = new PyString[strings.length];
        for (int index = 0; index < strings.length; index++) {
            pythonStrings[index] = new PyString(strings[index]);
        }
        return new PyList(pythonStrings);
    }

    public HealthChecker(String host, String[] ports) {
        this.pyHealthChecker = getHealthCheckerClass().__call__(new PyString(host),
                stringArrayToPyList(ports));
    }

    public HealthChecker(String host, String[] ports, String failMsg) {
        this.pyHealthChecker = getHealthCheckerClass().__call__(new PyString(host),
                stringArrayToPyList(ports), new PyString(failMsg));
    }

    public boolean check() {
        PyObject result = this.pyHealthChecker.invoke("check");
        return ((PyBoolean)result).getValue() == 1;
    }
}
