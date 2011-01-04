package com.saucelabs.sauceconnect;

import org.python.core.*;
import org.python.util.PythonInterpreter;

public class SauceConnect {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		PythonInterpreter interpreter = new PythonInterpreter();
		interpreter.exec("import sauce_connect");
		PyString[] pythonArgs = new PyString[args.length];
		for(int index = 0; index < args.length; index++){
			pythonArgs[index] = new PyString(args[index]);
		}
		interpreter.set("arglist", new PyList(pythonArgs));
		interpreter.exec("from com.saucelabs.sauceconnect import ReverseSSH as JavaReverseSSH");
		interpreter.exec("options = sauce_connect.get_options(arglist)");
		interpreter.exec("sauce_connect.setup_logging(options.logfile, options.quiet)");
		interpreter.exec("sauce_connect.run(options, enable_signal_handlers=False, reverse_ssh=JavaReverseSSH)");
	}
}