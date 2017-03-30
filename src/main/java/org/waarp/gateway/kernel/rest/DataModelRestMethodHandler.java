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

import java.nio.charset.UnsupportedCharsetException;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.multipart.FileUpload;

import org.waarp.common.database.DbPreparedStatement;
import org.waarp.common.database.data.AbstractDbData;
import org.waarp.common.database.data.AbstractDbData.UpdatedInfo;
import org.waarp.common.database.exception.WaarpDatabaseException;
import org.waarp.common.database.exception.WaarpDatabaseNoConnectionException;
import org.waarp.common.database.exception.WaarpDatabaseSqlException;
import org.waarp.common.json.JsonHandler;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.common.utility.WaarpStringUtils;
import org.waarp.gateway.kernel.exception.HttpForbiddenRequestException;
import org.waarp.gateway.kernel.exception.HttpIncorrectRequestException;
import org.waarp.gateway.kernel.exception.HttpInvalidAuthenticationException;
import org.waarp.gateway.kernel.exception.HttpNotFoundRequestException;
import org.waarp.gateway.kernel.rest.HttpRestHandler.METHOD;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Generic Rest Model handler for Data model (CRUD access to a database table)
 * 
 * @author "Frederic Bregier"
 *
 */
public abstract class DataModelRestMethodHandler<E extends AbstractDbData> extends RestMethodHandler {

    public static enum COMMAND_TYPE {
        MULTIGET, GET, UPDATE, CREATE, DELETE, OPTIONS;
    }

    /**
     * Internal Logger
     */
    private static final WaarpLogger logger = WaarpLoggerFactory
            .getLogger(DataModelRestMethodHandler.class);

    public DataModelRestMethodHandler(String name, RestConfiguration config, METHOD... method) {
        super(name, name, true, config, METHOD.OPTIONS);
        setMethods(method);
    }

    protected abstract void checkAuthorization(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, METHOD method) throws HttpForbiddenRequestException;

    /**
     * allowed: GET iff name or name/id, PUT iff name/id, POST iff name (no id),
     * DELETE iff name/id and allowed
     */
    @Override
    public void checkHandlerSessionCorrectness(HttpRestHandler handler, RestArgument arguments,
            RestArgument result) throws HttpForbiddenRequestException {
        METHOD method = arguments.getMethod();
        if (!isMethodIncluded(method)) {
            logger.warn("NotAllowed: " + method + ":" + arguments.getUri() + ":" + arguments.getUriArgs());
            throw new HttpForbiddenRequestException("Unallowed Method: " + method);
        }
        checkAuthorization(handler, arguments, result, method);
        boolean hasOneExtraPathAsId = arguments.getSubUriSize() == 1;
        boolean hasNoExtraPath = arguments.getSubUriSize() == 0;
        if (hasOneExtraPathAsId) {
            arguments.addIdToUriArgs();
        }
        switch (method) {
            case DELETE:
                if (hasOneExtraPathAsId) {
                    return;
                }
                break;
            case GET:
                return;
            case OPTIONS:
                return;
            case POST:
                if (hasNoExtraPath) {
                    return;
                }
                break;
            case PUT:
                if (hasOneExtraPathAsId) {
                    return;
                }
                break;
            default:
                break;
        }
        logger.warn("NotAllowed: " + method + ":" + hasNoExtraPath + ":" + hasOneExtraPathAsId + ":"
                + arguments.getUri() + ":" + arguments.getUriArgs());
        throw new HttpForbiddenRequestException("Unallowed Method and arguments combinaison");
    }

    public void getFileUpload(HttpRestHandler handler, FileUpload data, RestArgument arguments,
            RestArgument result) throws HttpIncorrectRequestException {
        throw new HttpIncorrectRequestException("File Upload not allowed");
    }

    public Object getBody(HttpRestHandler handler, ByteBuf body, RestArgument arguments,
            RestArgument result) throws HttpIncorrectRequestException {
        // get the Json equivalent of the Body
        ObjectNode node = null;
        try {
            String json = body.toString(WaarpStringUtils.UTF8);
            node = JsonHandler.getFromStringExc(json);
        } catch (UnsupportedCharsetException e) {
            logger.warn("Error", e);
            throw new HttpIncorrectRequestException(e);
        } catch (JsonProcessingException e) {
            result.setDetail("ERROR: JSON body cannot be parsed");
        }
        if (node != null) {
            arguments.getBody().setAll(node);
        }
        return node;
    }

    public void endParsingRequest(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException, HttpNotFoundRequestException {
        METHOD method = arguments.getMethod();
        switch (method) {
            case DELETE:
                delete(handler, arguments, result, body);
                return;
            case GET:
                boolean hasNoExtraPath = arguments.getSubUriSize() == 0;
                if (hasNoExtraPath) {
                    getAll(handler, arguments, result, body);
                } else {
                    getOne(handler, arguments, result, body);
                }
                return;
            case OPTIONS:
                optionsCommand(handler, arguments, result);
                return;
            case POST:
                post(handler, arguments, result, body);
                return;
            case PUT:
                put(handler, arguments, result, body);
                return;
            default:
                break;
        }
        throw new HttpIncorrectRequestException("Incorrect request: " + method);
    }

    /**
     * For Read or Update, should include a select() from the database.
     * Shall not be used for Create. JSON_ID should be checked for the primary id.
     * 
     * @param handler
     * @param arguments
     * @param result
     * @param body
     * @return the Object E according to URI and other arguments
     * @throws HttpIncorrectRequestException
     * @throws HttpInvalidAuthenticationException
     * @throws HttpNotFoundRequestException
     */
    protected abstract E getItem(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException, HttpNotFoundRequestException;

    /**
     * To be used only in create mode. No insert should be done into the database.
     * 
     * @param handler
     * @param arguments
     * @param result
     * @param body
     * @return a new Object E according to URI and other arguments
     * @throws HttpIncorrectRequestException
     * @throws HttpInvalidAuthenticationException
     */
    protected abstract E createItem(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException;

    /**
     * For getAll access
     * 
     * @param handler
     * @param arguments
     * @param result
     * @param body
     * @return the associated preparedStatement
     * @throws HttpIncorrectRequestException
     * @throws HttpInvalidAuthenticationException
     */
    protected abstract DbPreparedStatement getPreparedStatement(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException;

    /**
     * 
     * @param statement
     * @return the Object E according to statement (using next) or null if no more item
     * @throws HttpIncorrectRequestException
     * @throws HttpNotFoundRequestException
     */
    protected abstract E getItemPreparedStatement(DbPreparedStatement statement) throws HttpIncorrectRequestException,
            HttpNotFoundRequestException;

    /**
     * 
     * @return the primary property name used in the uri for Get,Put,Delete for unique access
     */
    public abstract String getPrimaryPropertyName();

    protected void setOk(HttpRestHandler handler, RestArgument result) {
        handler.setStatus(HttpResponseStatus.OK);
        result.setResult(HttpResponseStatus.OK);
    }

    /**
     * Get all items, according to a possible filter
     * 
     * @param handler
     * @param arguments
     * @param result
     * @param body
     * @throws HttpIncorrectRequestException
     * @throws HttpInvalidAuthenticationException
     * @throws HttpNotFoundRequestException
     */
    protected void getAll(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException, HttpNotFoundRequestException {
        long limit = arguments.getLimitFromUri();
        DbPreparedStatement statement = getPreparedStatement(handler, arguments, result, body);
        try {
            result.addFilter((ObjectNode) body);
            int count = 0;
            try {
                statement.executeQuery();
            } catch (WaarpDatabaseNoConnectionException e) {
                throw new HttpIncorrectRequestException(e);
            } catch (WaarpDatabaseSqlException e) {
                throw new HttpNotFoundRequestException(e);
            }
            try {
                for (; count < limit && statement.getNext(); count++) {
                    E item = getItemPreparedStatement(statement);
                    if (item != null) {
                        result.addResult(item.getJson());
                    }
                }
            } catch (WaarpDatabaseNoConnectionException e) {
                throw new HttpIncorrectRequestException(e);
            } catch (WaarpDatabaseSqlException e) {
                throw new HttpNotFoundRequestException(e);
            }
            result.addCountLimit(count, limit);
            result.setCommand(COMMAND_TYPE.MULTIGET);
            setOk(handler, result);
        } finally {
            statement.realClose();
        }
    }

    /**
     * Get one item according to id
     * 
     * @param handler
     * @param arguments
     * @param result
     * @param body
     * @throws HttpIncorrectRequestException
     * @throws HttpInvalidAuthenticationException
     * @throws HttpNotFoundRequestException
     */
    protected void getOne(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException, HttpNotFoundRequestException {
        E item = getItem(handler, arguments, result, body);
        result.addAnswer(item.getJson());
        result.setCommand(COMMAND_TYPE.GET);
        setOk(handler, result);
    }

    /**
     * Update one item according to id
     * 
     * @param handler
     * @param arguments
     * @param result
     * @param body
     * @throws HttpIncorrectRequestException
     * @throws HttpInvalidAuthenticationException
     * @throws HttpNotFoundRequestException
     */
    protected void put(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException, HttpNotFoundRequestException {
        E item = getItem(handler, arguments, result, body);
        try {
            item.setFromJson(arguments.getBody(), true);
        } catch (WaarpDatabaseSqlException e) {
            throw new HttpIncorrectRequestException("Issue while using Json formatting", e);
        }
        item.changeUpdatedInfo(UpdatedInfo.TOSUBMIT);
        try {
            item.update();
        } catch (WaarpDatabaseException e) {
            throw new HttpIncorrectRequestException("Issue while updating to database", e);
        }
        result.addAnswer(item.getJson());
        result.setCommand(COMMAND_TYPE.UPDATE);
        setOk(handler, result);
    }

    /**
     * Create one item
     * 
     * @param handler
     * @param arguments
     * @param result
     * @param body
     * @throws HttpIncorrectRequestException
     * @throws HttpInvalidAuthenticationException
     */
    protected void post(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException {
        E item = createItem(handler, arguments, result, body);
        item.changeUpdatedInfo(UpdatedInfo.TOSUBMIT);
        try {
            item.insert();
        } catch (WaarpDatabaseException e) {
            throw new HttpIncorrectRequestException("Issue while inserting to database", e);
        }
        result.addAnswer(item.getJson());
        result.setCommand(COMMAND_TYPE.CREATE);
        setOk(handler, result);
    }

    /**
     * delete one item
     * 
     * @param handler
     * @param arguments
     * @param result
     * @param body
     * @throws HttpIncorrectRequestException
     * @throws HttpInvalidAuthenticationException
     * @throws HttpNotFoundRequestException
     */
    protected void delete(HttpRestHandler handler, RestArgument arguments,
            RestArgument result, Object body) throws HttpIncorrectRequestException,
            HttpInvalidAuthenticationException, HttpNotFoundRequestException {
        E item = getItem(handler, arguments, result, body);
        try {
            item.delete();
        } catch (WaarpDatabaseException e) {
            throw new HttpIncorrectRequestException("Issue while deleting from database", e);
        }
        result.addAnswer(item.getJson());
        result.setCommand(COMMAND_TYPE.DELETE);
        setOk(handler, result);
    }

    @Override
    public ChannelFuture sendResponse(HttpRestHandler handler, ChannelHandlerContext ctx,
            RestArgument arguments, RestArgument result, Object body, HttpResponseStatus status) {
        String answer = result.toString();
        ByteBuf buffer = Unpooled.wrappedBuffer(answer.getBytes(WaarpStringUtils.UTF8));
        HttpResponse response = handler.getResponse(buffer);
        if (status == HttpResponseStatus.UNAUTHORIZED) {
            ChannelFuture future = ctx.writeAndFlush(response);
            return future;
        }
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().add(HttpHeaderNames.REFERER, handler.getRequest().uri());
        logger.debug("Will write: {}", body);
        ChannelFuture future = ctx.writeAndFlush(response);
        if (handler.isWillClose()) {
            System.err.println("Will close session in DataModelRestMethodHandler");
            return future;
        }
        return null;
    }
}
