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
