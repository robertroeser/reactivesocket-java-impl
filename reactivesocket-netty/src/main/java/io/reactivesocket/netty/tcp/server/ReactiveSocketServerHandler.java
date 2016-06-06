/**
 * Copyright 2015 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivesocket.netty.tcp.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.reactivesocket.ConnectionSetupHandler;
import io.reactivesocket.DefaultReactiveSocket;
import io.reactivesocket.Frame;
import io.reactivesocket.LeaseGovernor;
import io.reactivesocket.ReactiveSocket;
import io.reactivesocket.netty.MutableDirectByteBuf;
import io.reactivesocket.rx.Completable;
import org.agrona.BitUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReactiveSocketServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ReactiveSocketServerHandler.class);

    private ServerTcpDuplexConnection connection;

    private ConnectionSetupHandler setupHandler;

    private LeaseGovernor leaseGovernor;

    private ChannelPromise channelPromise;

    protected ReactiveSocketServerHandler(ConnectionSetupHandler setupHandler, LeaseGovernor leaseGovernor) {
        this.setupHandler = setupHandler;
        this.leaseGovernor = leaseGovernor;
    }

    public static ReactiveSocketServerHandler create(ConnectionSetupHandler setupHandler) {
        return create(setupHandler, LeaseGovernor.UNLIMITED_LEASE_GOVERNOR);
    }

    public static ReactiveSocketServerHandler create(ConnectionSetupHandler setupHandler, LeaseGovernor leaseGovernor) {
        return new
            ReactiveSocketServerHandler(
            setupHandler,
            leaseGovernor);

    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ChannelPipeline cp = ctx.pipeline();
        if (cp.get(LengthFieldBasedFrameDecoder.class) == null) {
            ctx
                .pipeline()
                .addBefore(
                    ctx.name(),
                    LengthFieldBasedFrameDecoder.class.getName(),
                    new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE >> 1, 0, BitUtil.SIZE_OF_INT, -1 * BitUtil.SIZE_OF_INT, 0));
        }

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf content = (ByteBuf) msg;
        boolean newConnection = false;
        try {
            MutableDirectByteBuf mutableDirectByteBuf = new MutableDirectByteBuf(content);
            Frame from = Frame.from(mutableDirectByteBuf, 0, mutableDirectByteBuf.capacity());
            channelRegistered(ctx);
            final ChannelId channelId = ctx.channel().id();


            if (connection == null) {
                newConnection = true;
                logger.debug("No connection found for channel id: " + channelId + " from host " + ctx.channel().remoteAddress().toString());
                connection = new ServerTcpDuplexConnection(ctx);
                ReactiveSocket reactiveSocket
                    = DefaultReactiveSocket
                    .fromServerConnection(connection, setupHandler, leaseGovernor, throwable -> logger.error(throwable.getMessage(), throwable));

                channelPromise = ctx.channel().newPromise();

                reactiveSocket.start(new Completable() {
                    @Override
                    public void success() {
                        channelPromise.setSuccess();
                    }

                    @Override
                    public void error(Throwable e) {
                        channelPromise.setFailure(e);
                    }
                });

                channelPromise.addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(Future<? super Void> future) throws Exception {
                        try {
                            if (future.isSuccess()) {

                                if (connection != null) {
                                    connection
                                        .getSubscribers()
                                        .forEach(o -> o.onNext(from));
                                }
                            } else if (future.cause() != null) {
                                logger.error(future.cause().getMessage(), future.cause());
                                connection
                                    .getSubscribers()
                                    .forEach(o -> o.onError(future.cause()));
                            }
                        } finally {
                            content.release();
                        }
                    }
                });

            } else {
                connection
                    .getSubscribers()
                    .forEach(o -> o.onNext(from));
            }
        } finally {
            if (!newConnection) {
                content.release();
            }
        }
    }


    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        logger.error("caught an unhandled exception", cause);
        connection.getSubscribers().forEach(o -> o.onError(cause));
    }
}
