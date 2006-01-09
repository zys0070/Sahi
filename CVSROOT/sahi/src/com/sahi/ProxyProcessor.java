package com.sahi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.logging.Logger;

import javax.net.ssl.SSLSocket;

import com.sahi.config.Configuration;
import com.sahi.playback.FileScript;
import com.sahi.playback.SahiScript;
import com.sahi.playback.ScriptUtil;
import com.sahi.playback.URLScript;
import com.sahi.playback.log.LogFileConsolidator;
import com.sahi.processor.SuiteProcessor;
import com.sahi.request.HttpRequest;
import com.sahi.response.HttpFileResponse;
import com.sahi.response.HttpModifiedResponse;
import com.sahi.response.HttpResponse;
import com.sahi.response.NoCacheHttpResponse;
import com.sahi.response.SimpleHttpResponse;
import com.sahi.session.Session;
import com.sahi.test.SahiTestSuite;

/**
 * User: nraman Date: May 13, 2005 Time: 7:06:11 PM To
 */
public class ProxyProcessor implements Runnable {
	private Socket client;

	private SuiteProcessor suiteProcessor = new SuiteProcessor();

	private boolean isSSLSocket = false;

	private static Logger logger = Configuration
			.getLogger("com.sahi.ProxyProcessor");

	private static boolean externalProxyEnabled = Configuration
			.isExternalProxyEnabled();;

	private static String externalProxyHost = null;

	private static int externalProxyPort = 80;

	static {
		if (externalProxyEnabled) {
			externalProxyHost = Configuration.getExternalProxyHost();
			externalProxyPort = Configuration.getExternalProxyPort();
			logger.config("External Proxy is enabled for Host:"
					+ externalProxyHost + " and Port:" + externalProxyPort);
		} else {
			logger.config("External Proxy is disabled");
		}
	}

	public ProxyProcessor(Socket client) {
		this.client = client;
		isSSLSocket = (client instanceof SSLSocket);
	}

	public void run() {
		try {
			HttpRequest requestFromBrowser = getRequestFromBrowser();
			String uri = requestFromBrowser.uri();
			if (uri != null) {
				if (uri.indexOf("/_s_/") != -1) {
					processLocally(uri, requestFromBrowser);
				} else {
					if (isHostTheProxy(requestFromBrowser.host())
							&& requestFromBrowser.port() == Configuration
									.getPort()) {
						processLocally(uri, requestFromBrowser);
					} else if (uri.indexOf("favicon.ico") == -1) {
						processAsProxy(requestFromBrowser);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			logger.warning(e.getMessage());
		} finally {
			try {
				client.close();
			} catch (IOException e) {
				logger.warning(e.getMessage());
			}
		}
	}

	private boolean isHostTheProxy(String host) throws UnknownHostException {
		return InetAddress.getByName(host).getHostAddress().equals(
				InetAddress.getLocalHost().getHostAddress())
				|| InetAddress.getByName(host).getHostAddress().equals(
						"127.0.0.1");
	}

	private void processAsProxy(HttpRequest requestFromBrowser)
			throws IOException {
		if (requestFromBrowser.isConnect()) {
			processConnect(requestFromBrowser);
		} else if (requestFromBrowser.isSSL()) {
			processHttp(requestFromBrowser);
		} else {
			processHttp(requestFromBrowser);
		}
	}

	private void processConnect(HttpRequest requestFromBrowser) {
		try {
			client.getOutputStream().write(("HTTP/1.0 200 Ok\r\n\r\n").getBytes());
			SSLSocket sslSocket = new SSLHelper()
					.convertToSecureServerSocket(client);
			ProxyProcessor delegatedProcessor = new ProxyProcessor(sslSocket);
			delegatedProcessor.run();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void processHttp(HttpRequest requestFromBrowser)
			throws IOException, SocketException {
		logger.finest("### Type of socket is " + client.getClass().getName());
		Socket socketToHost = getSocketToHost(requestFromBrowser);
		socketToHost.setSoTimeout(120000);
		OutputStream outputStreamToHost = socketToHost.getOutputStream();
		InputStream inputStreamFromHost = socketToHost.getInputStream();
		HttpResponse responseFromHost = getResponseFromHost(
				inputStreamFromHost, outputStreamToHost, requestFromBrowser.modifyForFetch());
		sendResponseToBrowser(responseFromHost);
		socketToHost.close();
	}

	private void processLocally(String uri, HttpRequest requestFromBrowser)
			throws IOException {
		if (uri.indexOf("/dyn/") != -1) {
			// System.out.println(uri);
			Session session = getSession(requestFromBrowser);
//			System.out.println("----------- "+session.id());
			if (uri.indexOf("/log") != -1) {
				if (session.getScript() != null) {
					session.logPlayBack(requestFromBrowser.getParameter("msg"),
							requestFromBrowser.getParameter("type"),
							requestFromBrowser.getParameter("debugInfo"));
				}
				sendResponseToBrowser(new NoCacheHttpResponse(""));
			} else if (uri.indexOf("/setscriptfile") != -1) {
				String fileName = URLDecoder.decode(requestFromBrowser
						.getParameter("file"), "UTF8");
				session.setScript(new FileScript(
						getScriptFileWithPath(fileName)));
				sendWindowCloseResponse(session);
			} else if (uri.indexOf("/setscripturl") != -1) {
				String url = URLDecoder.decode(requestFromBrowser
						.getParameter("url"), "UTF8");
				session.setScript(new URLScript(url));
				sendWindowCloseResponse(session);
			} else if (uri.indexOf("/recordstart") != -1) {
//				System.out.println("########### "+session.id());
				startRecorder(requestFromBrowser, session);
				sendWindowCloseResponse(session);
			} else if (uri.indexOf("/recordstop") != -1) {
				session.getRecorder().stop();
				sendWindowCloseResponse(session);
			} else if (uri.indexOf("/record") != -1) {
				session.getRecorder().record(
						requestFromBrowser.getParameter("event"),
						requestFromBrowser.getParameter("accessor"),
						requestFromBrowser.getParameter("value"),
						requestFromBrowser.getParameter("type"),
						requestFromBrowser.getParameter("shorthand"),
						requestFromBrowser.getParameter("popup"));
				sendResponseToBrowser(new NoCacheHttpResponse(""));
			} else if (uri.indexOf("/scriptslist") != -1) {
				sendResponseToBrowser(new NoCacheHttpResponse(ScriptUtil
						.getScriptsJs(getScriptName(session))));
			} else if (uri.indexOf("/script") != -1) {
				String s = (session.getScript() != null) ? session.getScript()
						.modifiedScript() : "";
				sendResponseToBrowser(new NoCacheHttpResponse(s));
			} else if (uri.indexOf("/winclosed") != -1) {
				session.setIsWindowOpen(false);
				sendResponseToBrowser(new NoCacheHttpResponse(""));
			} else if (uri.indexOf("/winopen") != -1) {
				session.setIsWindowOpen(true);
				sendResponseToBrowser(new NoCacheHttpResponse(""));
			} else if (uri.indexOf("/state") != -1) {
				sendResponseToBrowser(proxyStateResponse(session));
			} else if (uri.indexOf("/auto") != -1) {
				String fileName = URLDecoder.decode(requestFromBrowser
						.getParameter("file"), "UTF8");
				session.setScript(new FileScript(
						getScriptFileWithPath(fileName)));
				String startUrl = URLDecoder.decode(requestFromBrowser
						.getParameter("startUrl"), "UTF8");
				session.startPlayBack();
				sendResponseToBrowser(proxyAutoResponse(startUrl, session.id()));
			} else if (uri.indexOf("/setvar") != -1) {
				String name = requestFromBrowser.getParameter("name");
				String value = requestFromBrowser.getParameter("value");
				session.setVariable(name, value);
				sendResponseToBrowser(new NoCacheHttpResponse(""));
			} else if (uri.indexOf("/getvar") != -1) {
				String name = requestFromBrowser.getParameter("name");
				String value = session.getVariable(name);
				sendResponseToBrowser(new NoCacheHttpResponse(value != null
						? value
						: "null"));
			} else if (uri.indexOf("/startplay") != -1) {
				if (session.getScript() != null)
					session.startPlayBack();
				session.setVariable("sahi_play", "1");
				session.setVariable("sahiIx", requestFromBrowser.getParameter("step"));
				sendWindowCloseResponse(session);
			} else if (uri.indexOf("/stopplay") != -1) {
				sendResponseToBrowser(new NoCacheHttpResponse(""));
				stopPlay(session);
			} else if (uri.indexOf("/startsuite") != -1) {
				suiteProcessor.startSuite(requestFromBrowser, session);
				sendResponseToBrowser(new NoCacheHttpResponse(""));
			} else if (uri.indexOf("/getSuiteStatus") != -1) {
				SahiTestSuite suite = SahiTestSuite.getSuite(session.id());
				String status = "NONE";
				if (suite != null) {
					status = session.getPlayBackStatus();
				}
				sendResponseToBrowser(new NoCacheHttpResponse(status));
			} else if (uri.indexOf("/stopserver") != -1) {
				System.exit(1);
			} else if (uri.indexOf("/getSahiScript") != -1) {
				String code = requestFromBrowser.getParameter("code");
				sendResponseToBrowser(new NoCacheHttpResponse(SahiScript
						.modifyFunctionNames(code)));
			} else if (uri.indexOf("/alert.htm") != -1) {
				String msg = requestFromBrowser.getParameter("msg");
				sendResponseToBrowser(proxyAlertResponse(msg));
			} else if (uri.indexOf("/confirm.htm") != -1) {
				String msg = requestFromBrowser.getParameter("msg");
				sendResponseToBrowser(proxyConfirmResponse(msg));
			} else if (uri.indexOf("/sleep") != -1) {
				long millis = 1000;
				try {
					millis = Long.parseLong(requestFromBrowser
							.getParameter("ms"));
				} catch (Exception e) {
				}
				try {
					Thread.sleep(millis);
				} catch (Exception e) {
				}
				sendResponseToBrowser(new SimpleHttpResponse(""));
			}
		} else if (uri.indexOf("/scripts/") != -1) {
			String fileName = scriptFileNamefromURI(requestFromBrowser.uri());
			sendResponseToBrowser(new HttpFileResponse(fileName));
		} else if (uri.indexOf("/logs/") != -1 || uri.endsWith("/logs")) {
			String fileName = logFileNamefromURI(requestFromBrowser.uri());
			if ("".equals(fileName))
				sendResponseToBrowser(new NoCacheHttpResponse(getLogsList()));
			else
				sendResponseToBrowser(new HttpFileResponse(fileName));
		} else if (uri.indexOf("/spr/") != -1) {
			String fileName = fileNamefromURI(requestFromBrowser.uri());
			sendResponseToBrowser(new HttpFileResponse(fileName));
		} else {
			sendResponseToBrowser(new SimpleHttpResponse(
					"<html><h2>You have reached the Sahi proxy.</h2></html>"));
		}
	}

	private void sendWindowCloseResponse(Session session) throws IOException {
		HttpResponse httpResponse = new HttpFileResponse(Configuration.getHtdocsRoot()
				+ "spr/close.htm");
		sendResponseToBrowser(addSahisidCookie(httpResponse, session));
	}

	private HttpResponse addSahisidCookie(HttpResponse httpResponse, Session session) {
		httpResponse.addHeader("Set-Cookie", "sahisid="+session.id()+"; path=/; ");
		httpResponse.setRawHeaders(httpResponse.getRebuiltHeaderBytes());
		return httpResponse;
	}

	private void stopPlay(Session session) {
		if (session.getScript() != null)
			session.stopPlayBack();
		SahiTestSuite suite = SahiTestSuite.getSuite(session.id());
		if (suite != null) {
			suite.stop(session.getScript().getScriptName());
			waitASec();
			if (!suite.executeNext())
				consolidateLogs(session.getSuiteLogDir());
		} else {
			consolidateLogs(session.getScriptLogFile());
		}
	}

	private void waitASec() {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void consolidateLogs(String consolidateBy) {
		try {
			new LogFileConsolidator(consolidateBy).summarize();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private String getLogsList() {
		return LogFileConsolidator.getLogsList();
	}

	private String getScriptName(Session session) {
		SahiScript script = session.getScript();
		if (script == null)
			return "";
		return script.getScriptName();
	}

	private HttpFileResponse proxyAutoResponse(String startUrl, String sessionId) {
		Properties props = new Properties();
		props.setProperty("startUrl", startUrl);
		props.setProperty("sessionId", sessionId);
		return new HttpFileResponse(Configuration.getHtdocsRoot()
				+ "spr/auto.htm", props);
	}

	private HttpFileResponse proxyAlertResponse(String msg) {
		Properties props = new Properties();
		props.setProperty("msg", msg);
		return new HttpFileResponse(Configuration.getHtdocsRoot()
				+ "spr/alert.htm", props);
	}

	private HttpFileResponse proxyConfirmResponse(String msg) {
		Properties props = new Properties();
		props.setProperty("msg", msg);
		return new HttpFileResponse(Configuration.getHtdocsRoot()
				+ "spr/confirm.htm", props);
	}

	private HttpFileResponse proxyStateResponse(Session session) {
		Properties props = new Properties();
		props.setProperty("sessionId", session.id());
		props.setProperty("isRecording", "" + session.isRecording());
		props.setProperty("isWindowOpen", "" + session.isWindowOpen());
		return new HttpFileResponse(Configuration.getHtdocsRoot()
				+ "spr/state.js", props);
	}

	private Session getSession(HttpRequest requestFromBrowser) {
		String sessionId = null;
		if (Configuration.isMultiDomainEnabled()) {
			sessionId = "sahi_abc";
		} else {
			sessionId = requestFromBrowser.getParameter("sahisid");
		}
//		System.out.println("1:"+sessionId);
		if (isBlankOrNull(sessionId))
			sessionId = requestFromBrowser.getCookie(new String("sahisid"));
		if (isBlankOrNull(sessionId))
			sessionId = "sahi_" + System.currentTimeMillis();
//		System.out.println("2:"+sessionId);
		return Session.getInstance(sessionId);
	}

	private boolean isBlankOrNull(String s) {
		return (s == null || "".equals(s));
	}

	private String scriptFileNamefromURI(String uri) {
		StringBuffer sb = new StringBuffer();
		sb.append(Configuration.getScriptRoot());
		sb.append(uri.substring(uri.lastIndexOf("/scripts/") + 9));
		return sb.toString();
	}

	private String logFileNamefromURI(String uri) {
		String fileName = uri.substring(uri.indexOf("/logs/") + 6);
		if ("".equals(fileName))
			return "";
		StringBuffer sb = new StringBuffer();
		sb.append(Configuration.getPlayBackLogsRoot());
		sb.append(fileName);
		return sb.toString();
	}

	private String fileNamefromURI(String uri) {
		StringBuffer sb = new StringBuffer();
		sb.append(Configuration.getHtdocsRoot());
		sb.append(uri.substring(uri.indexOf("_s_/") + 4));
		return sb.toString();
	}

	private String getScriptFileWithPath(String fileName) {
		if (!fileName.endsWith(".sah"))
			fileName = fileName + ".sah";
		return Configuration.getScriptRoot() + fileName;
	}

	private void startRecorder(HttpRequest request, Session session) {
		String fileName = request.getParameter("file");
		session.getRecorder()
				.start(getScriptFileWithPath(fileName));
		session.setVariable("sahi_record", "1");
//		System.out.println("$$$$$$$$$$$ "+session.id());
	}

	private HttpResponse getResponseFromHost(InputStream inputStreamFromHost,
			OutputStream outputStreamToHost, HttpRequest request)
			throws IOException {
		logger.finest(request.uri());
		logger.finest(new String(request.rawHeaders()));
		outputStreamToHost.write(request.rawHeaders());
		if (request.isPost())
			outputStreamToHost.write(request.data());
		outputStreamToHost.flush();
		HttpModifiedResponse modifiedResponse = new HttpModifiedResponse(
				inputStreamFromHost);
		logger.finest(new String(modifiedResponse.rawHeaders()));
		return modifiedResponse;
	}

	private Socket getSocketToHost(HttpRequest request) throws IOException {
		InetAddress addr = InetAddress.getByName(request.host());
		if (request.isSSL()) {
			return new SSLHelper().getSocket(request, addr);
		} else {
			if (externalProxyEnabled) {
				return new Socket(externalProxyHost, externalProxyPort);
			}
			return new Socket(addr, request.port());
		}
	}

	private HttpRequest getRequestFromBrowser() throws IOException {
		InputStream in = client.getInputStream();
		return new HttpRequest(in, isSSLSocket);
	}

	protected void sendResponseToBrowser(HttpResponse responseFromHost)
			throws IOException {
		OutputStream outputStreamToBrowser = client.getOutputStream();
		logger.fine(new String(responseFromHost.rawHeaders()));
		outputStreamToBrowser.write(responseFromHost.rawHeaders());
		outputStreamToBrowser.write(responseFromHost.data());
	}

	protected Socket client() {
		return client;
	}
}
