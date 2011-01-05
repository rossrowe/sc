package com.saucelabs.sauceconnect;

import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.python.core.PyObject;
import org.python.core.PyString;

public class PythonLogHandler extends Handler {
    private PyObject logger = null;
    
    @Override
    public void close() throws SecurityException { }

    @Override
    public void flush() { }

    @Override
    public void publish(LogRecord arg0) {
        if(logger == null){
            logger = SauceConnect.getInterpreter().eval("sauce_connect.logger");
        }
        logger.invoke("info", new PyString(arg0.getMessage()));
    }
    
    public static void install(){
        Logger rootLogger = LogManager.getLogManager().getLogger("");
        rootLogger.removeHandler(rootLogger.getHandlers()[0]);
        rootLogger.addHandler(new PythonLogHandler());
    }
}
