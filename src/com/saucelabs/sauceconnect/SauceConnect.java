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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.bouncycastle.util.encoders.Base64;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.python.core.PyInteger;
import org.python.core.PyList;
import org.python.core.PyString;
import org.python.util.PythonInterpreter;

public class SauceConnect {
    private static final int RELEASE = 4;
    private static PythonInterpreter interpreter = null;
    private static SauceProxy proxy = null;
    
    public static PythonInterpreter getInterpreter(){
        if(interpreter == null){
            interpreter = new PythonInterpreter();
            interpreter.exec("import sauce_connect");
        }
        return interpreter;
    }

    @SuppressWarnings("static-access")
    private static CommandLine parseArgs(String[] args) {
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
            options.addOption("l", "lite", false, null);
        try {
            CommandLineParser parser = new PosixParser();
            CommandLine result = parser.parse(options, args);
            if(result.hasOption("help")){
                HelpFormatter help = new HelpFormatter();
                help.printHelp("java -jar Sauce-Connect.jar USERNAME API_KEY [OPTIONS]", options);
                System.exit(0);
            }
            if(result.hasOption("version")){
                System.out.println("Version: Sauce Connect 2.0-r"+RELEASE);
                System.exit(0);
            }
            if(result.getArgs().length == 0){
                throw new ParseException("Missing required arguments USERNAME, API_KEY");
            }
            if(result.getArgs().length == 1){
                throw new ParseException("Missing required argument API_KEY");
            }
            return result;
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            System.err.println();
            HelpFormatter help = new HelpFormatter();
            help.printHelp("java -jar Sauce-Connect.jar USERNAME API_KEY [OPTIONS]", options);
            System.exit(1);
            return null;
        }
    }

    private static PyList generateArgsForSauceConnect(String username, String apikey, String domain, CommandLine options) {
        ArrayList<PyString> args = new ArrayList<PyString>();
        args.add(new PyString("-u"));
        args.add(new PyString(username));
        args.add(new PyString("-k"));
        args.add(new PyString(apikey));
        args.add(new PyString("-d"));
        args.add(new PyString(domain));
        args.add(new PyString("-s"));
        args.add(new PyString("127.0.0.1"));
        args.add(new PyString("--ssh-port"));
        args.add(new PyString("443"));
        if (options != null) {
            if (options.hasOption('x')) {
                args.add(new PyString("--rest-url"));
                args.add(new PyString(options.getOptionValue('x')));
            }
            if (options.hasOption('f')) {
                args.add(new PyString("--readyfile"));
                args.add(new PyString(options.getOptionValue('f')));
            }
            if (options.hasOption("boost-mode")) {
                args.add(new PyString("-b"));
            }
        }

        return new PyList(args);
    }

    public static void main(String[] args) {
        boolean lite = false;
        PyList strippedArgs = new PyList();

        for(String s : args){
            if(s.equals("-l") || s.equals("--lite")){
                lite = true;
            } else {
                strippedArgs.add(new PyString(s));
            }
        }
        if(lite) {
            getInterpreter().set("arglist", strippedArgs);
            getInterpreter().exec("options = sauce_connect.get_options(arglist)");
            interpreter.exec("sauce_connect.setup_logging(options.logfile, options.quiet)");
            PythonLogHandler.install();
            interpreter.exec("tunnel_for_java_to_kill = None");
            interpreter.exec("def setup_java_signal_handler(tunnel, options):\n"
                    + "  global tunnel_for_java_to_kill\n" + "  tunnel_for_java_to_kill = tunnel\n");
    
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    interpreter.exec("sauce_connect.logger.removeHandler(sauce_connect.fileout)");
                    mainThread.interrupt();
                    interpreter
                            .exec("sauce_connect.peace_out(tunnel_for_java_to_kill, atexit=True)");
                }
            });
    
            try {
                getInterpreter().exec("from com.saucelabs.sauceconnect import ReverseSSH as JavaReverseSSH");
                interpreter.exec("sauce_connect.run(options,"
                        + "setup_signal_handler=setup_java_signal_handler,"
                        + "reverse_ssh=JavaReverseSSH)");
            } catch (Exception e) {
                // uncomment for debugging:
                //e.printStackTrace();
                System.exit(3);
            }
        } else {
            versionCheck();
            final CommandLine parsedArgs = parseArgs(args);;
            String domain = "sauce-connect.proxy";

            if(parsedArgs.hasOption("proxy-host")) {
                domain = parsedArgs.getOptionValue("proxy-host");
            }
            if(!parsedArgs.hasOption("dont-update-proxy-host")){
                int port = 80;
                if(parsedArgs.hasOption("boost-mode")){
                    port = 33128;
                }
                updateDefaultProxyHost(parsedArgs.getArgs()[0], parsedArgs.getArgs()[1], domain, port,
                        parsedArgs.getOptionValue("rest-url", "http://saucelabs.com/rest"));
            }
            getInterpreter().set(
                    "arglist",
                    generateArgsForSauceConnect(parsedArgs.getArgs()[0],
                            parsedArgs.getArgs()[1], domain, parsedArgs));
    
            getInterpreter().exec("options = sauce_connect.get_options(arglist)");
            interpreter.exec("sauce_connect.setup_logging(options.logfile, options.quiet)");
            PythonLogHandler.install();
    
            try {
                proxy = new SauceProxy();
                proxy.start();
                interpreter.exec("options.ports = ['"+proxy.getPort()+"']");
            } catch (Exception e) {
                System.err.println("Error starting proxy: " + e.getMessage());
                System.exit(2);
            }
            
            interpreter.exec("tunnel_for_java_to_kill = None");
            interpreter.exec("def setup_java_signal_handler(tunnel, options):\n"
                    + "  global tunnel_for_java_to_kill\n" + "  tunnel_for_java_to_kill = tunnel\n");
    
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    if(parsedArgs != null && !parsedArgs.hasOption("dont-update-proxy-host")) {
                        updateDefaultProxyHost(parsedArgs.getArgs()[0], parsedArgs.getArgs()[1], null, 0,
                                parsedArgs.getOptionValue("rest-url", "http://saucelabs.com/rest"));
                    }
                    interpreter.exec("sauce_connect.logger.removeHandler(sauce_connect.fileout)");
                    interpreter
                            .exec("sauce_connect.peace_out(tunnel_for_java_to_kill, atexit=True)");
                }
            });
    
            try {
                getInterpreter().exec("from com.saucelabs.sauceconnect import ReverseSSH as JavaReverseSSH");
                interpreter.exec("sauce_connect.run(options,"
                        + "setup_signal_handler=setup_java_signal_handler,"
                        + "reverse_ssh=JavaReverseSSH,do_check_version=False)");
            } catch (Exception e) {
                // uncomment for debugging:
                //e.printStackTrace();
                System.exit(3);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void updateDefaultProxyHost(String username, String password, String proxyHost, int proxyPort, String restURL) {
        try {
            URL restEndpoint = new URL(restURL+"/v1/"+username+"/defaults");
            String auth = username + ":" + password;
            auth = "Basic " + new String(Base64.encode(auth.getBytes()));
            URLConnection connection = restEndpoint.openConnection();
            connection.setRequestProperty("Authorization", auth);
            InputStream data = connection.getInputStream();
            JSONParser parser = new JSONParser();
            JSONObject currentDefaults = (JSONObject)parser.parse(new InputStreamReader(data));
            if(proxyHost != null) {
                currentDefaults.put("proxy-host", proxyHost+":"+proxyPort);
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
        } catch(IOException e) {
            System.err.println("Error connecting to Sauce OnDemand REST API: ");
            e.printStackTrace();
            System.exit(5);
        } catch(org.json.simple.parser.ParseException e) {
            System.err.println("Error reading from Sauce OnDemand REST API: ");
            e.printStackTrace();
            System.exit(5);
        }
    }
    
    private static void versionCheck() {
        try {
            String downloadURL = getDownloadURL(RELEASE);
            if(downloadURL != null){
                System.err.println("** This version of Sauce Connect is outdated.\n" +
				"** Please update with "+downloadURL);
            }
        } catch(IOException e) {
            System.err.println("Error checking Sauce Connect version:");
            e.printStackTrace();
        } catch(org.json.simple.parser.ParseException e) {
            System.err.println("Error checking Sauce Connect version:");
            e.printStackTrace();
        }
    }
    
    /**
     * Get the download URL for the newer release of Sauce Connect if this version is outdated.
     * @param localRelease
     * @return The download URL or null if the release is current.
     * @throws IOException
     * @throws org.json.simple.parser.ParseException
     */
    public static String getDownloadURL(int localRelease) throws IOException, org.json.simple.parser.ParseException {
        URL versionsURL = new URL("http://saucelabs.com/versions.json");
        JSONObject versions = (JSONObject) new JSONParser().parse(new InputStreamReader(versionsURL.openStream()));
        if(!versions.containsKey("Sauce Connect 2")){
            return "http://saucelabs.com/downloads/Sauce-Connect-2-latest.zip";
        }
        JSONObject versionDetails = (JSONObject) versions.get("Sauce Connect 2");
        String remoteVersion = (String)versionDetails.get("version");
        int remoteRelease = Integer.valueOf(remoteVersion.substring(remoteVersion.indexOf("-r")+2));
        if(localRelease < remoteRelease) {
            return (String)versionDetails.get("download_url");
        } else {
            return null;
        }
    }

    public static int getHealthCheckInterval() {
        return ((PyInteger) interpreter.eval("sauce_connect.HEALTH_CHECK_INTERVAL")).asInt() * 1000;
    }
}