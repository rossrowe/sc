package com.saucelabs.sauceconnect;

import java.util.ArrayList;
import java.util.Random;

import org.apache.commons.cli.*;
import org.apache.commons.cli.Options;
import org.python.core.*;
import org.python.util.PythonInterpreter;

public class SauceConnect {
	public static PythonInterpreter interpreter;

	private static CommandLine parseArgs(String[] args) throws ParseException{
		Options options = new Options();
		Option username = new Option("u", "username", true, "Sauce OnDemand account name");
		username.setRequired(true);
		options.addOption(username);
		Option apikey = new Option("k", "api-key", true, "Sauce OnDemand API key");
		apikey.setRequired(true);
		options.addOption(apikey);
		options.addOption("r", "rest-url", true, null);
		CommandLineParser parser = new PosixParser();
		return parser.parse(options, args);
	}
	
	private static PyList generateArgsForSauceConnect(String username, String api_key, String domain, String rest_url){
		ArrayList<PyString> args = new ArrayList<PyString>();
		args.add(new PyString("-u"));
		args.add(new PyString(username));
		args.add(new PyString("-k"));
		args.add(new PyString(api_key));
		args.add(new PyString("-p"));
		args.add(new PyString("4445"));
		args.add(new PyString("-d"));
		args.add(new PyString(domain));
		args.add(new PyString("-s"));
		args.add(new PyString("127.0.0.1"));
		if(rest_url != null){
			args.add(new PyString("--rest-url"));
			args.add(new PyString(rest_url));
		}
		
		return new PyList(args);
	}
	
	public static void main(String[] args) {
		CommandLine parsedArgs = null;
		String domain = String.valueOf(new Random().nextInt(10000)) + ".proxy.saucelabs.com";
		
		try {
			parsedArgs = parseArgs(args);
		} catch (ParseException e1) {
			System.err.println(e1.getMessage());
			return;
		}
		
		SauceProxy proxy = new SauceProxy();
		try {
			proxy.start();
		} catch (Exception e) {
			log("Error starting proxy: "+e.getMessage());
		}
		
		interpreter = new PythonInterpreter();
		interpreter.exec("import sauce_connect");
		interpreter.set("arglist", generateArgsForSauceConnect(parsedArgs.getOptionValue('u'),
				parsedArgs.getOptionValue('k'),
				domain,
				parsedArgs.getOptionValue("rest-url")));
		interpreter.exec("from com.saucelabs.sauceconnect import ReverseSSH as JavaReverseSSH");
		interpreter.exec("options = sauce_connect.get_options(arglist)");
		interpreter.exec("sauce_connect.setup_logging(options.logfile, options.quiet)");
		interpreter.exec("tunnel_for_java_to_kill = None");
		interpreter.exec("def setup_java_signal_handler(tunnel, options):\n" +
				"  global tunnel_for_java_to_kill\n" +
				"  tunnel_for_java_to_kill = tunnel\n");

		final Thread mainThread = Thread.currentThread();
		Runtime.getRuntime().addShutdownHook(new Thread(){
			public void run(){
				try {
					interpreter.exec("sauce_connect.logger.removeHandler(sauce_connect.fileout)");
					mainThread.interrupt();
					interpreter.exec("sauce_connect.peace_out(tunnel_for_java_to_kill, atexit=True)");
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		});

		try {
			interpreter.exec("sauce_connect.run(options, setup_signal_handler=setup_java_signal_handler, reverse_ssh=JavaReverseSSH)");
		} catch (Exception e){
			// swallow silently
		}
	}

	public static void log(String line){
		interpreter.exec("sauce_connect.logger.info('"+line+"')");
	}
}