package com.samblake.log4j;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.Charset;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.net.SocketNode;
import org.apache.log4j.net.ZeroConfSupport;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LoggingEvent;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Contributors: Dan MacDonald <dan@redknee.com>

/**
 * Based on the log4j {@link org.apache.log4j.net.SocketAppender}.
 * Sends formatted messages to Log.io.
 * 
 * See {@link org.apache.log4j.net.SocketAppender}
 * 
 * @author sam.adams;
 */
public class LogIOAppender extends AppenderSkeleton {

	/**
	 * The default port number of remote logging server (28777).
	 */
	static public final int DEFAULT_PORT = 28777;
	
	/**
	 * The default reconnection delay (30000 milliseconds or 30 seconds).
	 */
	static final int DEFAULT_RECONNECTION_DELAY = 30000;
	
	/**
	 * The MulticastDNS zone advertised by a SocketAppender
	 */
	public static final String ZONE = "_log4j_obj_logio_appender.local.";
	
	/**
	 * We remember host name as String in addition to the resolved InetAddress
	 * so that it can be returned via getOption().
	 */
	String remoteHost;
	InetAddress address;
	int port = DEFAULT_PORT;
	
	String node;
	String stream;
	
	Charset charset = Charset.defaultCharset();
	int reconnectionDelay = DEFAULT_RECONNECTION_DELAY;
	boolean locationInfo = false;
	String application;
	String indent = "    ";

	private OutputStream out;
	private Connector connector;
	
	private boolean advertiseViaMulticastDNS;
	private ZeroConfSupport zeroConf;
	
	public LogIOAppender() {
	}
	
	/**
	 * Connects to remote server at <code>address</code> and <code>port</code>.
	 */
	public LogIOAppender(InetAddress address, int port, String node, String stream) {
		this(node, stream, port);
		this.address = address;
		this.remoteHost = address.getHostName();
	}
	
	/**
	 * Connects to remote server at <code>host</code> and <code>port</code>.
	 */
	public LogIOAppender(String host, int port, String node, String stream) {
		this(node, stream, port);
		this.address = getAddressByName(host);
		this.remoteHost = host;
	}

	private LogIOAppender(String node, String stream, int port) {
		this.port = port;
		this.node = node;
		this.stream = stream;
	}
	
	/**
	 * Connect to the specified <b>RemoteHost</b> and <b>Port</b>.
	 */
	public void activateOptions() {
		if (advertiseViaMulticastDNS) {
			zeroConf = new ZeroConfSupport(ZONE, port, getName());
			zeroConf.advertise();
		}
		connect(address, port);
	}
	
	/**
	 * Drop the connection to the remote host and release the underlying
	 * connector thread if it has been created
	 * */
	public void cleanUp() {
		if (out != null) {
			try {
				remove();
				out.close();
			}
			catch (IOException e) {
				LogLog.error("Could not close output stream.", e);
				if (e instanceof InterruptedIOException)
					Thread.currentThread().interrupt();
			}
			out = null;
		}
		
		if (connector != null) {
			connector.interrupted = true;
			connector = null; // Allow GC
		}
	}

	private void register() throws IOException {
		register(node, stream);
	}
	
	/**
	 * +node|my_node|my_stream1,my_stream2\r\n
	 */
	private void register(String node, String... streams) throws IOException {
		StringBuilder register = new StringBuilder(String.format("+node|%s|", node));
		boolean first = true;
		for (String stream : streams) {
			if (first)
				first = false;
			else
				register.append(",");	
			register.append(stream);
		}
		register.append(System.getProperty("line.separator"));
		send(register.toString());
	}

	private void remove() throws IOException {
		remove(node);
	}
	
	/**
	 * -node|my_node\r\n
	 */
	private void remove(String node) throws IOException {
		send(String.format("-node|%s", node));
	}

	private void sendMessage(Level level, String message) throws IOException {
		sendMessage(node, stream, level, message);
	}
	
	/**
	 * +log|my_stream|my_node|info|this is log message\r\n
	 */
	private void sendMessage(String node, String stream, Level level, String message) throws IOException {
		send(String.format("+log|%s|%s|%s|%s", stream, node, level.toString().toLowerCase(), message));
	}
	
	private void send(String message) throws IOException {
		if (out != null) {
			out.write(message.getBytes(charset));
			out.flush();
		}
	}
	
	void connect(InetAddress address, int port) {
		if (this.address == null)
			return;
		
		try {
			cleanUp();
			out = new PrintStream(new Socket(address, port).getOutputStream());
			register();
		}
		catch (IOException e) {
			handleIOError(e, "Could not connect to remote log4j server at [" + address.getHostName() + "].");
		}
	}
	
	public void append(LoggingEvent event) {
		if (event == null)
			return;
		
		if (address == null) {
			errorHandler.error("No remote host is set for SocketAppender named \"" + this.name + "\".");
			return;
		}
		
		if (out != null) {
			try {
				if (locationInfo)
					event.getLocationInformation();
				if (application != null)
					event.setProperty("application", application);
				
				sendMessage(event.getLevel(), layout.format(event));
				
				if (layout.ignoresThrowable()) {
					String[] lines = event.getThrowableStrRep();
					if (lines != null) {
						for (String line : lines) {
							line = line.replaceAll("^\t", indent);
							sendMessage(event.getLevel(), line + Layout.LINE_SEP);
						}
					}
				}
			}
			catch (IOException e) {				
				out = null;
				handleIOError(e, "Could not log message");
			}
		}
	}

	private void handleIOError(IOException e, String msg) {
		if (e instanceof InterruptedIOException)
			Thread.currentThread().interrupt();
		
		if (reconnectionDelay > 0) {
			msg += " We will try again later.";
			fireConnector();
		}
		else {
			msg += " We are not retrying.";
			errorHandler.error(msg, e, ErrorCode.GENERIC_FAILURE);
		}
		LogLog.warn(msg, e);
	}

	public void setAdvertiseViaMulticastDNS(boolean advertiseViaMulticastDNS) {
		this.advertiseViaMulticastDNS = advertiseViaMulticastDNS;
	}
	
	public boolean isAdvertiseViaMulticastDNS() {
		return advertiseViaMulticastDNS;
	}
	
	void fireConnector() {
		if (connector == null) {
			LogLog.debug("Starting a new connector thread.");
			connector = new Connector();
			connector.setDaemon(true);
			connector.setPriority(Thread.MIN_PRIORITY);
			connector.start();
		}
	}
	
	static InetAddress getAddressByName(String host) {
		try {
			return InetAddress.getByName(host);
		}
		catch (Exception e) {
			if (e instanceof InterruptedIOException || e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LogLog.error("Could not find address of [" + host + "].", e);
			return null;
		}
	}
	
	/**
	 * Close this appender.
	 * 
	 * <p>
	 * This will mark the appender as closed and call then {@link #cleanUp}
	 * method.
	 * */
	synchronized public void close() {
		if (closed)
			return;
		
		if (advertiseViaMulticastDNS)
			zeroConf.unadvertise();
		
		cleanUp();
		this.closed = true;
	}
	
	public boolean requiresLayout() {
		return true;
	}
	
	/**
	 * The <b>RemoteHost</b> option takes a string value which should be the
	 * host name of the server where a {@link SocketNode} is running.
	 * */
	public void setRemoteHost(String host) {
		address = getAddressByName(host);
		remoteHost = host;
	}
	
	/**
	 * Returns value of the <b>RemoteHost</b> option.
	 */
	public String getRemoteHost() {
		return remoteHost;
	}
	
	/**
	 * The <b>Port</b> option takes a positive integer representing the port
	 * where the server is waiting for connections.
	 */
	public void setPort(int port) {
		this.port = port;
	}
	
	/**
	 * Returns value of the <b>Port</b> option.
	 */
	public int getPort() {
		return port;
	}
	
	/**
	 * The <b>LocationInfo</b> option takes a boolean value. If true, the
	 * information sent to the remote host will include location information. By
	 * default no location information is sent to the server.
	 */
	public void setLocationInfo(boolean locationInfo) {
		this.locationInfo = locationInfo;
	}
	
	/**
	 * Returns value of the <b>LocationInfo</b> option.
	 */
	public boolean getLocationInfo() {
		return locationInfo;
	}
	
	/**
	 * The <b>App</b> option takes a string value which should be the name of
	 * the application getting logged. If property was already set (via system
	 * property), don't set here.
	 * 
	 * @since 1.2.15
	 */
	public void setApplication(String lapp) {
		this.application = lapp;
	}
	
	/**
	 * Returns value of the <b>Application</b> option.
	 * 
	 * @since 1.2.15
	 */
	public String getApplication() {
		return application;
	}
	
	/**
	 * The <b>ReconnectionDelay</b> option takes a positive integer representing
	 * the number of milliseconds to wait between each failed connection attempt
	 * to the server. The default value of this option is 30000 which
	 * corresponds to 30 seconds.
	 * 
	 * <p>
	 * Setting this option to zero turns off reconnection capability.
	 */
	public void setReconnectionDelay(int delay) {
		this.reconnectionDelay = delay;
	}
	
	/**
	 * Returns value of the <b>ReconnectionDelay</b> option.
	 */
	public int getReconnectionDelay() {
		return reconnectionDelay;
	}
	
	/**
	 * @return The name of the node the appender should publish itself as
	 */
	public String getNode() {
		return node;
	}

	/**
	 * @param node The name of the node the appender should publish itself as
	 */
	public void setNode(String node) {
		this.node = node;
	}

	/**
	 * @return The name of the stream the appender should publish itself as
	 */
	public String getStream() {
		return stream;
	}

	/**
	 * @param stream The name of the stream the appender should publish itself as
	 */
	public void setStream(String stream) {
		this.stream = stream;
	}
	
	/**
	 * @return the charset
	 */
	public Charset getCharset() {
		return charset;
	}

	/**
	 * @param charset the charset to set
	 */
	public void setCharset(Charset charset) {
		this.charset = charset;
	}

	/**
	 * @return the indent
	 */
	public String getIndent() {
		return indent;
	}

	/**
	 * @param indent the indent to set
	 */
	public void setIndent(String indent) {
		this.indent = indent;
	}

	/**
	 * The Connector will reconnect when the server becomes available again. It
	 * does this by attempting to open a new connection every
	 * <code>reconnectionDelay</code> milliseconds.
	 * 
	 * <p>
	 * It stops trying whenever a connection is established. It will restart to
	 * try reconnect to the server when previously open connection is dropped.
	 * 
	 * @author Ceki G&uuml;lc&uuml;
	 * @since 0.8.4
	 */
	class Connector extends Thread {
		
		boolean interrupted = false;
		
		public void run() {
			Socket socket;
			while (!interrupted) {
				try {
					sleep(reconnectionDelay);
					LogLog.debug("Attempting connection to " + address.getHostName());
					socket = new Socket(address, port);
					synchronized (this) {
						out = new PrintStream(socket.getOutputStream());
						register();
						connector = null;
						LogLog.debug("Connection established. Exiting connector thread.");
						break;
					}
				}
				catch (InterruptedException e) {
					LogLog.debug("Connector interrupted. Leaving loop.");
					return;
				}
				catch (java.net.ConnectException e) {
					LogLog.debug("Remote host " + address.getHostName() + " refused connection.");
				}
				catch (IOException e) {
					if (e instanceof InterruptedIOException) {
						Thread.currentThread().interrupt();
					}
					LogLog.debug("Could not connect to " + address.getHostName() + ". Exception is " + e);
				}
			}
		}
	}
}
