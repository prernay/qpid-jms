/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms.transports;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.qpid.jms.util.IOExceptionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.net.impl.PartialPooledByteBufAllocator;

/**
 * TCP based transport that uses Netty as the underlying IO layer.
 */
public class NettyTcpTransport implements Transport {

    private static final Logger LOG = LoggerFactory.getLogger(NettyTcpTransport.class);

    private Bootstrap bootstrap;
    private EventLoopGroup group;
    private Channel channel;
    private TransportListener listener;
    private final TcpTransportOptions options;
    private final URI remote;

    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Create a new transport instance
     *
     * @param options
     *        the transport options used to configure the socket connection.
     */
    public NettyTcpTransport(TransportListener listener, URI remoteLocation, TcpTransportOptions options) {
        this.options = options;
        this.listener = listener;
        this.remote = remoteLocation;
    }

    @Override
    public void connect() throws IOException {

        if (listener == null) {
            throw new IllegalStateException("A transport listener must be set before connection attempts.");
        }

        group = new NioEventLoopGroup();

        bootstrap = new Bootstrap();
        bootstrap.group(group);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            public void initChannel(Channel connectedChannel) throws Exception {
                channel = connectedChannel;
                channel.pipeline().addLast(new NettyTcpTransportHandler());
            }
        });

        configureNetty(bootstrap, options);

        ChannelFuture future = bootstrap.connect(remote.getHost(), remote.getPort());
        future.awaitUninterruptibly();

        if (future.isCancelled()) {
            throw new IOException("Connection attempt was cancelled");
        } else if (!future.isSuccess()) {
            throw IOExceptionSupport.create(future.cause());
        } else {
            connected.set(true);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            channel.close();
            group.shutdownGracefully();
        }
    }

    @Override
    public void send(ByteBuffer output) throws IOException {
        send(Unpooled.wrappedBuffer(output));
    }

    @Override
    public void send(ByteBuf output) throws IOException {
        channel.write(output);
        channel.flush();
    }

    @Override
    public TransportListener getTransportListener() {
        return listener;
    }

    @Override
    public void setTransportListener(TransportListener listener) {
        this.listener = listener;
    }

    //----- Internal implementation details ----------------------------------//

    protected void configureNetty(Bootstrap bootstrap, TcpTransportOptions options) {
        bootstrap.option(ChannelOption.TCP_NODELAY, options.isTcpNoDelay());
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, options.getConnectTimeout());
        bootstrap.option(ChannelOption.SO_KEEPALIVE, options.isTcpKeepAlive());
        bootstrap.option(ChannelOption.SO_LINGER, options.getSoLinger());
        bootstrap.option(ChannelOption.ALLOCATOR, PartialPooledByteBufAllocator.INSTANCE);

        if (options.getSendBufferSize() != -1) {
            bootstrap.option(ChannelOption.SO_SNDBUF, options.getSendBufferSize());
        }

        if (options.getReceiveBufferSize() != -1) {
            bootstrap.option(ChannelOption.SO_RCVBUF, options.getSendBufferSize());
            bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(options.getSendBufferSize()));
        }

        if (options.getTrafficClass() != -1) {
            bootstrap.option(ChannelOption.IP_TOS, options.getTrafficClass());
        }
    }

    //----- Handle connection events -----------------------------------------//

    private class NettyTcpTransportHandler extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        public void channelActive(ChannelHandlerContext context) throws Exception {
            LOG.info("Channel has become active! Channel is {}", context.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            LOG.info("Channel has gone inactive! Channel is {}", context.channel());
            if (!closed.get()) {
                connected.set(false);
                listener.onTransportClosed();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
            LOG.info("Exception on channel! Channel is {}", context.channel());
            if (!closed.get()) {
                connected.set(false);
                listener.onTransportError(cause);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
            LOG.info("New data read: {} bytes incoming", buffer.readableBytes());
            listener.onData(new Buffer(buffer));
        }
    }
}
