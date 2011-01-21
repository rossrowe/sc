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
