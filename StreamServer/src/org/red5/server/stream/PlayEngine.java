package org.red5.server.stream;

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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.io.amf.Output;
import org.red5.io.object.Serializer;
import org.red5.logging.Red5LoggerFactory;
import org.red5.server.api.IScope;
import org.red5.server.api.scheduling.IScheduledJob;
import org.red5.server.api.scheduling.ISchedulingService;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.api.stream.IPlayItem;
import org.red5.server.api.stream.IPlaylistSubscriberStream;
import org.red5.server.api.stream.IStreamCodecInfo;
import org.red5.server.api.stream.ISubscriberStream;
import org.red5.server.api.stream.IVideoStreamCodec;
import org.red5.server.api.stream.OperationNotSupportedException;
import org.red5.server.api.stream.StreamState;
import org.red5.server.messaging.AbstractMessage;
import org.red5.server.messaging.IFilter;
import org.red5.server.messaging.IMessage;
import org.red5.server.messaging.IMessageComponent;
import org.red5.server.messaging.IMessageInput;
import org.red5.server.messaging.IMessageOutput;
import org.red5.server.messaging.IPassive;
import org.red5.server.messaging.IPipe;
import org.red5.server.messaging.IPipeConnectionListener;
import org.red5.server.messaging.IProvider;
import org.red5.server.messaging.IPushableConsumer;
import org.red5.server.messaging.OOBControlMessage;
import org.red5.server.messaging.PipeConnectionEvent;
import org.red5.server.net.rtmp.event.Aggregate;
import org.red5.server.net.rtmp.event.AudioData;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.event.Ping;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.event.VideoData.FrameType;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.net.rtmp.status.Status;
import org.red5.server.net.rtmp.status.StatusCodes;
import org.red5.server.stream.codec.StreamCodecInfo;
import org.red5.server.stream.message.RTMPMessage;
import org.red5.server.stream.message.ResetMessage;
import org.red5.server.stream.message.StatusMessage;
import org.slf4j.Logger;

/**
 * A play engine for playing an IPlayItem.
 * 
 * @author The Red5 Project (red5@osflash.org)
 * @author Steven Gong
 * @author Paul Gregoire (mondain@gmail.com)
 * @author Dan Rossi
 * @author Tiago Daniel Jacobs (tiago@imdt.com.br)
 */
public final class PlayEngine implements IFilter, IPushableConsumer, IPipeConnectionListener {

	private static final Logger log = Red5LoggerFactory.getLogger(PlayEngine.class);

	private IMessageInput msgIn;

	private IMessageOutput msgOut;

	private final ISubscriberStream subscriberStream;

	private ISchedulingService schedulingService;

	private IConsumerService consumerService;

	private IProviderService providerService;

	private int streamId;

	/**
	 * Receive video?
	 */
	private boolean receiveVideo = true;

	/**
	 * Receive audio?
	 */
	private boolean receiveAudio = true;

	private boolean pullMode;

	private String waitLiveJob;

	private boolean waiting;

	/**
	 * timestamp of first sent packet
	 */
	private int streamStartTS;

	private IPlayItem currentItem;

	private RTMPMessage pendingMessage;

	/**
	 * Interval in ms to check for buffer underruns in VOD streams.
	 */
	private int bufferCheckInterval = 0;

	/**
	 * Number of pending messages at which a <code>NetStream.Play.InsufficientBW</code>
	 * message is generated for VOD streams.
	 */
	private int underrunTrigger = 10;

	/**
	 * threshold for number of pending video frames
	 */
	private int maxPendingVideoFramesThreshold = 10;

	/**
	 * if we have more than 1 pending video frames, but less than maxPendingVideoFrames,
	 * continue sending until there are this many sequential frames with more than 1
	 * pending
	 */
	private int maxSequentialPendingVideoFrames = 10;

	/**
	 * the number of sequential video frames with > 0 pending frames
	 */
	private int numSequentialPendingVideoFrames = 0;

	/**
	 * State machine for video frame dropping in live streams
	 */
	private IFrameDropper videoFrameDropper = new VideoFrameDropper();

	private int timestampOffset = 0;

	/**
	 * Timestamp of the last message sent to the client.
	 */
	private int lastMessageTs = -1;

	/**
	 * Number of bytes sent.
	 */
	private AtomicLong bytesSent = new AtomicLong(0);

	/**
	 * Start time of stream playback.
	 * It's not a time when the stream is being played but
	 * the time when the stream should be played if it's played
	 * from the very beginning.
	 * Eg. A stream is played at timestamp 5s on 1:00:05. The
	 * playbackStart is 1:00:00.
	 */
	private long playbackStart;

	/**
	 * Scheduled future job that makes sure messages are sent to the client.
	 */
	private volatile ScheduledFuture<?> pullAndPushFuture = null;

	/**
	 * Monitor to guard setup and teardown of pull/push thread.
	 */
	private Object pullAndPushMonitor = new Object();

	/**
	 * Monitor guarding completion of a given push/pull run.
	 * Used to wait for job cancellation to finish.
	 */
	private Object doingPullMonitor = new Object();

	/**
	 * Offset in ms the stream started.
	 */
	private int streamOffset;

	/**
	 * Timestamp when buffer should be checked for underruns next. 
	 */
	private long nextCheckBufferUnderrun;

	/**
	 * Send blank audio packet next?
	 */
	private boolean sendBlankAudio;

	/**
	 * decision: 0 for Live, 1 for File, 2 for Wait, 3 for N/A
	 */
	private int playDecision = 3;

	/**
	 * List of pending operations
	 */
	private LinkedList<Runnable> pendingOperations = null;
	
	/**
	 * Constructs a new PlayEngine.
	 */
	private PlayEngine(Builder builder) {
		subscriberStream = builder.subscriberStream;
		schedulingService = builder.schedulingService;
		consumerService = builder.consumerService;
		providerService = builder.providerService;
		//
		streamId = subscriberStream.getStreamId();
		pendingOperations = new LinkedList<Runnable>();
	}

	/**
	 * Builder pattern
	 */
	public final static class Builder {
		//Required for play engine
		private ISubscriberStream subscriberStream;

		//Required for play engine
		private ISchedulingService schedulingService;

		//Required for play engine
		private IConsumerService consumerService;

		//Required for play engine
		private IProviderService providerService;

		public Builder(ISubscriberStream subscriberStream, ISchedulingService schedulingService, IConsumerService consumerService, IProviderService providerService) {
			this.subscriberStream = subscriberStream;
			this.schedulingService = schedulingService;
			this.consumerService = consumerService;
			this.providerService = providerService;
		}

		public PlayEngine build() {
			return new PlayEngine(this);
		}

	}

	public void setBufferCheckInterval(int bufferCheckInterval) {
		this.bufferCheckInterval = bufferCheckInterval;
	}

	public void setUnderrunTrigger(int underrunTrigger) {
		this.underrunTrigger = underrunTrigger;
	}

	void setMessageOut(IMessageOutput msgOut) {
		this.msgOut = msgOut;
	}

	/**
	 * Start stream
	 */
	public void start() {
		switch (subscriberStream.getState()) {
			case UNINIT:
				//allow start if uninitialized
				synchronized (this) {
					subscriberStream.setState(StreamState.STOPPED);
				}
				if (msgOut == null) {
					msgOut = consumerService.getConsumerOutput(subscriberStream);
					msgOut.subscribe(this, null);
				}
				break;
			default:
				throw new IllegalStateException("Cannot start in current state");
		}
	}

	/**
	 * Play stream
	 * @param item                  Playlist item
	 * @throws StreamNotFoundException       Stream not found
	 * @throws IllegalStateException         Stream is in stopped state
	 * @throws IOException Stream had io exception
	 */
	public void play(IPlayItem item) throws StreamNotFoundException, IllegalStateException, IOException {
		play(item, true);
	}

	/**
	 * Play stream
	 * @param item                  Playlist item
	 * @param withReset				Send reset status before playing.
	 * @throws StreamNotFoundException       Stream not found
	 * @throws IllegalStateException         Stream is in stopped state
	 * @throws IOException Stream had IO exception
	 */
	public synchronized void play(IPlayItem item, boolean withReset) throws StreamNotFoundException, IllegalStateException, IOException {
		// Can't play if state is not stopped
		switch (subscriberStream.getState()) {
			case STOPPED:
				//allow play if stopped
				if (msgIn != null) {
					msgIn.unsubscribe(this);
					msgIn = null;
				}
				break;
			default:
				throw new IllegalStateException("Cannot play from non-stopped state");
		}
		// Play type determination
		// http://livedocs.adobe.com/flex/3/langref/flash/net/NetStream.html#play%28%29
		// The start time, in seconds. Allowed values are -2, -1, 0, or a positive number. 
		// The default value is -2, which looks for a live stream, then a recorded stream, 
		// and if it finds neither, opens a live stream. 
		// If -1, plays only a live stream. 
		// If 0 or a positive number, plays a recorded stream, beginning start seconds in.
		//
		// -2: live then recorded, -1: live, >=0: recorded
		int type = (int) (item.getStart() / 1000);
		log.debug("Type {}", type);
		// see if it's a published stream
		IScope thisScope = subscriberStream.getScope();
		final String itemName = item.getName();
		//check for input and type
		IProviderService.INPUT_TYPE sourceType = providerService.lookupProviderInput(thisScope, itemName, type);

		boolean isPublishedStream = sourceType == IProviderService.INPUT_TYPE.LIVE;
		boolean isPublishedStreamWait = sourceType == IProviderService.INPUT_TYPE.LIVE_WAIT;
		boolean isFileStream = sourceType == IProviderService.INPUT_TYPE.VOD;

		boolean sendNotifications = true;

		// decision: 0 for Live, 1 for File, 2 for Wait, 3 for N/A

		switch (type) {
			case -2:
				if (isPublishedStream) {
					playDecision = 0;
				} else if (isFileStream) {
					playDecision = 1;
				} else if (isPublishedStreamWait) {
					playDecision = 2;
				}
				break;

			case -1:
				if (isPublishedStream) {
					playDecision = 0;
				} else {
					playDecision = 2;
				}
				break;

			default:
				if (isFileStream) {
					playDecision = 1;
				}
				break;
		}
		log.debug("Play decision is {} (0=Live, 1=File, 2=Wait, 3=N/A)", playDecision);
		IMessage msg = null;
		currentItem = item;
		long itemLength = item.getLength();
		log.debug("Item length: {}", itemLength);
		switch (playDecision) {
			case 0:
				//get source input without create
				msgIn = providerService.getLiveProviderInput(thisScope, itemName, false);

				//drop all frames up to the next keyframe
				videoFrameDropper.reset(IFrameDropper.SEND_KEYFRAMES_CHECK);

				if (msgIn instanceof IBroadcastScope) {
					IBroadcastStream stream = (IBroadcastStream) ((IBroadcastScope) msgIn).getAttribute(IBroadcastScope.STREAM_ATTRIBUTE);

					if (stream != null && stream.getCodecInfo() != null) {
						IVideoStreamCodec videoCodec = stream.getCodecInfo().getVideoCodec();
						if (videoCodec != null) {
							if (withReset) {
								sendReset();
								sendResetStatus(item);
								sendStartStatus(item);
							}
							sendNotifications = false;
						}
					}
				}
				//Subscribe to stream (ClientBroadcastStream.onPipeConnectionEvent)
				msgIn.subscribe(this, null);
				//execute the processes to get Live playback setup
				playLive();
				break;
			case 2:
				//get source input with create
				msgIn = providerService.getLiveProviderInput(thisScope, itemName, true);
				msgIn.subscribe(this, null);
				waiting = true;
				if (type == -1 && itemLength >= 0) {
					log.debug("Creating wait job");
					// Wait given timeout for stream to be published
					waitLiveJob = schedulingService.addScheduledOnceJob(itemLength, new IScheduledJob() {
						public void execute(ISchedulingService service) {
							//set the msgIn if its null
							if (msgIn == null) {
								connectToProvider(itemName);
							}
							waitLiveJob = null;
							waiting = false;
							subscriberStream.onChange(StreamState.END);
						}
					});
				} else if (type == -2) {
					log.debug("Creating wait job");
					// Wait x seconds for the stream to be published
					waitLiveJob = schedulingService.addScheduledOnceJob(15000, new IScheduledJob() {
						public void execute(ISchedulingService service) {
							//set the msgIn if its null
							if (msgIn == null) {
								connectToProvider(itemName);
							}
							waitLiveJob = null;
							waiting = false;
						}
					});
				} else {
					connectToProvider(itemName);
				}
				break;
			case 1:
				msgIn = providerService.getVODProviderInput(thisScope, itemName);
				if (msgIn == null) {
					sendStreamNotFoundStatus(currentItem);
					throw new StreamNotFoundException(itemName);
				} else if (msgIn.subscribe(this, null)) {
					//execute the processes to get VOD playback setup
					msg = playVOD(withReset, itemLength);
				} else {
					log.error("Input source subscribe failed");
					throw new IOException(String.format("Subscribe to %s failed", itemName));
				}
				break;
			default:
				sendStreamNotFoundStatus(currentItem);
				throw new StreamNotFoundException(itemName);
		}
		//continue with common play processes (live and vod)
		if (sendNotifications) {
			if (withReset) {
				sendReset();
				sendResetStatus(item);
			}
			sendStartStatus(item);
			if (!withReset) {
				sendSwitchStatus();
			}
		}
		if (msg != null) {
			sendMessage((RTMPMessage) msg);
		}
		subscriberStream.onChange(StreamState.PLAYING, currentItem, !pullMode);
		if (withReset) {
			long currentTime = System.currentTimeMillis();
			playbackStart = currentTime - streamOffset;
			nextCheckBufferUnderrun = currentTime + bufferCheckInterval;
			if (currentItem.getLength() != 0) {
				ensurePullAndPushRunning();
			}
		}
	}

	/**
	 * Performs the processes needed for live streams.
	 * The following items are sent if they exist:
	 * - Metadata
	 * - Decoder configuration (ie. AVC codec)
	 * - Most recent keyframe
	 * 
	 * @throws IOException
	 */
	private final void playLive() throws IOException {
		//change state
		subscriberStream.setState(StreamState.PLAYING);
		streamOffset = 0;
		streamStartTS = -1;
		//get the stream so that we can grab any metadata and decoder configs
		IBroadcastStream stream = (IBroadcastStream) ((IBroadcastScope) msgIn).getAttribute(IBroadcastScope.STREAM_ATTRIBUTE);
		//prevent an NPE when a play list is created and then immediately flushed
		if (stream != null) {
			Notify metaData = stream.getMetaData();
			//check for metadata to send
			if (metaData != null) {
				log.debug("Metadata is available");
				RTMPMessage metaMsg = new RTMPMessage();
				metaMsg.setBody(metaData);
				metaMsg.getBody().setTimestamp(0);
				try {
					msgOut.pushMessage(metaMsg);
				} catch (IOException e) {
					log.warn("Error sending metadata", e);
				}
			} else {
				log.debug("No metadata available");
			}

			IStreamCodecInfo codecInfo = stream.getCodecInfo();
			log.debug("Codec info: {}", codecInfo);
			if (codecInfo instanceof StreamCodecInfo) {
				StreamCodecInfo info = (StreamCodecInfo) codecInfo;
				IVideoStreamCodec videoCodec = info.getVideoCodec();
				log.debug("Video codec: {}", videoCodec);
				if (videoCodec != null) {
					//check for decoder configuration to send
					IoBuffer config = videoCodec.getDecoderConfiguration();
					if (config != null) {
						log.debug("Decoder configuration is available for {}", videoCodec.getName());
						//log.debug("Dump:\n{}", Hex.encodeHex(config.array()));
						VideoData conf = new VideoData(config.asReadOnlyBuffer());
						log.trace("Configuration ts: {}", conf.getTimestamp());
						RTMPMessage confMsg = new RTMPMessage();
						confMsg.setBody(conf);
						try {
							log.debug("Pushing decoder configuration");
							msgOut.pushMessage(confMsg);
						} finally {
							conf.release();
						}
					}
					//check for a keyframe to send
					IoBuffer keyFrame = videoCodec.getKeyframe();
					if (keyFrame != null) {
						log.debug("Keyframe is available");
						VideoData video = new VideoData(keyFrame.asReadOnlyBuffer());
						log.trace("Keyframe ts: {}", video.getTimestamp());
						//log.debug("Dump:\n{}", Hex.encodeHex(keyFrame.array()));
						RTMPMessage videoMsg = new RTMPMessage();
						videoMsg.setBody(video);
						try {
							log.debug("Pushing keyframe");
							msgOut.pushMessage(videoMsg);
						} finally {
							video.release();
						}
					}
				} else {
					log.debug("Could not initialize stream output, videoCodec is null.");
				}
			}
		}
	}

	/**
	 * Performs the processes needed for VOD / pre-recorded streams.
	 * 
	 * @param withReset whether or not to perform reset on the stream
	 * @param itemLength length of the item to be played
	 * @return message for the consumer
	 * @throws IOException
	 */
	private final IMessage playVOD(boolean withReset, long itemLength) throws IOException {
		IMessage msg = null;
		//change state
		subscriberStream.setState(StreamState.PLAYING);
		streamOffset = 0;
		streamStartTS = -1;
		if (withReset) {
			releasePendingMessage();
		}
		sendVODInitCM(msgIn, currentItem);
		// Don't use pullAndPush to detect IOExceptions prior to sending
		// NetStream.Play.Start
		if (currentItem.getStart() > 0) {
			streamOffset = sendVODSeekCM(msgIn, (int) currentItem.getStart());
			// We seeked to the nearest keyframe so use real timestamp now
			if (streamOffset == -1) {
				streamOffset = (int) currentItem.getStart();
			}
		}
		msg = msgIn.pullMessage();
		if (msg instanceof RTMPMessage) {
			// Only send first video frame
			IRTMPEvent body = ((RTMPMessage) msg).getBody();
			if (itemLength == 0) {
				while (body != null && !(body instanceof VideoData)) {
					msg = msgIn.pullMessage();
					if (msg != null && msg instanceof RTMPMessage) {
						body = ((RTMPMessage) msg).getBody();
					} else {
						break;
					}
				}
			}
			if (body != null) {
				// Adjust timestamp when playing lists
				body.setTimestamp(body.getTimestamp() + timestampOffset);
			}
		}
		return msg;
	}

	/**
	 * Connects to the data provider.
	 * 
	 * @param itemName name of the item to play
	 */
	private final void connectToProvider(String itemName) {
		log.debug("Attempting connection to {}", itemName);
		IScope thisScope = subscriberStream.getScope();
		msgIn = providerService.getLiveProviderInput(thisScope, itemName, true);
		if (msgIn != null) {
			log.debug("Provider: {}", msgIn);
			if (msgIn.subscribe(this, null)) {
				log.debug("Subscribed to {} provider", itemName);
				//execute the processes to get Live playback setup
				try {
					playLive();
				} catch (IOException e) {
					log.warn("Could not play live stream: {}", itemName, e);
				}
			} else {
				log.warn("Subscribe to {} provider failed", itemName);
			}
		} else {
			log.warn("Provider was not found for {}", itemName);
			StreamService.sendNetStreamStatus(subscriberStream.getConnection(), StatusCodes.NS_PLAY_STREAMNOTFOUND, "Stream was not found", itemName, Status.ERROR, streamId);
		}
	}

	/**
	 * Pause at position
	 * @param position                  Position in file
	 * @throws IllegalStateException    If stream is stopped
	 */
	public void pause(int position) throws IllegalStateException {
		switch (subscriberStream.getState()) {
			case PLAYING:
			case STOPPED:
				//allow pause if playing or stopped
				synchronized (this) {
					subscriberStream.setState(StreamState.PAUSED);
				}
				clearWaitJobs();
				sendClearPing();
				sendPauseStatus(currentItem);
				subscriberStream.onChange(StreamState.PAUSED, currentItem, position);
				break;
			default:
				throw new IllegalStateException("Cannot pause in current state");
		}
	}

	/**
	 * Resume playback
	 * @param position                   Resumes playback
	 * @throws IllegalStateException     If stream is stopped
	 */
	public void resume(int position) throws IllegalStateException {
		switch (subscriberStream.getState()) {
			case PAUSED:
				//allow resume from pause
				synchronized (this) {
					subscriberStream.setState(StreamState.PLAYING);
				}
				sendReset();
				sendResumeStatus(currentItem);
				if (pullMode) {
					sendVODSeekCM(msgIn, position);
					subscriberStream.onChange(StreamState.RESUMED, currentItem, position);
					playbackStart = System.currentTimeMillis() - position;
					if (currentItem.getLength() >= 0 && (position - streamOffset) >= currentItem.getLength()) {
						// Resume after end of stream
						stop();
					} else {
						ensurePullAndPushRunning();
					}
				} else {
					subscriberStream.onChange(StreamState.RESUMED, currentItem, position);
					videoFrameDropper.reset(VideoFrameDropper.SEND_KEYFRAMES_CHECK);
				}
				break;
			default:
				throw new IllegalStateException("Cannot resume from non-paused state");
		}
	}

	/**
	 * Seek position in file
	 * @param position                  Position
	 * @throws IllegalStateException    If stream is in stopped state
	 * @throws OperationNotSupportedException If this object doesn't support the operation.
	 */
	public synchronized void seek(final int position) throws IllegalStateException, OperationNotSupportedException {
		Runnable seekRunnable = null;
		
		seekRunnable = new Runnable() {
			public void run() {
				log.trace("Seek: {}", position);
				boolean startPullPushThread = false;
				switch (subscriberStream.getState()) {
					case PLAYING:
						startPullPushThread = true;
					case PAUSED:
					case STOPPED:
						//allow seek if playing, paused, or stopped
						if (!pullMode) {
							// throw new OperationNotSupportedException();
							throw new RuntimeException();
						}
		
						releasePendingMessage();
						clearWaitJobs();
		
						break;
					default:
						throw new IllegalStateException("Cannot seek in current state");
				}
		
				sendClearPing();
				sendReset();
				sendSeekStatus(currentItem, position);
				sendStartStatus(currentItem);
				int seekPos = sendVODSeekCM(msgIn, position);
				// We seeked to the nearest keyframe so use real timestamp now
				if (seekPos == -1) {
					seekPos = position;
				}
				//what should our start be?
				log.trace("Current playback start: {}", playbackStart);
				playbackStart = System.currentTimeMillis() - seekPos;
				log.trace("Playback start: {} seek pos: {}", playbackStart, seekPos);
				subscriberStream.onChange(StreamState.SEEK, currentItem, seekPos);
				// start off with not having sent any message
				boolean messageSent = false;
				// read our client state
				switch (subscriberStream.getState()) {
					case PAUSED:
					case STOPPED:
						// we send a single snapshot on pause
						if (sendCheckVideoCM(msgIn)) {
							IMessage msg = null;
							do {
								try {
									msg = msgIn.pullMessage();
								} catch (Throwable err) {
									log.error("Error while pulling message", err);
									msg = null;
								}
								if (msg instanceof RTMPMessage) {
									RTMPMessage rtmpMessage = (RTMPMessage) msg;
									IRTMPEvent body = rtmpMessage.getBody();
									if (body instanceof VideoData && ((VideoData) body).getFrameType() == FrameType.KEYFRAME) {
										//body.setTimestamp(seekPos);
										doPushMessage(rtmpMessage);
										rtmpMessage.getBody().release();
										messageSent = true;
										lastMessageTs = body.getTimestamp();
										break;
									}
								}
							} while (msg != null);
						}
				}
				//seeked past end of stream
				if (currentItem.getLength() >= 0 && (position - streamOffset) >= currentItem.getLength()) {
					stop();
				}
				// if no message has been sent by this point send an audio packet
				if (!messageSent) {
					// Send blank audio packet to notify client about new position
					log.debug("Sending blank audio packet");
					AudioData audio = new AudioData();
					audio.setTimestamp(seekPos);
					audio.setHeader(new Header());
					audio.getHeader().setTimer(seekPos);
					RTMPMessage audioMessage = new RTMPMessage();
					audioMessage.setBody(audio);
					lastMessageTs = seekPos;
					doPushMessage(audioMessage);
					audioMessage.getBody().release();
				}
				if (!messageSent && subscriberStream.getState() == StreamState.PLAYING) {
					// send all frames from last keyframe up to requested position and fill client buffer
					if (sendCheckVideoCM(msgIn)) {
						final long clientBuffer = subscriberStream.getClientBufferDuration();
						IMessage msg = null;
						do {
							try {
								msg = msgIn != null ? msgIn.pullMessage() : null;
								if (msg instanceof RTMPMessage) {
									RTMPMessage rtmpMessage = (RTMPMessage) msg;
									IRTMPEvent body = rtmpMessage.getBody();
									if (body.getTimestamp() >= position + (clientBuffer*2)) {
										// client buffer should be full by now, continue regular pull/push
										releasePendingMessage();
										if (checkSendMessageEnabled(rtmpMessage)) {
											pendingMessage = rtmpMessage;
										}
										break;
									}
									if (!checkSendMessageEnabled(rtmpMessage)) {
										continue;
									}								
									sendMessage(rtmpMessage);
								}
							} catch (Throwable err) {
								log.error("Error while pulling message", err);
								msg = null;
							}
						} while (msg != null);
						playbackStart = System.currentTimeMillis() - lastMessageTs;
					}
				}
				// start pull-push
				if (startPullPushThread) {
					ensurePullAndPushRunning();
				}
			}
		};
		
		// Add this pending seek operation to the list
		synchronized(this.pendingOperations) {
			this.pendingOperations.addLast(seekRunnable);
		}
	}

	/**
	 * Stop playback
	 * @throws IllegalStateException    If stream is in stopped state
	 */
	public void stop() throws IllegalStateException {
		switch (subscriberStream.getState()) {
			case PLAYING:
			case PAUSED:
				//allow stop if playing or paused
				synchronized (this) {
					subscriberStream.setState(StreamState.STOPPED);
				}
				if (msgIn != null && !pullMode) {
					msgIn.unsubscribe(this);
					msgIn = null;
				}
				subscriberStream.onChange(StreamState.STOPPED, currentItem);
				clearWaitJobs();
				if (subscriberStream instanceof IPlaylistSubscriberStream) {
					IPlaylistSubscriberStream pss = (IPlaylistSubscriberStream) subscriberStream;
					if (!pss.hasMoreItems()) {
						releasePendingMessage();
						if (pss.getItemSize() > 0) {
							sendCompleteStatus();
						}
						bytesSent.set(0);
						sendClearPing();
						sendStopStatus(currentItem);
					} else {
						if (lastMessageTs > 0) {
							// Remember last timestamp so we can generate correct
							// headers in playlists.
							timestampOffset = lastMessageTs;
						}
						pss.nextItem();
					}
				}
				break;
			default:
				throw new IllegalStateException("Cannot stop in current state");
		}
	}

	/**
	 * Close stream
	 */
	public void close() {
		if (msgIn != null) {
			msgIn.unsubscribe(this);
			msgIn = null;
		}
		synchronized (this) {
			subscriberStream.setState(StreamState.CLOSED);
		}
		clearWaitJobs();
		releasePendingMessage();
		lastMessageTs = 0;
		sendClearPing();
	}

	/**
	 * Check if it's okay to send the client more data. This takes the configured
	 * bandwidth as well as the requested client buffer into account.
	 * 
	 * @param message
	 * @return
	 */
	private boolean okayToSendMessage(IRTMPEvent message) {
		if (message instanceof IStreamData) {
			final long now = System.currentTimeMillis();
			// check client buffer size
			if (isClientBufferFull(now)) {
				return false;
			}
			// get pending message count
			long pending = pendingMessages();
			if (bufferCheckInterval > 0 && now >= nextCheckBufferUnderrun) {
				if (pending > underrunTrigger) {
					// Client is playing behind speed, notify him
					sendInsufficientBandwidthStatus(currentItem);
				}
				nextCheckBufferUnderrun = now + bufferCheckInterval;
			}
			// check for under run
			if (pending > underrunTrigger) {
				// Too many messages already queued on the connection
				return false;
			}
			return true;
		} else {
			String itemName = "Undefined";
			//if current item exists get the name to help debug this issue
			if (currentItem != null) {
				itemName = currentItem.getName();
			}
			Object[] errorItems = new Object[] { message.getClass(), message.getDataType(), itemName };
			throw new RuntimeException(String.format("Expected IStreamData but got %s (type %s) for %s", errorItems));
		}
	}

	/**
	 * Estimate client buffer fill.
	 * @param now The current timestamp being used.
	 * @return True if it appears that the client buffer is full, otherwise false.
	 */
	private boolean isClientBufferFull(final long now) {
		// check client buffer length when we've already sent some messages
		if (lastMessageTs > 0) {
			// Duration the stream is playing / playback duration
			final long delta = now - playbackStart;
			// Buffer size as requested by the client
			final long buffer = subscriberStream.getClientBufferDuration();
			// Expected amount of data present in client buffer
			final long buffered = lastMessageTs - delta;
			log.trace("isClientBufferFull: timestamp {} delta {} buffered {} buffer {}", new Object[] { lastMessageTs, delta, buffered, buffer });
			//Fix for SN-122, this sends double the size of the client buffer
			if (buffer > 0 && buffered > (buffer * 2)) {
				// Client is likely to have enough data in the buffer
				return true;
			}
		}
		return false;
	}

	/**
	 * Make sure the pull and push processing is running.
	 */
	private void ensurePullAndPushRunning() {
		if (pullMode && pullAndPushFuture == null) {
			synchronized (pullAndPushMonitor) {
				// client buffer is at least 100ms
				pullAndPushFuture = subscriberStream.getExecutor().scheduleWithFixedDelay(new PullAndPushRunnable(), 0, 10, TimeUnit.MILLISECONDS);
			}
		}
	}

	/**
	 * Clear all scheduled waiting jobs
	 */
	private void clearWaitJobs() {
		log.debug("Clear wait jobs");
		if (pullAndPushFuture != null) {
			pullAndPushFuture.cancel(false);
			synchronized (doingPullMonitor) {
				releasePendingMessage();
				pullAndPushFuture = null;
			}
		}
		if (waitLiveJob != null) {
			schedulingService.removeScheduledJob(waitLiveJob);
			waitLiveJob = null;
		}
	}

	/**
	 * Sends a status message.
	 * 
	 * @param status
	 */
	private void doPushMessage(Status status) {
		StatusMessage message = new StatusMessage();
		message.setBody(status);
		doPushMessage(message);
	}

	/**
	 * Send message to output stream and handle exceptions.
	 * 
	 * @param message The message to send.
	 */
	private void doPushMessage(AbstractMessage message) {
		try {
			msgOut.pushMessage(message);
			if (message instanceof RTMPMessage) {
				IRTMPEvent body = ((RTMPMessage) message).getBody();
				//update the last message sent's timestamp
				lastMessageTs = body.getTimestamp();
				IoBuffer streamData = null;
				if (body instanceof IStreamData && (streamData = ((IStreamData<?>) body).getData()) != null) {
					bytesSent.addAndGet(streamData.limit());
				}
			}
		} catch (IOException err) {
			log.error("Error while pushing message", err);
		}
	}

	/**
	 * Send RTMP message
	 * @param message        RTMP message
	 */
	private void sendMessage(RTMPMessage messageIn) {
		//copy patch from Andy Shaules
		IRTMPEvent event;
		IoBuffer dataReference;
		switch (messageIn.getBody().getDataType()) {
			case Constants.TYPE_AGGREGATE:
				dataReference = ((Aggregate) messageIn.getBody()).getData();
				event = new Aggregate(dataReference);
				event.setTimestamp(messageIn.getBody().getTimestamp());
				break;
			case Constants.TYPE_AUDIO_DATA:
				dataReference = ((AudioData) messageIn.getBody()).getData();
				event = new AudioData(dataReference);
				event.setTimestamp(messageIn.getBody().getTimestamp());
				break;
			case Constants.TYPE_VIDEO_DATA:
				dataReference = ((VideoData) messageIn.getBody()).getData();
				event = new VideoData(dataReference);
				event.setTimestamp(messageIn.getBody().getTimestamp());
				break;
			default:
				dataReference = ((Notify) messageIn.getBody()).getData();
				event = new Notify(dataReference);
				event.setTimestamp(messageIn.getBody().getTimestamp());
				break;
		}
		RTMPMessage messageOut = new RTMPMessage();
		messageOut.setBody(event);
		//get the current timestamp from the message
		int ts = messageOut.getBody().getTimestamp();
		if (log.isTraceEnabled()) {
			log.trace("sendMessage: streamStartTS={}, length={}, streamOffset={}, timestamp={}", new Object[] { streamStartTS, currentItem.getLength(), streamOffset, ts });
		}
		//relative timestamp adjustment for live streams
		if (playDecision == 0 && streamStartTS > 0) {
			//subtract the offset time of when the stream started playing for the client
			int relativeTs = ts - streamStartTS;
			messageOut.getBody().setTimestamp(relativeTs);
			//we changed the timestamp to update var
			ts = relativeTs;
			if (log.isTraceEnabled()) {
				log.trace("sendMessage (updated): streamStartTS={}, length={}, streamOffset={}, timestamp={}", new Object[] { streamStartTS, currentItem.getLength(), streamOffset,
						ts });
			}
		}
		if (streamStartTS == -1) {
			log.debug("sendMessage: resetting streamStartTS");
			streamStartTS = ts;
			messageOut.getBody().setTimestamp(0);
		} else {
			if (currentItem.getLength() >= 0) {
				int duration = ts - streamStartTS;
				if (duration - streamOffset >= currentItem.getLength()) {
					// Sent enough data to client
					stop();
					return;
				}
			}
		}
		doPushMessage(messageOut);
	}

	/**
	 * Send clear ping. Lets client know that stream has no more data to
	 * send.
	 */
	private void sendClearPing() {
		Ping eof = new Ping();
		eof.setEventType(Ping.STREAM_PLAYBUFFER_CLEAR);
		eof.setValue2(streamId);
		// eos 
		RTMPMessage eofMsg = new RTMPMessage();
		eofMsg.setBody(eof);
		doPushMessage(eofMsg);
	}

	/**
	 * Send reset message
	 */
	private void sendReset() {
		if (pullMode) {
			Ping recorded = new Ping();
			recorded.setEventType(Ping.RECORDED_STREAM);
			recorded.setValue2(streamId);
			// recorded 
			RTMPMessage recordedMsg = new RTMPMessage();
			recordedMsg.setBody(recorded);
			doPushMessage(recordedMsg);
		}

		Ping begin = new Ping();
		begin.setEventType(Ping.STREAM_BEGIN);
		begin.setValue2(streamId);
		// begin 
		RTMPMessage beginMsg = new RTMPMessage();
		beginMsg.setBody(begin);
		doPushMessage(beginMsg);
		// reset
		ResetMessage reset = new ResetMessage();
		doPushMessage(reset);
	}

	/**
	 * Send reset status for item
	 * @param item            Playlist item
	 */
	private void sendResetStatus(IPlayItem item) {
		Status reset = new Status(StatusCodes.NS_PLAY_RESET);
		reset.setClientid(streamId);
		reset.setDetails(item.getName());
		reset.setDesciption(String.format("Playing and resetting %s.", item.getName()));

		doPushMessage(reset);
	}

	/**
	 * Send playback start status notification
	 * @param item            Playlist item
	 */
	private void sendStartStatus(IPlayItem item) {
		Status start = new Status(StatusCodes.NS_PLAY_START);
		start.setClientid(streamId);
		start.setDetails(item.getName());
		start.setDesciption(String.format("Started playing %s.", item.getName()));

		doPushMessage(start);
	}

	/**
	 * Send playback stoppage status notification
	 * @param item            Playlist item
	 */
	private void sendStopStatus(IPlayItem item) {
		Status stop = new Status(StatusCodes.NS_PLAY_STOP);
		stop.setClientid(streamId);
		stop.setDesciption(String.format("Stopped playing %s.", item.getName()));
		stop.setDetails(item.getName());

		doPushMessage(stop);
	}

	private void sendOnPlayStatus(String code, int duration, long bytes) {
		IoBuffer buf = IoBuffer.allocate(1024);
		buf.setAutoExpand(true);
		Output out = new Output(buf);
		out.writeString("onPlayStatus");
		Map<Object, Object> props = new HashMap<Object, Object>();
		props.put("code", code);
		props.put("level", "status");
		props.put("duration", duration);
		props.put("bytes", bytes);
		out.writeMap(props, new Serializer());
		buf.flip();

		IRTMPEvent event = new Notify(buf);
		if (lastMessageTs > 0) {
			event.setTimestamp(lastMessageTs);
		} else {
			event.setTimestamp(0);
		}
		RTMPMessage msg = new RTMPMessage();
		msg.setBody(event);
		doPushMessage(msg);
	}

	/**
	 * Send playlist switch status notification
	 */
	private void sendSwitchStatus() {
		// TODO: find correct duration to send
		int duration = 1;
		sendOnPlayStatus(StatusCodes.NS_PLAY_SWITCH, duration, bytesSent.get());
	}

	/**
	 * Send playlist complete status notification
	 *
	 */
	private void sendCompleteStatus() {
		// TODO: find correct duration to send
		int duration = 1;
		sendOnPlayStatus(StatusCodes.NS_PLAY_COMPLETE, duration, bytesSent.get());
	}

	/**
	 * Send seek status notification
	 * @param item            Playlist item
	 * @param position        Seek position
	 */
	private void sendSeekStatus(IPlayItem item, int position) {
		Status seek = new Status(StatusCodes.NS_SEEK_NOTIFY);
		seek.setClientid(streamId);
		seek.setDetails(item.getName());
		seek.setDesciption(String.format("Seeking %d (stream ID: %d).", position, streamId));

		doPushMessage(seek);
	}

	/**
	 * Send pause status notification
	 * @param item            Playlist item
	 */
	private void sendPauseStatus(IPlayItem item) {
		Status pause = new Status(StatusCodes.NS_PAUSE_NOTIFY);
		pause.setClientid(streamId);
		pause.setDetails(item.getName());

		doPushMessage(pause);
	}

	/**
	 * Send resume status notification
	 * @param item            Playlist item
	 */
	private void sendResumeStatus(IPlayItem item) {
		Status resume = new Status(StatusCodes.NS_UNPAUSE_NOTIFY);
		resume.setClientid(streamId);
		resume.setDetails(item.getName());

		doPushMessage(resume);
	}

	/**
	 * Send published status notification
	 * @param item            Playlist item
	 */
	private void sendPublishedStatus(IPlayItem item) {
		Status published = new Status(StatusCodes.NS_PLAY_PUBLISHNOTIFY);
		published.setClientid(streamId);
		published.setDetails(item.getName());

		doPushMessage(published);
	}

	/**
	 * Send unpublished status notification
	 * @param item            Playlist item
	 */
	private void sendUnpublishedStatus(IPlayItem item) {
		Status unpublished = new Status(StatusCodes.NS_PLAY_UNPUBLISHNOTIFY);
		unpublished.setClientid(streamId);
		unpublished.setDetails(item.getName());

		doPushMessage(unpublished);
	}

	/**
	 * Stream not found status notification
	 * @param item            Playlist item
	 */
	private void sendStreamNotFoundStatus(IPlayItem item) {
		Status notFound = new Status(StatusCodes.NS_PLAY_STREAMNOTFOUND);
		notFound.setClientid(streamId);
		notFound.setLevel(Status.ERROR);
		notFound.setDetails(item.getName());

		doPushMessage(notFound);
	}

	/**
	 * Insufficient bandwidth notification
	 * @param item            Playlist item
	 */
	private void sendInsufficientBandwidthStatus(IPlayItem item) {
		Status insufficientBW = new Status(StatusCodes.NS_PLAY_INSUFFICIENT_BW);
		insufficientBW.setClientid(streamId);
		insufficientBW.setLevel(Status.WARNING);
		insufficientBW.setDetails(item.getName());
		insufficientBW.setDesciption("Data is playing behind the normal speed.");

		doPushMessage(insufficientBW);
	}

	/**
	 * Send VOD init control message
	 * @param msgIn           Message input
	 * @param item            Playlist item
	 */
	private void sendVODInitCM(IMessageInput msgIn, IPlayItem item) {
		OOBControlMessage oobCtrlMsg = new OOBControlMessage();
		oobCtrlMsg.setTarget(IPassive.KEY);
		oobCtrlMsg.setServiceName("init");
		Map<String, Object> paramMap = new HashMap<String, Object>(1);
		paramMap.put("startTS", (int) item.getStart());
		oobCtrlMsg.setServiceParamMap(paramMap);
		msgIn.sendOOBControlMessage(this, oobCtrlMsg);
	}

	/**
	 * Send VOD seek control message
	 * @param msgIn            Message input
	 * @param position         Playlist item
	 * @return                 Out-of-band control message call result or -1 on failure
	 */
	private int sendVODSeekCM(IMessageInput msgIn, int position) {
		OOBControlMessage oobCtrlMsg = new OOBControlMessage();
		oobCtrlMsg.setTarget(ISeekableProvider.KEY);
		oobCtrlMsg.setServiceName("seek");
		Map<String, Object> paramMap = new HashMap<String, Object>(1);
		paramMap.put("position", position);
		oobCtrlMsg.setServiceParamMap(paramMap);
		msgIn.sendOOBControlMessage(this, oobCtrlMsg);
		if (oobCtrlMsg.getResult() instanceof Integer) {
			return (Integer) oobCtrlMsg.getResult();
		} else {
			return -1;
		}
	}

	/**
	 * Send VOD check video control message
	 * 
	 * @param msgIn
	 * @return
	 */
	private boolean sendCheckVideoCM(IMessageInput msgIn) {
		OOBControlMessage oobCtrlMsg = new OOBControlMessage();
		oobCtrlMsg.setTarget(IStreamTypeAwareProvider.KEY);
		oobCtrlMsg.setServiceName("hasVideo");
		msgIn.sendOOBControlMessage(this, oobCtrlMsg);
		if (oobCtrlMsg.getResult() instanceof Boolean) {
			return (Boolean) oobCtrlMsg.getResult();
		} else {
			return false;
		}
	}

	/** {@inheritDoc} */
	public void onOOBControlMessage(IMessageComponent source, IPipe pipe, OOBControlMessage oobCtrlMsg) {
		if ("ConnectionConsumer".equals(oobCtrlMsg.getTarget())) {
			if (source instanceof IProvider) {
				msgOut.sendOOBControlMessage((IProvider) source, oobCtrlMsg);
			}
		}
	}

	/** {@inheritDoc} */
	public void onPipeConnectionEvent(PipeConnectionEvent event) {
		switch (event.getType()) {
			case PipeConnectionEvent.PROVIDER_CONNECT_PUSH:
				if (event.getProvider() != this) {
					if (waiting) {
						schedulingService.removeScheduledJob(waitLiveJob);
						waitLiveJob = null;
						waiting = false;
					}
					sendPublishedStatus(currentItem);
				}
				break;
			case PipeConnectionEvent.PROVIDER_DISCONNECT:
				if (pullMode) {
					sendStopStatus(currentItem);
				} else {
					sendUnpublishedStatus(currentItem);
				}
				break;
			case PipeConnectionEvent.CONSUMER_CONNECT_PULL:
				if (event.getConsumer() == this) {
					pullMode = true;
				}
				break;
			case PipeConnectionEvent.CONSUMER_CONNECT_PUSH:
				if (event.getConsumer() == this) {
					pullMode = false;
				}
				break;
			default:
		}
	}

	/** {@inheritDoc} */
	public synchronized void pushMessage(IPipe pipe, IMessage message) throws IOException {
		if (message instanceof RTMPMessage) {
			RTMPMessage rtmpMessage = (RTMPMessage) message;
			IRTMPEvent body = rtmpMessage.getBody();
			if (body instanceof IStreamData) {
				if (body instanceof VideoData) {
					IVideoStreamCodec videoCodec = null;
					if (msgIn instanceof IBroadcastScope) {
						IBroadcastStream stream = (IBroadcastStream) ((IBroadcastScope) msgIn).getAttribute(IBroadcastScope.STREAM_ATTRIBUTE);
						if (stream != null && stream.getCodecInfo() != null) {
							videoCodec = stream.getCodecInfo().getVideoCodec();
						}
					}
					//dont try to drop frames if video codec is null - related to SN-77
					if (videoCodec != null && videoCodec.canDropFrames()) {
						if (subscriberStream.getState() == StreamState.PAUSED) {
							// The subscriber paused the video
							log.debug("Dropping packet because we are paused");
							videoFrameDropper.dropPacket(rtmpMessage);
							return;
						}

						if (!receiveVideo) {
							// The client disabled video or the app doesn't have enough bandwidth
							// allowed for this stream.
							log.debug("Dropping packet because we cant receive video or token acquire failed");
							videoFrameDropper.dropPacket(rtmpMessage);
							return;
						}

						// Only check for frame dropping if the codec supports it
						long pendingVideos = pendingVideoMessages();
						if (!videoFrameDropper.canSendPacket(rtmpMessage, pendingVideos)) {
							// Drop frame as it depends on other frames that were dropped before.
							log.debug("Dropping packet because frame dropper says we cant send it");
							return;
						}

						// increment the number of times we had pending video frames sequentially
						if (pendingVideos > 1) {
							numSequentialPendingVideoFrames++;
						} else {
							// reset number of sequential pending frames if 1 or 0 are pending.
							numSequentialPendingVideoFrames = 0;
						}
						if (pendingVideos > maxPendingVideoFramesThreshold || numSequentialPendingVideoFrames > maxSequentialPendingVideoFrames) {
							log.debug("Pending: {} Threshold: {} Sequential: {}", new Object[] { pendingVideos, maxPendingVideoFramesThreshold, numSequentialPendingVideoFrames });
							// We drop because the client has insufficient bandwidth.
							long now = System.currentTimeMillis();
							if (bufferCheckInterval > 0 && now >= nextCheckBufferUnderrun) {
								// Notify client about frame dropping (keyframe)
								sendInsufficientBandwidthStatus(currentItem);
								nextCheckBufferUnderrun = now + bufferCheckInterval;
							}
							videoFrameDropper.dropPacket(rtmpMessage);
							return;
						}
					}
				} else if (body instanceof AudioData) {
					if (!receiveAudio && sendBlankAudio) {
						// Send blank audio packet to reset player
						sendBlankAudio = false;
						body = new AudioData();
						if (lastMessageTs > 0) {
							body.setTimestamp(lastMessageTs);
						} else {
							body.setTimestamp(0);
						}
						rtmpMessage.setBody(body);
					} else if (subscriberStream.getState() == StreamState.PAUSED || !receiveAudio) {
						return;
					}
				}
				sendMessage(rtmpMessage);
			} else {
				throw new RuntimeException(String.format("Expected IStreamData but got %s (type %s)", body.getClass(), body.getDataType()));
			}
		} else if (message instanceof ResetMessage) {
			sendReset();
		} else {
			msgOut.pushMessage(message);
		}
	}

	/**
	 * Get number of pending video messages
	 * @return          Number of pending video messages
	 */
	private long pendingVideoMessages() {
		OOBControlMessage pendingRequest = new OOBControlMessage();
		pendingRequest.setTarget("ConnectionConsumer");
		pendingRequest.setServiceName("pendingVideoCount");
		msgOut.sendOOBControlMessage(this, pendingRequest);
		if (pendingRequest.getResult() != null) {
			return (Long) pendingRequest.getResult();
		} else {
			return 0;
		}
	}

	/**
	 * Get number of pending messages to be sent
	 * @return          Number of pending messages
	 */
	private long pendingMessages() {
		return subscriberStream.getConnection().getPendingMessages();
	}

	public boolean isPullMode() {
		return pullMode;
	}

	public boolean isPaused() {
		return subscriberStream.isPaused();
	}

	/**
	 * Returns the timestamp of the last message sent.
	 * 
	 * @return last message timestamp
	 */
	public int getLastMessageTimestamp() {
		return lastMessageTs;
	}

	public long getPlaybackStart() {
		return playbackStart;
	}

	public void sendBlankAudio(boolean sendBlankAudio) {
		this.sendBlankAudio = sendBlankAudio;
	}

	/**
	 * Returns true if the engine currently receives audio.
	 * 
	 * @return receive audio
	 */
	public boolean receiveAudio() {
		return receiveAudio;
	}

	/**
	 * Returns true if the engine currently receives audio and
	 * sets the new value.
	 * 
	 * @param receive new value
	 * @return old value
	 */
	public boolean receiveAudio(boolean receive) {
		boolean oldValue = receiveAudio;
		//set new value
		if (receiveAudio != receive) {
			receiveAudio = receive;
		}
		return oldValue;
	}

	/**
	 * Returns true if the engine currently receives video.
	 * 
	 * @return receive video
	 */
	public boolean receiveVideo() {
		return receiveVideo;
	}

	/**
	 * Returns true if the engine currently receives video and
	 * sets the new value.
	 * @param receive new value
	 * @return old value
	 */
	public boolean receiveVideo(boolean receive) {
		boolean oldValue = receiveVideo;
		//set new value
		if (receiveVideo != receive) {
			receiveVideo = receive;
		}
		return oldValue;
	}

	/**
	 * Releases pending message body, nullifies pending message object
	 */
	private void releasePendingMessage() {
		if (pendingMessage != null) {
			IRTMPEvent body = pendingMessage.getBody();
			if (body instanceof IStreamData && ((IStreamData<?>) body).getData() != null) {
				((IStreamData<?>) body).getData().free();
			}
			pendingMessage.setBody(null);
			pendingMessage = null;
		}
	}
	
	/**
	 * Check if sending the given message was enabled by the client.
	 * 
	 * @param message the message to check
	 * @return <code>true</code> if the message should be sent, <code>false</code> otherwise (and the message is discarded)
	 */
	protected boolean checkSendMessageEnabled(RTMPMessage message) {
		IRTMPEvent body = message.getBody();
		if (!receiveAudio && body instanceof AudioData) {
			// The user doesn't want to get audio packets
			((IStreamData<?>) body).getData().free();
			if (sendBlankAudio) {
				// Send reset audio packet
				sendBlankAudio = false;
				body = new AudioData();
				// We need a zero timestamp
				if (lastMessageTs >= 0) {
					body.setTimestamp(lastMessageTs - timestampOffset);
				} else {
					body.setTimestamp(-timestampOffset);
				}
				message.setBody(body);
			} else {
				return false;
			}
		} else if (!receiveVideo && body instanceof VideoData) {
			// The user doesn't want to get video packets
			((IStreamData<?>) body).getData().free();
			return false;
		}

		return true;
	}

	/**
	 * Periodically triggered by executor to send messages to the client.
	 */
	private final class PullAndPushRunnable implements Runnable {

		/**
		 * Trigger sending of messages.
		 */
		public void run() {
			synchronized (doingPullMonitor) {
				try {
					// Are there any pending operations?
					while(pendingOperations.size() > 0) {
						synchronized(pendingOperations) {
							// Remove the first operation and execute it 
							log.debug("Executing pending operation");
							pendingOperations.removeFirst().run();
						}
					}

					//Receive then send if message is data (not audio or video)
					if (subscriberStream.getState() == StreamState.PLAYING && pullMode) {
						if (pendingMessage != null) {
							IRTMPEvent body = pendingMessage.getBody();
							if (okayToSendMessage(body)) {
								sendMessage(pendingMessage);
								releasePendingMessage();
							} else {
								return;
							}
						} else {
							while (true) {
								IMessage msg = msgIn.pullMessage();
								if (msg == null) {
									// No more packets to send
									log.debug("Ran out of packets");
									runDeferredStop();
									break;
								} else {
									if (msg instanceof RTMPMessage) {
										RTMPMessage rtmpMessage = (RTMPMessage) msg;
										if (!checkSendMessageEnabled(rtmpMessage)) {
											continue;
										}

										// Adjust timestamp when playing lists
										IRTMPEvent body = rtmpMessage.getBody();
										body.setTimestamp(body.getTimestamp() + timestampOffset);
										if (okayToSendMessage(body)) {
											log.trace("ts: {}", rtmpMessage.getBody().getTimestamp());
											sendMessage(rtmpMessage);
											((IStreamData<?>) body).getData().free();
										} else {
											pendingMessage = rtmpMessage;
										}
										ensurePullAndPushRunning();
										break;
									}
								}
							}
						}
					}

				} catch (IOException err) {
					// We couldn't get more data, stop stream.
					log.error("Error while getting message", err);
					runDeferredStop();
				}
			}
		}

		/**
		 * Schedule a stop to be run from a separate thread to allow the background thread to stop cleanly.
		 */
		private void runDeferredStop() {
			subscriberStream.getExecutor().schedule(new Runnable() {
				public void run() {
					log.trace("Ran deferred stop");
					stop();
				}
			}, 1, TimeUnit.MILLISECONDS);
		}

	}

	/**
	 * @param maxPendingVideoFrames the maxPendingVideoFrames to set
	 */
	public void setMaxPendingVideoFrames(int maxPendingVideoFrames) {
		this.maxPendingVideoFramesThreshold = maxPendingVideoFrames;
	}

	/**
	 * @param maxSequentialPendingVideoFrames the maxSequentialPendingVideoFrames to set
	 */
	public void setMaxSequentialPendingVideoFrames(int maxSequentialPendingVideoFrames) {
		this.maxSequentialPendingVideoFrames = maxSequentialPendingVideoFrames;
	}
}
