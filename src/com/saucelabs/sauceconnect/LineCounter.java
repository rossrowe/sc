package com.saucelabs.sauceconnect;

public class LineCounter {
    public static int countLines(String text){
        int lines = 0;
        for(int index = 0; index != -1; index = text.indexOf("\n", index+1)){
            lines++;
        }
        return lines-1;
    }
}
