package com.samblake.log4j;

import java.io.IOException;

import org.apache.log4j.Level;
import org.apache.log4j.LogIOAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class LogioTest {
	private static final Logger logger = Logger.getLogger("test");

	private static final String HOST = "localhost";
	private static final int PORT = 28777;
	private static final String NODE = "testNode";
	private static final String STREAM = "testStream";

	@BeforeClass
	public void configureLog4j() throws IOException {
		LogIOAppender appender = new LogIOAppender(HOST, PORT, NODE, STREAM);
		appender.setLayout(new PatternLayout("%d [%p|%c|%C{1}] %m%n"));
		appender.setThreshold(Level.DEBUG);
		appender.activateOptions();
		logger.addAppender(appender);
	}
	
	@AfterClass
	public void resetLog4j() {
		logger.getLoggerRepository().resetConfiguration();
	}

	@Test
	public void test() throws IOException {
		logger.warn("A test message!");
	}
}