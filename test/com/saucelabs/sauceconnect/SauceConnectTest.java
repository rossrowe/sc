package com.saucelabs.sauceconnect;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;

import org.json.simple.parser.ParseException;
import org.junit.Test;

public class SauceConnectTest {
    @Test
    public void testGetDownloadURL() throws IOException, ParseException {
        assertNull(SauceConnect.getDownloadURL(500000));
        assertNotNull(SauceConnect.getDownloadURL(-1));
    }
}
