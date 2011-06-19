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

import javax.swing.JTextPane;

public class TextPanelLogger {
    private JTextPane target;
    private StringBuilder buffer = new StringBuilder();
    
    public TextPanelLogger(JTextPane target){
        this.target = target;
    }
    public void write(String s){
        buffer.append(s);
    }
    public void flush(){
        target.setText(buffer.toString());
        target.setCaretPosition(buffer.length());
    }
}