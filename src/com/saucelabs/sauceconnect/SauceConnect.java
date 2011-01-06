package com.saucelabs.sauceconnect;

import java.util.ArrayList;
import java.util.Random;

import org.apache.commons.cli.*;
import org.apache.commons.cli.Options;
import org.python.core.*;
import org.python.util.PythonInterpreter;

public class SauceConnect {
    private static PythonInterpreter interpreter = null;
    private static SauceProxy proxy = null;
    
    public static PythonInterpreter getInterpreter(){
        if(interpreter == null){
            interpreter = new PythonInterpreter();
            interpreter.exec("import sauce_connect");
        }
        return interpreter;
    }

    private static CommandLine parseArgs(String[] args) {
            Options options = new Options();
            Option readyfile = new Option("f", "readyfile", true, "Ready file that will be touched when tunnel is ready");
            readyfile.setArgName("FILE");
            options.addOption(readyfile);
            options.addOption("x", "rest-url", true, "Advanced feature: Connect to Sauce OnDemand at alternative URL." +
            		" Use only if directed to by Sauce Labs support.");
        try {
            CommandLineParser parser = new PosixParser();
            CommandLine result = parser.parse(options, args);
            if(result.getArgs().length == 0){
                throw new ParseException("Missing required argument USERNAME");
            }
            if(result.getArgs().length == 1){
                throw new ParseException("Missing required argument API_KEY");
            }
            return result;
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            System.err.println();
            HelpFormatter help = new HelpFormatter();
            help.printHelp("java -jar Sauce_Connect.jar USERNAME API_KEY [OPTIONS]", options);
            System.exit(1);
            return null;
        }
    }

    private static PyList generateArgsForSauceConnect(CommandLine options, String domain) {
        ArrayList<PyString> args = new ArrayList<PyString>();
        args.add(new PyString("-u"));
        args.add(new PyString(options.getArgs()[0]));
        args.add(new PyString("-k"));
        args.add(new PyString(options.getArgs()[1]));
        args.add(new PyString("-d"));
        args.add(new PyString(domain));
        args.add(new PyString("-s"));
        args.add(new PyString("127.0.0.1"));
        args.add(new PyString("--ssh-port"));
        args.add(new PyString("443"));
        if (options.hasOption('x')) {
            args.add(new PyString("--rest-url"));
            args.add(new PyString(options.getOptionValue('x')));
        }
        if (options.hasOption('f')) {
            args.add(new PyString("--readyfile"));
            args.add(new PyString(options.getOptionValue('f')));
        }

        return new PyList(args);
    }

    public static void main(String[] args) {
        CommandLine parsedArgs = null;
        String domain = String.valueOf(new Random().nextInt(10000)) + ".proxy";

        parsedArgs = parseArgs(args);

        getInterpreter();
        interpreter.set("arglist", generateArgsForSauceConnect(parsedArgs, domain));
        interpreter.exec("from com.saucelabs.sauceconnect import ReverseSSH as JavaReverseSSH");
        interpreter.exec("options = sauce_connect.get_options(arglist)");
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
            interpreter.exec("sauce_connect.run(options,"
                    + "setup_signal_handler=setup_java_signal_handler,"
                    + "reverse_ssh=JavaReverseSSH)");
        } catch (Exception e) {
            // uncomment for debugging:
            //e.printStackTrace();
            System.exit(3);
        }
    }

    public static int getHealthCheckInterval() {
        return ((PyInteger) interpreter.eval("sauce_connect.HEALTH_CHECK_INTERVAL")).asInt() * 1000;
    }
}