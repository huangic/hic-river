package org.red5.server.net.remoting.codec;

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

import org.red5.io.object.Deserializer;
import org.red5.io.object.Serializer;

/**
 * Factory for remoting codec
 */
public class RemotingCodecFactory {
    /**
     * Deserializer
     */
	protected Deserializer deserializer;
    /**
     * Serializers
     */
	protected Serializer serializer;
    /**
     * Remoting protocol decoder
     */
	protected RemotingProtocolDecoder decoder;
    /**
     * Remoting protocol encoder
     */
	protected RemotingProtocolEncoder encoder;

    /**
     * Initialization, creates and binds encoder and decoder to serializer and deserializer
     */
    public void init() {
		decoder = new RemotingProtocolDecoder();
		decoder.setDeserializer(deserializer);
		encoder = new RemotingProtocolEncoder();
		encoder.setSerializer(serializer);
	}

	/**
     * Setter for deserializer.
     *
     * @param deserializer Deserializer.
     */
    public void setDeserializer(Deserializer deserializer) {
		this.deserializer = deserializer;
	}

	/**
     * Setter for serializer.
     *
     * @param serializer Sserializer.
     */
    public void setSerializer(Serializer serializer) {
		this.serializer = serializer;
	}


    /**
     * Returns the remoting decoder.
     * 
     * @return decoder
     */
    public RemotingProtocolDecoder getRemotingDecoder() {
		return decoder;
	}

    /**
     * Returns the remoting encoder.
     * 
     * @return encoder
     */
    public RemotingProtocolEncoder getRemotingEncoder() {
		return encoder;
	}

}
