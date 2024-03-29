package org.red5.server.net.rtmpt;

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

import java.io.IOException;
import java.util.List;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.HttpVersion;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.DummySession;
import org.apache.mina.core.session.IoSession;
import org.red5.server.net.protocol.ProtocolState;
import org.red5.server.net.rtmp.RTMPClientConnManager;
import org.red5.server.net.rtmp.RTMPConnection;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.message.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client connector for RTMPT
 * 
 * @author Anton Lebedevich (mabrek@gmail.com)
 */
class RTMPTClientConnector extends Thread {
	private static final Logger log = LoggerFactory.getLogger(RTMPTClientConnector.class);

	private static final String CONTENT_TYPE = "application/x-fcs";

	private static final ByteArrayRequestEntity ZERO_REQUEST_ENTITY = new ByteArrayRequestEntity(new byte[] { 0 }, CONTENT_TYPE);

	/**
	 * Size to split messages queue by, borrowed from
	 * RTMPTServlet.RESPONSE_TARGET_SIZE
	 */
	private static final int SEND_TARGET_SIZE = 32768;

	// connection timeout
	private static int connectionTimeout = 7000; // 7 seconds

	private final HttpClient httpClient = new HttpClient();

	private final RTMPTClient client;

	private final RTMPClientConnManager connManager;

	private int clientId;

	private long messageCount = 1;

	private volatile boolean stopRequested = false;

	public RTMPTClientConnector(String server, int port, RTMPTClient client) {
		httpClient.getHostConfiguration().setHost(server, port);
		httpClient.getHttpConnectionManager().closeIdleConnections(0);

		HttpClientParams params = new HttpClientParams();
		params.setVersion(HttpVersion.HTTP_1_1);
		httpClient.setParams(params);
		// establish a connection within x seconds - this will prevent hung sockets
		httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(connectionTimeout);

		this.client = client;
		this.connManager = RTMPClientConnManager.getInstance();
	}

	public void run() {
		try {
			RTMPTClientConnection connection = openConnection();

			while (!connection.isClosing() && !stopRequested) {
				IoBuffer toSend = connection.getPendingMessages(SEND_TARGET_SIZE);
				PostMethod post;
				if (toSend != null && toSend.limit() > 0) {
					post = makePost("send");
					post.setRequestEntity(new InputStreamRequestEntity(toSend.asInputStream(), CONTENT_TYPE));
				} else {
					post = makePost("idle");
					post.setRequestEntity(ZERO_REQUEST_ENTITY);
				}
				httpClient.executeMethod(post);

				checkResponseCode(post);

				byte[] received = post.getResponseBody();

				IoBuffer data = IoBuffer.allocate(received.length);
				data.put(received);
				data.flip();
				data.skip(1); // XXX: polling interval lies in this byte
				List<?> messages = connection.decode(data);

				if (messages == null || messages.isEmpty()) {
					try {
						// XXX handle polling delay
						Thread.sleep(250);
					} catch (InterruptedException e) {
						if (stopRequested)
							break;
					}
					continue;
				}

				IoSession session = new DummySession();
				session.setAttribute(RTMPConnection.RTMP_CONNECTION_KEY, connection);
				session.setAttribute(ProtocolState.SESSION_KEY, connection.getState());
				for (Object message : messages) {
					try {
						client.messageReceived(message, session);
					} catch (Exception e) {
						log.error("Could not process message.", e);
					}
				}
			}

			finalizeConnection();

			client.connectionClosed(connection, connection.getState());

		} catch (Throwable e) {
			log.debug("RTMPT handling exception", e);
			client.handleException(e);
		}
	}

	private RTMPTClientConnection openConnection() throws IOException {

		PostMethod openPost = new PostMethod("/open/1");
		setCommonHeaders(openPost);
		openPost.setRequestEntity(ZERO_REQUEST_ENTITY);

		httpClient.executeMethod(openPost);

		checkResponseCode(openPost);

		String response = openPost.getResponseBodyAsString();
		clientId = Integer.parseInt(response.substring(0, response.length() - 1));
		log.debug("Got client id {}", clientId);

		RTMPTClientConnection connection = (RTMPTClientConnection) connManager.createConnection(RTMPTClientConnection.class);

		RTMP state = new RTMP(RTMP.MODE_CLIENT);
		connection.setState(state);

		connection.setHandler(client);
		connection.setDecoder(client.getCodecFactory().getRTMPDecoder());
		connection.setEncoder(client.getCodecFactory().getRTMPEncoder());

		log.debug("Handshake 1st phase");
		IoBuffer handshake = IoBuffer.allocate(Constants.HANDSHAKE_SIZE + 1);
		handshake.put((byte) 0x03);
		handshake.fill((byte) 0x01, Constants.HANDSHAKE_SIZE);
		handshake.flip();
		connection.rawWrite(handshake);

		return connection;
	}

	private void finalizeConnection() throws IOException {
		log.debug("Sending close post");
		PostMethod closePost = new PostMethod(makeUrl("close"));
		closePost.setRequestEntity(ZERO_REQUEST_ENTITY);
		httpClient.executeMethod(closePost);
		closePost.getResponseBody();
	}

	private PostMethod makePost(String command) {
		PostMethod post = new PostMethod(makeUrl(command));
		setCommonHeaders(post);
		return post;
	}

	private String makeUrl(String command) {
		// use message count from connection
		return new StringBuilder().append('/').append(command).append('/').append(clientId).append('/').append(messageCount++).toString();
	}

	private void setCommonHeaders(PostMethod post) {
		post.setRequestHeader("Connection", "Keep-Alive");
		post.setRequestHeader("Cache-Control", "no-cache");
	}

	private void checkResponseCode(HttpMethod method) {
		if (method.getStatusCode() != HttpStatus.SC_OK) {
			try {
				String body = method.getResponseBodyAsString();
				throw new RuntimeException("Bad HTTP status returned, line: " + method.getStatusLine() + "; body: " + body);
			} catch (IOException e) {
				throw new RuntimeException("Bad HTTP status returned, line: " + method.getStatusLine() + "; in addition IOException occured on reading body", e);
			}
		}
	}

	public void setStopRequested(boolean stopRequested) {
		this.stopRequested = stopRequested;
	}
}
