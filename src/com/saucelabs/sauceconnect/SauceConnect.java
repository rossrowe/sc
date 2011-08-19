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

import org.apache.commons.cli.*;
import org.bouncycastle.util.encoders.Base64;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.python.core.PyList;
import org.python.core.PyString;
import org.python.util.PythonInterpreter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;

/**
 * Third party libraries wishing to invoke the SauceConnect library should first invoke {@link #openConnection()},
 * which will create the SSH Tunnel.  The tunnel can be closed by first invoking the {@link #removeHandler()} and then
 * the {@link #closeTunnel()} methods.
 */
public class SauceConnect {
    private static final int BUILD = 12;
    private static final int RELEASE = 12;
    private static PythonInterpreter interpreter = null;
    private CommandLine commandLineArguments;
    private boolean liteMode;
    private PyList strippedArgs;
    private boolean standaloneMode = true;

    public SauceConnect(String[] args) {
        storeCommandLineArgs(args);
        this.strippedArgs = new PyList();
        for (String s : args) {
            if (s.equals("-l") || s.equals("--lite")) {
                liteMode = true;
            } else {
                strippedArgs.add(new PyString(s));
            }
        }
    }

    /**
     * Not thread safe.
     *
     * @return
     */
    public static PythonInterpreter getInterpreter() {
        if (interpreter == null) {
            interpreter = new PythonInterpreter();
            interpreter.exec("import sauce_connect");
        }
        return interpreter;
    }

    public synchronized static void setInterpreterIfNull(PythonInterpreter aInterpreter) {
       if (interpreter == null) {
             interpreter = aInterpreter;
       }
    }

    @SuppressWarnings("static-access")
    private void storeCommandLineArgs(String[] args) {
        Options options = new Options();
        Option readyfile = new Option("f", "readyfile", true, "Ready file that will be touched when tunnel is ready");
        readyfile.setArgName("FILE");
        options.addOption(readyfile);
        options.addOption("x", "rest-url", true, "Advanced feature: Connect to Sauce OnDemand at alternative URL." +
                " Use only if directed to by Sauce Labs support.");

        Option proxyHost = OptionBuilder.withArgName("HOSTNAME").
                hasArg().
                withDescription("Set 'proxy-host' field on jobs to the same " +
                        "value to use this Sauce Connect connection. " +
                        "Defaults to sauce-connect.proxy.").
                withLongOpt("proxy-host").
                create('p');
        options.addOption(proxyHost);

        options.addOption(OptionBuilder.withLongOpt("dont-update-proxy-host").
                withDescription("Don't update default proxy-host value for " +
                        "this account while tunnel is running.").create());

        options.addOption("h", "help", false, "Display this help text");
        options.addOption("v", "version", false, "Print the version and exit");
        options.addOption("b", "boost-mode", false, null);
        options.addOption("s", "ssh", false, null);
        options.addOption("d", "debug", false, "Enable verbose debugging");
        options.addOption("l", "lite", false, null);
        Option sePort = new Option("P", "se-port", true, null);
        sePort.setArgName("PORT");
        options.addOption(sePort);
        try {
            CommandLineParser parser = new PosixParser();
            CommandLine result = parser.parse(options, args);
            if (result.hasOption("help")) {
                HelpFormatter help = new HelpFormatter();
                help.printHelp("java -jar Sauce-Connect.jar USERNAME API_KEY [OPTIONS]", options);
                if (standaloneMode) {
                    System.exit(0);
                }
            }
            if (result.hasOption("version")) {
                System.out.println("Version: Sauce Connect 3.0-r" + RELEASE);
                if (standaloneMode) {
                    System.exit(0);
                }
            }
            if (result.getArgs().length == 0) {
                throw new ParseException("Missing required arguments USERNAME, API_KEY");
            }
            if (result.getArgs().length == 1) {
                throw new ParseException("Missing required argument API_KEY");
            }
            this.commandLineArguments = result;
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            System.err.println();
            HelpFormatter help = new HelpFormatter();
            help.printHelp("java -jar Sauce-Connect.jar USERNAME API_KEY [OPTIONS]", options);
            if (standaloneMode) {
                System.exit(1);
            }
        }
    }

    private PyList generateArgsForSauceConnect(String username, String apikey, String domain, CommandLine options) {
        ArrayList<PyString> args = new ArrayList<PyString>();
        args.add(new PyString("-u"));
        args.add(new PyString(username));
        args.add(new PyString("-k"));
        args.add(new PyString(apikey));
        args.add(new PyString("-d"));
        args.add(new PyString(domain));
        args.add(new PyString("-s"));
        args.add(new PyString("127.0.0.1"));
        args.add(new PyString("-p"));
        args.add(new PyString("80"));
        args.add(new PyString("--ssh-port"));
        args.add(new PyString("443"));
        args.add(new PyString("-b"));
        if (options != null) {
            if (options.hasOption('x')) {
                args.add(new PyString("--rest-url"));
                args.add(new PyString(options.getOptionValue('x')));
            }
            if (options.hasOption('f')) {
                args.add(new PyString("--readyfile"));
                args.add(new PyString(options.getOptionValue('f')));
            }
            if (options.hasOption('s')) {
                args.add(new PyString("--ssh"));
            }
            if (options.hasOption('P')) {
                args.add(new PyString("--se-port"));
                args.add(new PyString(options.getOptionValue('P')));
            }
        }

        return new PyList(args);
    }

    public static void main(String[] args) {
        new SauceConnect(args).openConnection();
    }

    public void openConnection() {
        versionCheck();
        setupArgumentList();
        setupTunnel();
        addShutdownHandler();
        startTunnel();
    }

    private void startTunnel() {
        try {
            if (commandLineArguments.hasOption("s")) {
                getInterpreter().exec("from com.saucelabs.sauceconnect import ReverseSSH as JavaReverseSSH");
            } else {
                getInterpreter().exec("from com.saucelabs.sauceconnect import KgpTunnel as JavaReverseSSH");
            }
            String startCommand;
            if (liteMode) {
                startCommand = "sauce_connect.run(options,"
                        + "setup_signal_handler=setup_java_signal_handler,"
                        + "reverse_ssh=JavaReverseSSH)";
            } else {
                startCommand = "sauce_connect.run(options,"
                        + "setup_signal_handler=setup_java_signal_handler,"
                        + "reverse_ssh=JavaReverseSSH,do_check_version=False,"
                        + "release=\"3.0-r" + RELEASE + "\","
                        + "build=\"" + BUILD + "\")";
            }
            interpreter.exec(startCommand);
        } catch (Exception e) {
            if (commandLineArguments.hasOption("d")) {
                e.printStackTrace();
            }
            //only invoke a System.exit() if we are in standalone mode
            if (standaloneMode) {
                System.exit(3);
            }
        }
    }

    private void addShutdownHandler() {
        //only add the shutdown handlers if we are in standalone mode
        if (standaloneMode) {
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    removeHandler();
                    if (liteMode) {
                        mainThread.interrupt();
                    }
                    closeTunnel();
                }
            });
        }
    }

    private void setupArgumentList() {
        if (liteMode) {
            getInterpreter().set("arglist", strippedArgs);
        } else {
            String domain = "sauce-connect.proxy";

            if (commandLineArguments.hasOption("proxy-host")) {
                domain = commandLineArguments.getOptionValue("proxy-host");
            }
            if (!commandLineArguments.hasOption("dont-update-proxy-host")) {
                int port = 33128;
                updateDefaultProxyHost(commandLineArguments.getArgs()[0], commandLineArguments.getArgs()[1], domain, port,
                        commandLineArguments.getOptionValue("rest-url", "http://saucelabs.com/rest"));
            }
            getInterpreter().set(
                    "arglist",
                    generateArgsForSauceConnect(commandLineArguments.getArgs()[0],
                            commandLineArguments.getArgs()[1], domain, commandLineArguments));
        }
    }

    private void startProxy() {
        try {
            SauceProxy proxy = new SauceProxy();
            proxy.start();
            interpreter.exec("options.ports = ['" + proxy.getPort() + "']");
        } catch (Exception e) {
            System.err.println("Error starting proxy: " + e.getMessage());
            //only invoke a System.exit() if we are in standalone mode
            if (standaloneMode) {
                System.exit(2);
            }
        }
    }

    private void setupTunnel() {
        setupLogging();
        if (!liteMode) {
            startProxy();
        }
        setupSignalHandler();
    }

    private void setupSignalHandler() {
        interpreter.exec("tunnel_for_java_to_kill = None");
        interpreter.exec("def setup_java_signal_handler(tunnel, options):\n"
                + "  global tunnel_for_java_to_kill\n" + "  tunnel_for_java_to_kill = tunnel\n");
    }

    private void setupLogging() {
        getInterpreter().exec("options = sauce_connect.get_options(arglist)");
        interpreter.exec("sauce_connect.setup_logging(options.logfile, options.quiet)");
        PythonLogHandler.install();
    }

    public void closeTunnel() {
        interpreter
                .exec("sauce_connect.peace_out(tunnel_for_java_to_kill, atexit=True)");
    }

    public void removeHandler() {
        if (!liteMode) {
            if (commandLineArguments != null && !commandLineArguments.hasOption("dont-update-proxy-host")) {
                updateDefaultProxyHost(commandLineArguments.getArgs()[0], commandLineArguments.getArgs()[1], null, 0,
                        commandLineArguments.getOptionValue("rest-url", "http://saucelabs.com/rest"));
            }
        }
        interpreter.exec("sauce_connect.logger.removeHandler(sauce_connect.fileout)");
    }

    @SuppressWarnings("unchecked")
    private void updateDefaultProxyHost(String username, String password, String proxyHost, int proxyPort, String restURL) {
        try {
            URL restEndpoint = new URL(restURL + "/v1/" + username + "/defaults");
            String auth = username + ":" + password;
            auth = "Basic " + new String(Base64.encode(auth.getBytes()));
            URLConnection connection = restEndpoint.openConnection();
            connection.setRequestProperty("Authorization", auth);
            InputStream data = connection.getInputStream();
            JSONParser parser = new JSONParser();
            JSONObject currentDefaults = (JSONObject) parser.parse(new InputStreamReader(data));
            if (proxyHost != null) {
                currentDefaults.put("proxy-host", proxyHost + ":" + proxyPort);
            } else {
                currentDefaults.remove("proxy-host");
            }

            HttpURLConnection postBack = (HttpURLConnection) restEndpoint.openConnection();
            postBack.setDoOutput(true);
            postBack.setRequestMethod("PUT");
            postBack.setRequestProperty("Authorization", auth);
            String newDefaults = currentDefaults.toJSONString();
            postBack.getOutputStream().write(newDefaults.getBytes());
            postBack.getInputStream().close();
        } catch (IOException e) {
            System.err.println("Error connecting to Sauce OnDemand REST API: ");
            e.printStackTrace();
            if (standaloneMode) {
                System.exit(5);
            }
        } catch (org.json.simple.parser.ParseException e) {
            System.err.println("Error reading from Sauce OnDemand REST API: ");
            e.printStackTrace();
            if (standaloneMode) {
                System.exit(5);
            }
        }
    }

    private void versionCheck() {
        try {
            if (liteMode) {
                return;
            }
            String downloadURL = getDownloadURL(RELEASE);
            if (downloadURL != null) {
                System.err.println("** This version of Sauce Connect is outdated.\n" +
                        "** Please update with " + downloadURL);
            }
        } catch (IOException e) {
            System.err.println("Error checking Sauce Connect version:");
            e.printStackTrace();
        } catch (org.json.simple.parser.ParseException e) {
            System.err.println("Error checking Sauce Connect version:");
            e.printStackTrace();
        }
    }

    /**
     * Get the download URL for the newer release of Sauce Connect if this version is outdated.
     *
     * @param localRelease
     * @return The download URL or null if the release is current.
     * @throws IOException
     * @throws org.json.simple.parser.ParseException
     *
     */
    public static String getDownloadURL(int localRelease) throws IOException, org.json.simple.parser.ParseException {
        URL versionsURL = new URL("http://saucelabs.com/versions.json");
        JSONObject versions = (JSONObject) new JSONParser().parse(new InputStreamReader(versionsURL.openStream()));
        if (!versions.containsKey("Sauce Connect 2")) {
            return "http://saucelabs.com/downloads/Sauce-Connect-2-latest.zip";
        }
        JSONObject versionDetails = (JSONObject) versions.get("Sauce Connect 2");
        String remoteVersion = (String) versionDetails.get("version");
        int remoteRelease = Integer.valueOf(remoteVersion.substring(remoteVersion.indexOf("-r") + 2));
        if (localRelease < remoteRelease) {
            return (String) versionDetails.get("download_url");
        } else {
            return null;
        }
    }

    /**
     * Not thread safe
     *
     * @return
     */
    public static int getHealthCheckInterval() {
        return interpreter.eval("sauce_connect.HEALTH_CHECK_INTERVAL").asInt() * 1000;
    }

    public void setStandaloneMode(boolean standaloneMode) {
        this.standaloneMode = standaloneMode;
    }
}
