package org.red5.server.net.rtmp;

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

import static org.red5.server.api.ScopeUtils.getScopeService;

import java.util.HashMap;
import java.util.Map;

import org.red5.logging.Red5LoggerFactory;
import org.red5.server.api.IContext;
import org.red5.server.api.IGlobalScope;
import org.red5.server.api.IScope;
import org.red5.server.api.IScopeHandler;
import org.red5.server.api.IServer;
import org.red5.server.api.Red5;
import org.red5.server.api.ScopeUtils;
import org.red5.server.api.IConnection.Encoding;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IServiceCall;
import org.red5.server.api.so.ISharedObject;
import org.red5.server.api.so.ISharedObjectSecurity;
import org.red5.server.api.so.ISharedObjectSecurityService;
import org.red5.server.api.so.ISharedObjectService;
import org.red5.server.api.stream.IClientBroadcastStream;
import org.red5.server.api.stream.IClientStream;
import org.red5.server.api.stream.IStreamService;
import org.red5.server.exception.ClientRejectedException;
import org.red5.server.exception.ScopeNotFoundException;
import org.red5.server.exception.ScopeShuttingDownException;
import org.red5.server.messaging.IConsumer;
import org.red5.server.messaging.OOBControlMessage;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.event.ChunkSize;
import org.red5.server.net.rtmp.event.Invoke;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.event.Ping;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.net.rtmp.message.StreamAction;
import org.red5.server.net.rtmp.status.Status;
import org.red5.server.net.rtmp.status.StatusObject;
import org.red5.server.net.rtmp.status.StatusObjectService;
import org.red5.server.service.Call;
import org.red5.server.so.ISharedObjectEvent;
import org.red5.server.so.SharedObjectEvent;
import org.red5.server.so.SharedObjectMessage;
import org.red5.server.so.SharedObjectService;
import org.red5.server.stream.IBroadcastScope;
import org.red5.server.stream.StreamService;
import org.slf4j.Logger;

/**
 * RTMP events handler.
 */
public class RTMPHandler extends BaseRTMPHandler {

	/**
	 * Logger
	 */
	protected static Logger log = Red5LoggerFactory.getLogger(RTMPHandler.class);

	/**
	 * Status object service.
	 */
	protected StatusObjectService statusObjectService;

	/**
	 * Red5 server instance.
	 */
	protected IServer server;

	/**
	 * Whether or not global scope connections are allowed.
	 */
	private boolean globalScopeConnectionAllowed = false;

	/**
	 * Setter for server object.
	 * 
	 * @param server Red5 server instance
	 */
	public void setServer(IServer server) {
		this.server = server;
	}

	/**
	 * Setter for status object service.
	 * 
	 * @param statusObjectService Status object service.
	 */
	public void setStatusObjectService(StatusObjectService statusObjectService) {
		this.statusObjectService = statusObjectService;
	}

	public boolean isGlobalScopeConnectionAllowed() {
		return globalScopeConnectionAllowed;
	}

	public void setGlobalScopeConnectionAllowed(boolean globalScopeConnectionAllowed) {
		this.globalScopeConnectionAllowed = globalScopeConnectionAllowed;
	}

	/** {@inheritDoc} */
	@Override
	protected void onChunkSize(RTMPConnection conn, Channel channel, Header source, ChunkSize chunkSize) {
		for (IClientStream stream : conn.getStreams()) {
			if (stream instanceof IClientBroadcastStream) {
				IClientBroadcastStream bs = (IClientBroadcastStream) stream;
				IBroadcastScope scope = (IBroadcastScope) bs.getScope().getBasicScope(IBroadcastScope.TYPE, bs.getPublishedName());
				if (scope == null) {
					continue;
				}

				OOBControlMessage setChunkSize = new OOBControlMessage();
				setChunkSize.setTarget("ClientBroadcastStream");
				setChunkSize.setServiceName("chunkSize");
				if (setChunkSize.getServiceParamMap() == null) {
					setChunkSize.setServiceParamMap(new HashMap<String, Object>());
				}
				setChunkSize.getServiceParamMap().put("chunkSize", chunkSize.getSize());
				scope.sendOOBControlMessage((IConsumer) null, setChunkSize);
				log.debug("Sending chunksize {} to {}", chunkSize, bs.getProvider());
			}
		}
	}

	/**
	 * Remoting call invocation handler.
	 * 
	 * @param conn	RTMP connection
	 * @param call	Service call
	 */
	protected void invokeCall(RTMPConnection conn, IServiceCall call) {
		final IScope scope = conn.getScope();
		if (scope.hasHandler()) {
			final IScopeHandler handler = scope.getHandler();
			log.debug("Scope: {} handler: {}", scope, handler);
			if (!handler.serviceCall(conn, call)) {
				// XXX: What to do here? Return an error?
				return;
			}
		}

		final IContext context = scope.getContext();
		log.debug("Context: {}", context);
		context.getServiceInvoker().invoke(call, scope);
	}

	/**
	 * Remoting call invocation handler.
	 * 
	 * @param conn
	 *            RTMP connection
	 * @param call
	 *            Service call
	 * @param service
	 *            Server-side service object
	 * @return <code>true</code> if the call was performed, otherwise
	 *         <code>false</code>
	 */
	private boolean invokeCall(RTMPConnection conn, IServiceCall call, Object service) {
		final IScope scope = conn.getScope();
		final IContext context = scope.getContext();
		log.debug("Scope: {} Context: {}", scope, context);
		log.debug("Service: {}", service);
		return context.getServiceInvoker().invoke(call, service);
	}

	/** {@inheritDoc} */
	@SuppressWarnings({ "unchecked" })
	@Override
	protected void onInvoke(RTMPConnection conn, Channel channel, Header source, Notify invoke, RTMP rtmp) {
		log.debug("Invoke: {}", invoke);
		// Get call
		final IServiceCall call = invoke.getCall();
		// method name
		final String action = call.getServiceMethodName();

		// If it's a callback for server remote call then pass it over to
		// callbacks handler and return
		if ("_result".equals(action) || "_error".equals(action)) {
			handlePendingCallResult(conn, invoke);
			return;
		}

		boolean disconnectOnReturn = false;

		// If this is not a service call then handle connection...
		if (call.getServiceName() == null) {
			log.debug("call: {}", call);
			if (!conn.isConnected() && StreamAction.CONNECT.equals(action)) {
				// Handle connection
				log.debug("connect");
				// Get parameters passed from client to
				// NetConnection#connection
				final Map<String, Object> params = invoke.getConnectionParams();
				// Get hostname
				String host = getHostname((String) params.get("tcUrl"));
				// Check up port
				if (host.endsWith(":1935")) {
					// Remove default port from connection string
					host = host.substring(0, host.length() - 5);
				}
				// App name as path, but without query string if there is
				// one
				String path = (String) params.get("app");
				if (path.indexOf("?") != -1) {
					int idx = path.indexOf("?");
					params.put("queryString", path.substring(idx));
					path = path.substring(0, idx);
				}
				params.put("path", path);

				final String sessionId = null;
				conn.setup(host, path, sessionId, params);
				try {
					// Lookup server scope when connected
					// Use host and application name
					IGlobalScope global = server.lookupGlobal(host, path);
					log.trace("Global lookup result: {}", global);
					if (global != null) {
						final IContext context = global.getContext();
						IScope scope = null;
						try {
							scope = context.resolveScope(global, path);
							//if global scope connection is not allowed, reject
							if (scope.getDepth() < 1 && !globalScopeConnectionAllowed) {
								call.setStatus(Call.STATUS_ACCESS_DENIED);
								if (call instanceof IPendingServiceCall) {
									IPendingServiceCall pc = (IPendingServiceCall) call;
									StatusObject status = getStatus(NC_CONNECT_REJECTED);
									status.setDescription("Global scope connection disallowed on this server.");
									pc.setResult(status);
								}
								disconnectOnReturn = true;
							}
						} catch (ScopeNotFoundException err) {
							call.setStatus(Call.STATUS_SERVICE_NOT_FOUND);
							if (call instanceof IPendingServiceCall) {
								StatusObject status = getStatus(NC_CONNECT_REJECTED);
								status.setDescription(String.format("No scope '%s' on this server.", path));
								((IPendingServiceCall) call).setResult(status);
							}
							log.info("Scope {} not found on {}", path, host);
							disconnectOnReturn = true;
						} catch (ScopeShuttingDownException err) {
							call.setStatus(Call.STATUS_APP_SHUTTING_DOWN);
							if (call instanceof IPendingServiceCall) {
								StatusObject status = getStatus(NC_CONNECT_APPSHUTDOWN);
								status.setDescription(String.format("Application at '%s' is currently shutting down.", path));
								((IPendingServiceCall) call).setResult(status);
							}
							log.info("Application at {} currently shutting down on {}", path, host);
							disconnectOnReturn = true;
						}
						if (scope != null) {
							log.info("Connecting to: {}", scope);
							boolean okayToConnect;
							try {
								log.debug("Conn {}, scope {}, call {}", new Object[] { conn, scope, call });
								log.debug("Call args {}", call.getArguments());
								if (call.getArguments() != null) {
									okayToConnect = conn.connect(scope, call.getArguments());
								} else {
									okayToConnect = conn.connect(scope);
								}
								if (okayToConnect) {
									log.debug("Connected - Client: {}", conn.getClient());
									call.setStatus(Call.STATUS_SUCCESS_RESULT);
									if (call instanceof IPendingServiceCall) {
										IPendingServiceCall pc = (IPendingServiceCall) call;
										//send fmsver and capabilities
										StatusObject result = getStatus(NC_CONNECT_SUCCESS);
										result.setAdditional("fmsVer", Red5.getFMSVersion());
										result.setAdditional("capabilities", Integer.valueOf(31));
										result.setAdditional("mode", Integer.valueOf(1));
										result.setAdditional("data", Red5.getDataVersion());
										pc.setResult(result);
									}
									// Measure initial roundtrip time after connecting
									conn.ping(new Ping(Ping.STREAM_BEGIN, 0, -1));
									conn.startRoundTripMeasurement();
								} else {
									log.debug("Connect failed");
									call.setStatus(Call.STATUS_ACCESS_DENIED);
									if (call instanceof IPendingServiceCall) {
										IPendingServiceCall pc = (IPendingServiceCall) call;
										pc.setResult(getStatus(NC_CONNECT_REJECTED));
									}
									disconnectOnReturn = true;
								}
							} catch (ClientRejectedException rejected) {
								log.debug("Connect rejected");
								call.setStatus(Call.STATUS_ACCESS_DENIED);
								if (call instanceof IPendingServiceCall) {
									IPendingServiceCall pc = (IPendingServiceCall) call;
									StatusObject status = getStatus(NC_CONNECT_REJECTED);
									Object reason = rejected.getReason();
									if (reason != null) {
										status.setApplication(reason);
										//should we set description?
										status.setDescription(reason.toString());
									}
									pc.setResult(status);
								}
								disconnectOnReturn = true;
							}
						}
					} else {
						call.setStatus(Call.STATUS_SERVICE_NOT_FOUND);
						if (call instanceof IPendingServiceCall) {
							StatusObject status = getStatus(NC_CONNECT_INVALID_APPLICATION);
							status.setDescription(String.format("No scope '%s' on this server.", path));
							((IPendingServiceCall) call).setResult(status);
						}
						log.info("No application scope found for {} on host {}", path, host);
						disconnectOnReturn = true;
					}
				} catch (RuntimeException e) {
					call.setStatus(Call.STATUS_GENERAL_EXCEPTION);
					if (call instanceof IPendingServiceCall) {
						IPendingServiceCall pc = (IPendingServiceCall) call;
						pc.setResult(getStatus(NC_CONNECT_FAILED));
					}
					log.error("Error connecting {}", e);
					disconnectOnReturn = true;
				}

				// Evaluate request for AMF3 encoding
				if (Integer.valueOf(3).equals(params.get("objectEncoding")) && call instanceof IPendingServiceCall) {
					Object pcResult = ((IPendingServiceCall) call).getResult();
					Map<String, Object> result;
					if (pcResult instanceof Map) {
						result = (Map<String, Object>) pcResult;
						result.put("objectEncoding", 3);
					} else if (pcResult instanceof StatusObject) {
						result = new HashMap<String, Object>();
						StatusObject status = (StatusObject) pcResult;
						result.put("code", status.getCode());
						result.put("description", status.getDescription());
						result.put("application", status.getApplication());
						result.put("level", status.getLevel());
						result.put("objectEncoding", 3);
						((IPendingServiceCall) call).setResult(result);
					}

					rtmp.setEncoding(Encoding.AMF3);
				}
			} else {
				//log.debug("Enum value of: {}", StreamAction.getEnum(action));
				StreamAction streamAction = StreamAction.getEnum(action);
				//if the "stream" action is not predefined a custom type will be returned
				switch (streamAction) {
					case DISCONNECT:
						conn.close();
						break;
					case CREATE_STREAM:
					case INIT_STREAM:
					case CLOSE_STREAM:
					case RELEASE_STREAM:
					case DELETE_STREAM:
					case PUBLISH:
					case PLAY:
					case SEEK:
					case PAUSE:
					case PAUSE_RAW:
					case RECEIVE_VIDEO:
					case RECEIVE_AUDIO:
						IStreamService streamService = (IStreamService) getScopeService(conn.getScope(), IStreamService.class, StreamService.class);
						Status status = null;
						try {
							log.debug("Invoking {} from {} with service: {}", new Object[] { call, conn, streamService });
							if (!invokeCall(conn, call, streamService)) {
								status = getStatus(NS_INVALID_ARGUMENT).asStatus();
								status.setDescription(String.format("Failed to %s (stream id: %d)", action, source.getStreamId()));
							}
						} catch (Throwable err) {
							log.error("Error while invoking {} on stream service. {}", action, err);
							status = getStatus(NS_FAILED).asStatus();
							status.setDescription(String.format("Error while invoking %s (stream id: %d)", action, source.getStreamId()));
							status.setDetails(err.getMessage());
						}
						if (status != null) {
							channel.sendStatus(status);
						}
						break;
					default:
						invokeCall(conn, call);
				}
			}
		} else if (conn.isConnected()) {
			// Service calls, must be connected.
			invokeCall(conn, call);
		} else {
			// Warn user attempts to call service without being connected
			log.warn("Not connected, closing connection");
			conn.close();
		}

		if (invoke instanceof Invoke) {
			if ((source.getStreamId() != 0) && (call.getStatus() == Call.STATUS_SUCCESS_VOID || call.getStatus() == Call.STATUS_SUCCESS_NULL)) {
				// This fixes a bug in the FP on Intel Macs.
				log.debug("Method does not have return value, do not reply");
				return;
			}

			boolean sendResult = true;
			if (call instanceof IPendingServiceCall) {
				IPendingServiceCall psc = (IPendingServiceCall) call;
				Object result = psc.getResult();
				if (result instanceof DeferredResult) {
					// Remember the deferred result to be sent later
					DeferredResult dr = (DeferredResult) result;
					dr.setServiceCall(psc);
					dr.setChannel(channel);
					dr.setInvokeId(invoke.getInvokeId());
					conn.registerDeferredResult(dr);
					sendResult = false;
				}
			}

			if (sendResult) {
				// The client expects a result for the method call.
				Invoke reply = new Invoke();
				reply.setCall(call);
				reply.setInvokeId(invoke.getInvokeId());
				channel.write(reply);
				if (disconnectOnReturn) {
					conn.close();
				}
			}
		}
	}

	public StatusObject getStatus(String code) {
		return statusObjectService.getStatusObject(code);
	}

	/** {@inheritDoc} */
	@Override
	protected void onPing(RTMPConnection conn, Channel channel, Header source, Ping ping) {
		switch (ping.getEventType()) {
			case Ping.CLIENT_BUFFER:
				IClientStream stream = null;
				//get the stream id
				int streamId = ping.getValue2();
				//get requested buffer size in milliseconds
				int buffer = ping.getValue3();
				if (streamId != 0) {
					// The client wants to set the buffer time
					stream = conn.getStreamById(streamId);
					if (stream != null) {
						stream.setClientBufferDuration(buffer);
						log.debug("Client sent a buffer size: {} ms for stream id: {}", buffer, streamId);
						log.trace("Stream type: {}", stream.getClass().getName());
					}
				}
				//catch-all to make sure buffer size is set
				if (stream == null) {
					// Remember buffer time until stream is created
					conn.rememberStreamBufferDuration(streamId, buffer);
					log.info("Remembering client buffer on stream: {}", buffer);
				}
				break;

			case Ping.PONG_SERVER:
				// This is the response to an IConnection.ping request
				conn.pingReceived(ping);
				break;

			default:
				log.warn("Unhandled ping: {}", ping);
		}

	}

	/**
	 * Create and send SO message stating that a SO could not be created.
	 * 
	 * @param conn
	 * @param name
	 * @param persistent
	 */
	private void sendSOCreationFailed(RTMPConnection conn, String name, boolean persistent) {
		SharedObjectMessage msg = new SharedObjectMessage(name, 0, persistent);
		msg.addEvent(new SharedObjectEvent(ISharedObjectEvent.Type.CLIENT_STATUS, "error", SO_CREATION_FAILED));
		conn.getChannel(3).write(msg);
	}

	/** {@inheritDoc} */
	@Override
	protected void onSharedObject(RTMPConnection conn, Channel channel, Header source, SharedObjectMessage object) {
		final ISharedObject so;
		final String name = object.getName();
		final boolean persistent = object.isPersistent();
		final IScope scope = conn.getScope();
		if (scope == null) {
			// The scope already has been deleted.
			sendSOCreationFailed(conn, name, persistent);
			return;
		}

		ISharedObjectService sharedObjectService = (ISharedObjectService) getScopeService(scope, ISharedObjectService.class, SharedObjectService.class, false);
		if (!sharedObjectService.hasSharedObject(scope, name)) {
			ISharedObjectSecurityService security = (ISharedObjectSecurityService) ScopeUtils.getScopeService(scope, ISharedObjectSecurityService.class);
			if (security != null) {
				// Check handlers to see if creation is allowed
				for (ISharedObjectSecurity handler : security.getSharedObjectSecurity()) {
					if (!handler.isCreationAllowed(scope, name, persistent)) {
						sendSOCreationFailed(conn, name, persistent);
						return;
					}
				}
			}

			if (!sharedObjectService.createSharedObject(scope, name, persistent)) {
				sendSOCreationFailed(conn, name, persistent);
				return;
			}
		}
		so = sharedObjectService.getSharedObject(scope, name);
		if (so.isPersistentObject() != persistent) {
			SharedObjectMessage msg = new SharedObjectMessage(name, 0, persistent);
			msg.addEvent(new SharedObjectEvent(ISharedObjectEvent.Type.CLIENT_STATUS, "error", SO_PERSISTENCE_MISMATCH));
			conn.getChannel(3).write(msg);
		}
		so.dispatchEvent(object);
	}

}
