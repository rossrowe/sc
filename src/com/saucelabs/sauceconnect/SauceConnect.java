package com.saucelabs.sauceconnect;

import org.python.core.*;
import org.python.util.PythonInterpreter;

public class SauceConnect {
	private static PythonInterpreter interpreter;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		interpreter = new PythonInterpreter();
		interpreter.exec("import sauce_connect");
		PyString[] pythonArgs = new PyString[args.length];
		for(int index = 0; index < args.length; index++){
			pythonArgs[index] = new PyString(args[index]);
		}
		interpreter.set("arglist", new PyList(pythonArgs));
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