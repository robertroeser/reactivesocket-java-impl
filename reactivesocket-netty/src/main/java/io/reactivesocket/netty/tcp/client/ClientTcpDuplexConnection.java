/**
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivesocket.netty.tcp.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.reactivesocket.DuplexConnection;
import io.reactivesocket.Frame;
import io.reactivesocket.rx.Completable;
import io.reactivesocket.rx.Observable;
import io.reactivesocket.rx.Observer;
import org.agrona.BitUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientTcpDuplexConnection implements DuplexConnection {
    private Channel channel;

    private Bootstrap bootstrap;

    private final CopyOnWriteArrayList<Observer<Frame>> subjects;

    private ClientTcpDuplexConnection(Channel channel, Bootstrap bootstrap, CopyOnWriteArrayList<Observer<Frame>> subjects) {
        this.subjects  = subjects;
        this.channel = channel;
        this.bootstrap = bootstrap;
    }

    public static Publisher<ClientTcpDuplexConnection> create(InetSocketAddress address, EventLoopGroup eventLoopGroup) {
        return s -> {
            CopyOnWriteArrayList<Observer<Frame>> subjects = new CopyOnWriteArrayList<>();
            ReactiveSocketClientHandler clientHandler = new ReactiveSocketClientHandler(subjects);
            Bootstrap bootstrap = new Bootstrap();
            ChannelFuture connect = bootstrap
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(
                            new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE >> 1, 0, BitUtil.SIZE_OF_INT, -1 * BitUtil.SIZE_OF_INT, 0),
                            clientHandler
                        );
                    }
                }).connect(address.getHostName(), address.getPort());

            connect.addListener(connectFuture -> {
                if (connectFuture.isSuccess()) {
                    final Channel ch = connect.channel();
                    s.onNext(new ClientTcpDuplexConnection(ch, bootstrap, subjects));
                    s.onComplete();
                } else {
                    s.onError(connectFuture.cause());
                }
            });
        };
    }

    @Override
    public final Observable<Frame> getInput() {
        return o -> {
            o.onSubscribe(() -> subjects.removeIf(s -> s == o));
            subjects.add(o);
        };
    }

    @Override
    public void addOutput(Publisher<Frame> o, Completable callback) {

        o.subscribe(new Subscriber<Frame>() {
            Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(128);
            }

            @Override
            public void onNext(Frame frame) {
                try {
                    ByteBuffer data = frame.getByteBuffer();
                    ByteBuf byteBuf = Unpooled.wrappedBuffer(data);
                    ChannelFuture channelFuture = channel.writeAndFlush(byteBuf);
                    channelFuture.addListener(future -> {
                        subscription.request(1);
                        Throwable cause = future.cause();
                        if (cause != null) {
                            cause.printStackTrace();
                            callback.error(cause);
                        }
                    });
                } catch (Throwable t) {
                    onError(t);
                }
            }

            @Override
            public void onError(Throwable t) {
                callback.error(t);
            }

            @Override
            public void onComplete() {
                callback.success();
            }
        });
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public String toString() {
        if (channel == null) {
            return  getClass().getName() + ":channel=null";
        }

        return getClass().getName() + ":channel=[" +
            "remoteAddress=" + channel.remoteAddress() + "," +
            "isActive=" + channel.isActive() + "," +
            "isOpen=" + channel.isOpen() + "," +
            "isRegistered=" + channel.isRegistered() + "," +
            "isWritable=" + channel.isWritable() + "," +
            "channelId=" + channel.id().asLongText() +
            "]";

    }
}
