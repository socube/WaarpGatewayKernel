/**
   This file is part of Waarp Project.

   Copyright 2009, Frederic Bregier, and individual contributors by the @author
   tags. See the COPYRIGHT.txt in the distribution for a full listing of
   individual contributors.

   All Waarp Project is free software: you can redistribute it and/or 
   modify it under the terms of the GNU General Public License as published 
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   Waarp is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with Waarp .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.waarp.gateway.kernel.rest;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.multipart.FileUpload;
import org.waarp.common.json.JsonHandler;
import org.waarp.gateway.kernel.exception.HttpForbiddenRequestException;
import org.waarp.gateway.kernel.exception.HttpIncorrectRequestException;
import org.waarp.gateway.kernel.exception.HttpInvalidAuthenticationException;
import org.waarp.gateway.kernel.rest.DataModelRestMethodHandler.COMMAND_TYPE;
import org.waarp.gateway.kernel.rest.HttpRestHandler.METHOD;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * RestMethod handler to implement Root Options handler
 * @author "Frederic Bregier"
 *
 */
public class RootOptionsRestMethodHandler extends RestMethodHandler {

	public static final String ROOT = "root";

	public RootOptionsRestMethodHandler() {
		super("/", true, METHOD.OPTIONS);
	}

	public void checkHandlerSessionCorrectness(HttpRestHandler handler, RestArgument arguments,
			RestArgument result) throws HttpForbiddenRequestException {
	}

	public void getFileUpload(HttpRestHandler handler, FileUpload data, RestArgument arguments,
			RestArgument result) throws HttpIncorrectRequestException {
	}

	public Object getBody(HttpRestHandler handler, ChannelBuffer body, RestArgument arguments,
			RestArgument result) throws HttpIncorrectRequestException {
		return null;
	}

	public void endParsingRequest(HttpRestHandler handler, RestArgument arguments,
			RestArgument result, Object body) throws HttpIncorrectRequestException,
			HttpInvalidAuthenticationException {
	}

	public ChannelFuture sendResponse(HttpRestHandler handler, Channel channel,
			RestArgument arguments, RestArgument result, Object body, HttpResponseStatus status) {
		return sendOptionsResponse(handler, channel, result, status);
	}

	@Override
	public void optionsCommand(HttpRestHandler handler, RestArgument arguments, RestArgument result) {
		METHOD [] realmethods = METHOD.values();
		boolean []allMethods = new boolean[realmethods.length];
		for (RestMethodHandler method : HttpRestHandler.restHashMap.values()) {
			for (METHOD methoditem : method.methods) {
				allMethods[methoditem.ordinal()] = true;
			}
		}
		String allow = null;
		for (int i = 0; i < allMethods.length; i++) {
			if (allMethods[i]) {
				if (allow == null) {
					allow = realmethods[i].name();
				} else {
					allow += "," + realmethods[i].name();
				}
			}
		}
		result.addItem(HttpHeaders.Names.ALLOW, allow);
		allow = null;
		for (RestMethodHandler method : HttpRestHandler.restHashMap.values()) {
			if (allow == null) {
				allow = method.path;
			} else {
				allow += ","+method.path;
			}
		}
		result.addItem(RestArgument.X_ALLOW_URIS, allow);
		allow = null;
		ObjectNode node = result.getAnswer();
		ArrayNode array = node.putArray(RestArgument.X_DETAILED_ALLOW);
		for (RestMethodHandler method : HttpRestHandler.restHashMap.values()) {
			ArrayNode array2 = method.getDetailedAllow();
			if (method != this) {
				array.addObject().putArray(method.path).addAll(array2);
			} else {
				array.addObject().putArray(ROOT).addAll(array2);
			}
		}
	}

	@Override
	protected ArrayNode getDetailedAllow() {
		ArrayNode node = JsonHandler.createArrayNode();
		
		ObjectNode node2 = node.addObject().putObject(METHOD.OPTIONS.name());
		node2.put(RestArgument.JSON_PATH, this.path);
		node2.put(RestArgument.JSON_COMMAND, COMMAND_TYPE.OPTIONS.name());

		return node;
	}

}
