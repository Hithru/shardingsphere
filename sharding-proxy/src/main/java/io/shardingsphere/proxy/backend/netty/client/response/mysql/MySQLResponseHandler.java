/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.proxy.backend.netty.client.response.mysql;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.shardingsphere.core.metadata.datasource.DataSourceMetaData;
import io.shardingsphere.core.rule.DataSourceParameter;
import io.shardingsphere.proxy.backend.constant.AuthType;
import io.shardingsphere.proxy.backend.netty.client.response.ResponseHandler;
import io.shardingsphere.proxy.backend.netty.future.FutureRegistry;
import io.shardingsphere.proxy.config.RuleRegistry;
import io.shardingsphere.proxy.runtime.ChannelRegistry;
import io.shardingsphere.proxy.transport.mysql.constant.CapabilityFlag;
import io.shardingsphere.proxy.transport.mysql.constant.ServerInfo;
import io.shardingsphere.proxy.transport.mysql.packet.MySQLPacketPayload;
import io.shardingsphere.proxy.transport.mysql.packet.command.query.ColumnDefinition41Packet;
import io.shardingsphere.proxy.transport.mysql.packet.command.query.text.TextResultSetRowPacket;
import io.shardingsphere.proxy.transport.mysql.packet.generic.EofPacket;
import io.shardingsphere.proxy.transport.mysql.packet.generic.ErrPacket;
import io.shardingsphere.proxy.transport.mysql.packet.generic.OKPacket;
import io.shardingsphere.proxy.transport.mysql.packet.handshake.HandshakePacket;
import io.shardingsphere.proxy.transport.mysql.packet.handshake.HandshakeResponse41Packet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Response handler for MySQL.
 *
 * @author wangkai
 * @author linjiaqi
 */
@Slf4j
@RequiredArgsConstructor
public final class MySQLResponseHandler extends ResponseHandler {
    
    private static final RuleRegistry RULE_REGISTRY = RuleRegistry.getInstance();
    
    private final String dataSourceName;
    
    private final Map<Integer, MySQLQueryResult> resultMap = new HashMap<>();
    
    private AuthType authType = AuthType.UN_AUTH;
    
    @Override
    public void channelRead(final ChannelHandlerContext context, final Object message) {
        MySQLPacketPayload payload = new MySQLPacketPayload((ByteBuf) message);
        int header = getHeader(payload);
        switch (authType) {
            case UN_AUTH:
                auth(context, payload);
                return;
            case AUTHING:
                authing(context, payload, header);
                return;
            case AUTH_SUCCESS:
                authSuccess(context, payload, header);
                return;
            case AUTH_FAILED:
                log.error("mysql auth failed, cannot handle channel read message");
                return;
            default:
                throw new UnsupportedOperationException(authType.name());
        }
    }
    
    private int getHeader(final MySQLPacketPayload payload) {
        payload.getByteBuf().markReaderIndex();
        payload.readInt1();
        int result = payload.readInt1();
        payload.getByteBuf().resetReaderIndex();
        return result;
    }
    
    @Override
    protected void auth(final ChannelHandlerContext context, final MySQLPacketPayload payload) {
        try {
            DataSourceParameter dataSourceParameter = RULE_REGISTRY.getDataSourceConfigurationMap().get(dataSourceName);
            DataSourceMetaData dataSourceMetaData = RULE_REGISTRY.getMetaData().getDataSource().getActualDataSourceMetaData(dataSourceName);
            HandshakePacket handshakePacket = new HandshakePacket(payload);
            byte[] authResponse = securePasswordAuthentication(dataSourceParameter.getPassword().getBytes(), handshakePacket.getAuthPluginData().getAuthPluginData());
            HandshakeResponse41Packet handshakeResponse41Packet = new HandshakeResponse41Packet(
                    handshakePacket.getSequenceId() + 1, CapabilityFlag.calculateHandshakeCapabilityFlagsLower(), 16777215, ServerInfo.CHARSET, 
                    dataSourceParameter.getUsername(), authResponse, dataSourceMetaData.getSchemeName());
            ChannelRegistry.getInstance().putConnectionId(context.channel().id().asShortText(), handshakePacket.getConnectionId());
            context.writeAndFlush(handshakeResponse41Packet);
        } finally {
            payload.close();
        }
        authType = AuthType.AUTHING;
    }
    
    private void authing(final ChannelHandlerContext context, final MySQLPacketPayload payload, final int header) {
        if (OKPacket.HEADER == header) {
            okPacket(context, payload);
            authType = AuthType.AUTH_SUCCESS;
        } else {
            errPacket(context, payload);
            authType = AuthType.AUTH_FAILED;
        }
    }
    
    private void authSuccess(final ChannelHandlerContext context, final MySQLPacketPayload payload, final int header) {
        if (EofPacket.HEADER == header) {
            eofPacket(context, payload);
        } else if (OKPacket.HEADER == header) {
            okPacket(context, payload);
        } else if (ErrPacket.HEADER == header) {
            errPacket(context, payload);
        } else {
            commonPacket(context, payload);
        }
    }
    
    @Override
    protected void okPacket(final ChannelHandlerContext context, final MySQLPacketPayload payload) {
        int connectionId = ChannelRegistry.getInstance().getConnectionId(context.channel().id().asShortText());
        try {
            MySQLQueryResult mysqlQueryResult = new MySQLQueryResult();
            mysqlQueryResult.setGenericResponse(new OKPacket(payload));
            resultMap.put(connectionId, mysqlQueryResult);
            setResponse(context);
        } finally {
            resultMap.remove(connectionId);
            payload.close();
        }
    }
    
    @Override
    protected void errPacket(final ChannelHandlerContext context, final MySQLPacketPayload payload) {
        int connectionId = ChannelRegistry.getInstance().getConnectionId(context.channel().id().asShortText());
        try {
            MySQLQueryResult mysqlQueryResult = new MySQLQueryResult();
            mysqlQueryResult.setGenericResponse(new ErrPacket(payload));
            resultMap.put(connectionId, mysqlQueryResult);
            setResponse(context);
        } finally {
            resultMap.remove(connectionId);
            payload.close();
        }
    }
    
    @Override
    protected void eofPacket(final ChannelHandlerContext context, final MySQLPacketPayload payload) {
        int connectionId = ChannelRegistry.getInstance().getConnectionId(context.channel().id().asShortText());
        MySQLQueryResult mysqlQueryResult = resultMap.get(connectionId);
        if (mysqlQueryResult.isColumnFinished()) {
            mysqlQueryResult.setRowFinished(new EofPacket(payload));
            resultMap.remove(connectionId);
            payload.close();
        } else {
            mysqlQueryResult.setColumnFinished(new EofPacket(payload));
            setResponse(context);
        }
    }
    
    @Override
    protected void commonPacket(final ChannelHandlerContext context, final MySQLPacketPayload payload) {
        int connectionId = ChannelRegistry.getInstance().getConnectionId(context.channel().id().asShortText());
        MySQLQueryResult mysqlQueryResult = resultMap.get(connectionId);
        if (mysqlQueryResult == null) {
            mysqlQueryResult = new MySQLQueryResult(payload);
            resultMap.put(connectionId, mysqlQueryResult);
        } else if (mysqlQueryResult.needColumnDefinition()) {
            mysqlQueryResult.addColumnDefinition(new ColumnDefinition41Packet(payload));
        } else {
            mysqlQueryResult.addTextResultSetRow(new TextResultSetRowPacket(payload, mysqlQueryResult.getColumnCount()));
        }
    }
    
    private byte[] securePasswordAuthentication(final byte[] password, final byte[] authPluginData) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
            byte[] part1 = messageDigest.digest(password);
            messageDigest.reset();
            byte[] part2 = messageDigest.digest(part1);
            messageDigest.reset();
            messageDigest.update(authPluginData);
            byte[] result = messageDigest.digest(part2);
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) (result[i] ^ part1[i]);
            }
            return result;
        } catch (final NoSuchAlgorithmException ex) {
            log.error(ex.getMessage(), ex);
        }
        return null;
    }
    
    private void setResponse(final ChannelHandlerContext context) {
        int connectionId = ChannelRegistry.getInstance().getConnectionId(context.channel().id().asShortText());
        if (FutureRegistry.getInstance().get(connectionId) != null) {
            FutureRegistry.getInstance().get(connectionId).setResponse(resultMap.get(connectionId));
        }
    }
    
    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        //TODO delete connection map.
        super.channelInactive(ctx);
    }
}
