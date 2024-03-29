package org.red5.server.service;

/*
 * RED5 Open Source Flash Server - http://code.google.com/p/red5/
 * 
 * Copyright (c) 2006-2010 by respective authors (see below). All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or modify it under the 
 * terms of the GNU Lesser General Public License as published by the Free Software 
 * Foundation; either version 2.1 of the License, or (at your option) any later 
 * version. 
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along 
 * with this library; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.red5.compatibility.flex.messaging.messages.AcknowledgeMessage;
import org.red5.compatibility.flex.messaging.messages.AsyncMessage;
import org.red5.logging.Red5LoggerFactory;
import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.service.ServiceUtils;
import org.red5.server.jmx.JMXFactory;
import org.red5.server.jmx.mxbeans.LoaderMXBean;
import org.red5.server.util.FileUtil;
import org.slf4j.Logger;

/**
 * This service provides the means to list, download, install, and un-install 
 * applications from a given url. 
 * 
 * @author Paul Gregoire (mondain@gmail.com)
 * @author Dominick Accattato (daccattato@gmail.com)
 */
public final class Installer {

	private static Logger log = Red5LoggerFactory.getLogger(Installer.class);

	private String applicationRepositoryUrl;

	private static final String userAgent = "Mozilla/4.0 (compatible; Red5 Server)";

	{
		log.info("Installer service created");
	}

	public String getApplicationRepositoryUrl() {
		return applicationRepositoryUrl;
	}

	public void setApplicationRepositoryUrl(String applicationRepositoryUrl) {
		this.applicationRepositoryUrl = applicationRepositoryUrl;
	}

	/**
	 * Returns the LoaderMBean.
	 * @return LoaderMBean
	 */
	@SuppressWarnings("cast")
	public LoaderMXBean getLoader() {
		MBeanServer mbs = JMXFactory.getMBeanServer();
		ObjectName oName = JMXFactory.createObjectName("type", "TomcatLoader");
		LoaderMXBean proxy = null;
		if (mbs.isRegistered(oName)) {
			proxy = (LoaderMXBean) JMX.newMXBeanProxy(mbs, oName, LoaderMXBean.class, true);
			log.debug("Loader was found");
		} else {
			log.warn("Loader not found");
		}
		return proxy;
	}

	/**
	 * Returns a Map containing all of the application wars in the snapshot repository.
	 * 
	 * @return async message
	 */
	public AsyncMessage getApplicationList() {
		AcknowledgeMessage result = new AcknowledgeMessage();

		// create a singular HttpClient object
		HttpClient client = new HttpClient();

		// set the proxy (WT)
		// test if we received variables on the commandline
		if ((System.getProperty("http.proxyHost") != null) && (System.getProperty("http.proxyPort") != null)) {
			HostConfiguration config = client.getHostConfiguration();
			config.setProxy(System.getProperty("http.proxyHost").toString(), Integer.parseInt(System.getProperty("http.proxyPort")));
		}

		// establish a connection within 5 seconds
		client.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
		//get the params for the client
		HttpClientParams params = client.getParams();
		params.setParameter(HttpMethodParams.USER_AGENT, userAgent);
		//get registry file
		HttpMethod method = new GetMethod(applicationRepositoryUrl + "registry-0.9.xml");
		//follow any 302's although there shouldnt be any
		method.setFollowRedirects(true);
		// execute the method
		try {
			IConnection conn = Red5.getConnectionLocal();
			int code = client.executeMethod(method);
			log.debug("HTTP response code: {}", code);
			String xml = method.getResponseBodyAsString();
			log.trace("Response: {}", xml);
			//prepare response for flex			
			result.body = xml;
			result.clientId = conn.getClient().getId();
			result.messageId = UUID.randomUUID().toString();
			result.timestamp = System.currentTimeMillis();
			//send the servers java version so the correct apps are installed
			String javaVersion = System.getProperty("java.version");
			if (!ServiceUtils.invokeOnConnection(conn, "onJavaVersion", new Object[] { javaVersion })) {
				log.warn("Client call to onJavaVersion failed");
			}
		} catch (HttpException he) {
			log.error("Http error connecting to {}", applicationRepositoryUrl, he);
		} catch (IOException ioe) {
			log.error("Unable to connect to {}", applicationRepositoryUrl, ioe);
		} finally {
			if (method != null) {
				method.releaseConnection();
			}
		}

		return result;
	}

	/**
	 * Installs a given application.
	 * 
	 * @param applicationWarName app war name
	 * @return true if installed; false otherwise
	 */
	public boolean install(String applicationWarName) {
		IConnection conn = Red5.getConnectionLocal();

		boolean result = false;

		//strip everything except the applications name
		String application = applicationWarName.substring(0, applicationWarName.indexOf('-'));
		log.debug("Application name: {}", application);

		//get webapp location
		String webappsDir = System.getProperty("red5.webapp.root");
		log.debug("Webapp folder: {}", webappsDir);

		//setup context
		String contextPath = '/' + application;
		String contextDir = webappsDir + contextPath;

		//verify this is a unique app
		File appDir = new File(webappsDir, application);
		if (appDir.exists()) {
			if (appDir.isDirectory()) {
				log.debug("Application directory exists");
			} else {
				log.warn("Application destination is not a directory");
			}

			ServiceUtils.invokeOnConnection(conn, "onAlert", new Object[]{String.format("Application %s already installed, please un-install before attempting another install", application)});			
		} else {
			//use the system temp directory for moving files around
			String srcDir = System.getProperty("java.io.tmpdir");
			log.debug("Source directory: {}", srcDir);
			//look for archive containing application (war, zip, etc..)
			File dir = new File(srcDir);
			if (!dir.exists()) {
				log.warn("Source directory not found");
				//use another directory
				dir = new File(System.getProperty("red5.root"), "/webapps/installer/WEB-INF/cache");
				if (!dir.exists()) {
					if (dir.mkdirs()) {
						log.info("Installer cache directory created");
					}
				}
			} else {
				if (!dir.isDirectory()) {
					log.warn("Source directory is not a directory");
				}
			}
			//get a list of temp files
			File[] files = dir.listFiles();
			for (File f : files) {
				String fileName = f.getName();
				if (fileName.equals(applicationWarName)) {
					log.debug("File found matching application name");
					result = true;
					break;
				}
			}
			dir = null;

			//if the file was not found then download it
			if (!result) {
				// create a singular HttpClient object
				HttpClient client = new HttpClient();

				// set the proxy (WT)
				if ((System.getProperty("http.proxyHost") != null) && (System.getProperty("http.proxyPort") != null)) {
					HostConfiguration config = client.getHostConfiguration();
					config.setProxy(System.getProperty("http.proxyHost").toString(), Integer.parseInt(System.getProperty("http.proxyPort")));
				}

				// establish a connection within 5 seconds
				client.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
				//get the params for the client
				HttpClientParams params = client.getParams();
				params.setParameter(HttpMethodParams.USER_AGENT, userAgent);
				params.setParameter(HttpMethodParams.STRICT_TRANSFER_ENCODING, Boolean.TRUE);

				//try the wav version first
				HttpMethod method = new GetMethod(applicationRepositoryUrl + applicationWarName);
				//we dont want any transformation - RFC2616
				method.addRequestHeader("Accept-Encoding", "identity");
				//follow any 302's although there shouldnt be any
				method.setFollowRedirects(true);
				FileOutputStream fos = null;
				// execute the method
				try {
					int code = client.executeMethod(method);
					log.debug("HTTP response code: {}", code);
					//create output file
					fos = new FileOutputStream(srcDir + '/' + applicationWarName);
					log.debug("Writing response to {}/{}", srcDir, applicationWarName);

					// have to receive the response as a byte array.  This has the advantage of writing to the filesystem
					// faster and it also works on macs ;)
					byte[] buf = method.getResponseBody();
					fos.write(buf);
					fos.flush();

					result = true;
				} catch (HttpException he) {
					log.error("Http error connecting to {}", applicationRepositoryUrl, he);
				} catch (IOException ioe) {
					log.error("Unable to connect to {}", applicationRepositoryUrl, ioe);
				} finally {
					if (fos != null) {
						try {
							fos.close();
						} catch (IOException e) {
						}
					}
					if (method != null) {
						method.releaseConnection();
					}
				}
			}

			//if we've found or downloaded the war
			if (result) {
				//get the webapp loader
				LoaderMXBean loader = getLoader();
				if (loader != null) {
					//un-archive it to app dir
					FileUtil.unzip(srcDir + '/' + applicationWarName, contextDir);
					//load and start the context
					loader.startWebApplication(application);
				} else {
					//just copy the war to the webapps dir
					try {
						FileUtil.moveFile(srcDir + '/' + applicationWarName, webappsDir + '/' + application + ".war");
						ServiceUtils.invokeOnConnection(conn, "onAlert", new Object[]{String.format("Application %s will not be available until container is restarted", application)});			
					} catch (IOException e) {
					}
				}
			}

			ServiceUtils.invokeOnConnection(conn, "onAlert", new Object[]{String.format("Application %s was %s", application, (result ? "installed" : "not installed"))});
		
		}
		appDir = null;

		return result;
	}

	/**
	 * Un-installs a given application.
	 * 
	 * @param applicationName name to uninstall
	 * @return true if uninstalled; else false
	 */
	public boolean uninstall(String applicationName) {
		ServiceUtils.invokeOnConnection(Red5.getConnectionLocal(), "onAlert", new Object[]{"Uninstall function not available"});

		return false;
	}

}
