package com.saucelabs.sauceconnect;

import com.saucelabs.selenium.client.factory.SeleniumFactory;
import com.thoughtworks.selenium.Selenium;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * Simple Sauce test that uses the selenium-client-factory logic to verify the sauce-connect logic works.
 *
 * @author Ross Rowe
 */
public class SeleniumTest extends HttpServlet {

    protected static final String DEFAULT_SAUCE_DRIVER = "sauce-ondemand:?max-duration=30&os=windows 2008&browser=firefox&browser-version=4.";
    private int code;

    @Test
    public void fullRun() throws Exception {

        this.code = new Random().nextInt();

        // start the Jetty locally and have it respond our secret code.
        Server server = new Server(8080);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(this), "/*");
        server.start();

        System.out.println("Started Jetty at 8080");

        try {
            // start a tunnel
            System.out.println("Starting a tunnel");
            final String username = "REPLACE_ME";
            final String apiKey = "REPLACE_ME";

            Authenticator.setDefault(
                    new Authenticator() {
                        public PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(
                                    username, apiKey.toCharArray());
                        }
                    }
            );

            final SauceConnect sauceConnect = new SauceConnect(new String[]{username, apiKey, "-d", "--proxy-host", "testing.org"});
            Thread sauceConnectThread = new Thread("SauceConnectThread") {
                @Override
                public void run() {
                    sauceConnect.openConnection();
                }
            };
            sauceConnectThread.start();

            try {
                Thread.sleep(1000 * 60 * 2); //2 minutes
            } catch (InterruptedException e) {
                //continue;
            }
            System.out.println("tunnel established");
            String driver = System.getenv("SELENIUM_DRIVER");
            if (driver == null || driver.equals("")) {
                System.setProperty("SELENIUM_DRIVER", DEFAULT_SAUCE_DRIVER);
            }

            String originalUrl = System.getenv("SELENIUM_STARTING_URL");
            System.setProperty("SELENIUM_STARTING_URL", "http://testing.org:8080/");
            Selenium selenium = SeleniumFactory.create();
            try {
                selenium.start();
                selenium.open("/");
                // if the server really hit our Jetty, we should see the same title that includes the secret code.
                assertEquals("test" + code, selenium.getTitle());
                selenium.stop();
            } finally {
                sauceConnect.closeTunnel();
                sauceConnect.removeHandler();
                selenium.stop();
                if (originalUrl != null && !originalUrl.equals("")) {
                    System.setProperty("SELENIUM_STARTING_URL", originalUrl);
                }
            }
        } finally {
            server.stop();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html");
        resp.getWriter().println("<html><head><title>test" + code + "</title></head><body>it works</body></html>");
    }
}
