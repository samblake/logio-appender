package com.samblake.log4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.StringContainsInOrder.stringContainsInOrder;

public class AppenderTest {
	private static final Logger logger = Logger.getLogger("test");
	
	private static final String SERVER = "localhost";
	private static final int PORT = LogIOAppender.DEFAULT_PORT;
	private static final String STREAM = "testStream";
	private static final String NODE = "testNode";
	
	private ServerSocket serverSocket;
	private BufferedReader in;

	private LogIOAppender appender;
	
	@BeforeClass
	public void setUp() throws IOException {
		new Server().start();
		
		appender = new LogIOAppender(SERVER, PORT, NODE, STREAM);
		appender.setLayout(new PatternLayout("%d [%p|%c|%C{1}] %m%n"));
		appender.setThreshold(Level.DEBUG);
		appender.activateOptions();
		logger.addAppender(appender);
	}
	
	@AfterClass
	private void stopServer() throws IOException {
		if (in != null)
			in.close();
		if (serverSocket != null)
			serverSocket.close();
	}

	@AfterClass
	public void resetLog4j() {
		logger.getLoggerRepository().resetConfiguration();
	}
	
	@Test
	public void test() throws IOException {
		String[] messages = {"Testing", "More..."};
		
		try {
			npe();
		}
		catch (Exception e) {
			logger.error("Oh no!", e);
		}
		
		for (String message : messages) {
			logger.info(message);
		}
		appender.close();
		
		validate(messages, true);
	}
	
	@SuppressWarnings("null")
	private void npe() {
		Object o = null;
		o.hashCode();
	}

	private void validate(String[] messages, boolean includesStackTrace) throws IOException {
		List<Iterable<String>> checks = createTestComparisonStrings(messages);
		
		boolean stackTraceFound = false;
		int count = 0;
		String inputLine;
		Iterator<Iterable<String>> it = checks.iterator();
		while ((inputLine = in.readLine()) != null) {
			Assert.assertNotNull(inputLine); 
			if (inputLine.contains("    ")) {
				stackTraceFound = true;
				continue;
			}
			
			count++;
			assertThat(inputLine, stringContainsInOrder(it.next()));

			if (inputLine.startsWith("-node"))
				break;
		}
		
		Assert.assertEquals(stackTraceFound, includesStackTrace);
		Assert.assertEquals(count, checks.size());
	}

	@SuppressWarnings("serial")
	private List<Iterable<String>> createTestComparisonStrings(String[] messages) {
		List<Iterable<String>> checks = new LinkedList<Iterable<String>>();
		checks.add(new LinkedList<String>() {{add("+node|testNode|testStream");}});
		checks.add(new LinkedList<String>() {{add("+log|testStream|testNode|error|"); add("Oh no!");}});
		checks.add(new LinkedList<String>() {{add("java.lang.NullPointerException");}});
		for (final String message : messages) {
			checks.add(new LinkedList<String>() {{
				add("+log|testStream|testNode|");
				add(message);
			}});
		}
		checks.add(new LinkedList<String>() {{add("-node|testNode");}});
		return checks;
	}

	class Server extends Thread {
		
		@Override
		public void run() {
			ServerSocket serverSocket;
			try {
				serverSocket = new ServerSocket(PORT);
				Socket clientSocket = serverSocket.accept();
				in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
