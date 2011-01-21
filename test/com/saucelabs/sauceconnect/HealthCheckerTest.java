package com.saucelabs.sauceconnect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.BeforeClass;
import org.junit.Test;

public class HealthCheckerTest {
    
    @BeforeClass
    public static void makeSureInterpreterWorks(){
      assertNotNull(SauceConnect.getInterpreter());
    }

    @Test
    public void testCreationWithNoFailMsg(){
        HealthChecker c = new HealthChecker("127.0.0.1", new String[]{"80"});
        assertNotNull(c);
    }
    
    @Test
    public void testCreationWithFailMsg(){
        HealthChecker c = new HealthChecker("127.0.0.1", new String[]{"80"}, "This is a failure message");
        assertNotNull(c);
    }
    
    @Test
    public void testCheckGoodPort() throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = listener.getLocalPort();
        HealthChecker c = new HealthChecker("127.0.0.1", new String[]{String.valueOf(port)});
        try{
            assertTrue(c.check());
        } finally {
            listener.close();
        }
    }
    
    @Test
    public void testCheckBadPort() throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = listener.getLocalPort();
        listener.close();
        HealthChecker c = new HealthChecker("127.0.0.1", new String[]{String.valueOf(port)});
        assertFalse(c.check());
    }
}
