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
