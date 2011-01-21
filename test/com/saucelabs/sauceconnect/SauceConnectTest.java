package com.saucelabs.sauceconnect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.json.simple.parser.ParseException;
import org.junit.Test;

public class SauceConnectTest {
    @Test
    public void testIsReleaseCurrent() throws IOException, ParseException {
        assertTrue(SauceConnect.isReleaseCurrent(500000));
        assertFalse(SauceConnect.isReleaseCurrent(-1));
    }
}
