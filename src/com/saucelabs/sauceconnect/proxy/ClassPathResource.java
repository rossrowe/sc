/*
 * Copyright 2006 ThoughtWorks, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
/*
 * Adapted from the Selenium project under the Apache License 2.0
 * 
 * Portions Copyright 2011 Sauce Labs, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.saucelabs.sauceconnect.proxy;

import org.eclipse.jetty.util.resource.Resource;
import org.mortbay.util.IO;


import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Represents resource file off of the classpath.
 * 
 * @author Patrick Lightbody (plightbo at gmail dot com)
 */
public class ClassPathResource extends Resource {
	String path;

	ByteArrayOutputStream os;

	/**
	 * Specifies the classpath path containing the resource
	 */
	public ClassPathResource(String path) {
		this.path = path;
		InputStream is = ResourceExtractor.getSeleniumResourceAsStream(path);
		if (is != null) {
			os = new ByteArrayOutputStream();
			try {
				IO.copy(is, os);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/* ------------------------------------------------------------ */
	public Object getAssociate() {
		return super.getAssociate();
	}

    @Override
    public boolean isContainedIn(Resource resource) throws MalformedURLException {
        return false;
    }

    public void release() {
	}

	public boolean exists() {
		return os != null;
	}

	public boolean isDirectory() {
		return false;
	}

	/**
	 * Returns the lastModified time, which is always in the distant future to
	 * prevent caching.
	 */
	public long lastModified() {
		return System.currentTimeMillis() + 1000l * 3600l * 24l * 365l;
	}

	public long length() {
		if (os != null) {
			return os.size();
		}

		return 0;
	}

	public URL getURL() {
		return null;
	}

	public File getFile() throws IOException {
		return null;
	}

	public String getName() {
		return path;
	}

	public InputStream getInputStream() throws IOException {
		if (os != null) {
			return new ByteArrayInputStream(os.toByteArray());
		}
		return null;
	}

	public OutputStream getOutputStream() throws IOException, SecurityException {
		return null;
	}

	public boolean delete() throws SecurityException {
		return false;
	}

	public boolean renameTo(Resource dest) throws SecurityException {
		return false;
	}

	public String[] list() {
		return new String[0];
	}

	public Resource addPath(String pathParm) throws IOException,
			MalformedURLException {
		return new ClassPathResource(this.path + "/" + pathParm);
	}

	@Override
	public String toString() {
		return getName();
	}
}
