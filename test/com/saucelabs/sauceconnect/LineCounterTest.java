package com.saucelabs.sauceconnect;

import org.junit.Test;

import static org.junit.Assert.*;

public class LineCounterTest {
    @Test
    public void testCountLines(){
        String s = "foo\nbar\nbaz\n";
        assertEquals(3, LineCounter.countLines(s));
        assertEquals(0, LineCounter.countLines("foo"));
    }
}
