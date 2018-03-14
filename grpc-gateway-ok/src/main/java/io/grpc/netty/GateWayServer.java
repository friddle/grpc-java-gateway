package io.grpc.netty;

import io.grpc.InternalLogId;
import io.grpc.InternalWithLogId;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_AGE_NANOS_DISABLED;
import static io.netty.channel.ChannelOption.SO_BACKLOG;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;

/**
 * Created by talkweb on 2018/2/27.
 */

@SuppressWarnings("Duplicates")
public class GateWayServer implements InternalServer, InternalWithLogId {
    private static final Logger log = Logger.getLogger(InternalServer.class.getName());

    private final InternalLogId logId = InternalLogId.allocate(getClass().getName());
    private final SocketAddress address;
    private final Class<? extends ServerChannel> channelType;
    private final Map<ChannelOption<?>, ?> channelOptions;
    private final ProtocolNegotiator protocolNegotiator;
    private final int maxStreamsPerConnection;
    private final boolean usingSharedBossGroup;
    private final boolean usingSharedWorkerGroup;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ServerListener listener;
    private Channel channel;
    private final int flowControlWindow;
    private final int maxMessageSize;
    private final int maxHeaderListSize;
    private final long keepAliveTimeInNanos;
    private final long keepAliveTimeoutInNanos;
    private final long maxConnectionIdleInNanos;
    private final long maxConnectionAgeInNanos;
    private final long maxConnectionAgeGraceInNanos;
    private final boolean permitKeepAliveWithoutCalls;
    private final long permitKeepAliveTimeInNanos;
    private final ReferenceCounted eventLoopReferenceCounter = new EventLoopReferenceCounter();
    private final List<ServerStreamTracer.Factory> streamTracerFactories;
    private final TransportTracer.Factory transportTracerFactory;

    public GateWayServer(
            SocketAddress address, Class<? extends ServerChannel> channelType,
            Map<ChannelOption<?>, ?> channelOptions,
            @Nullable EventLoopGroup bossGroup, @Nullable EventLoopGroup workerGroup,
            ProtocolNegotiator protocolNegotiator, List<ServerStreamTracer.Factory> streamTracerFactories,
            TransportTracer.Factory transportTracerFactory,
            int maxStreamsPerConnection, int flowControlWindow, int maxMessageSize, int maxHeaderListSize,
            long keepAliveTimeInNanos, long keepAliveTimeoutInNanos,
            long maxConnectionIdleInNanos,
            long maxConnectionAgeInNanos, long maxConnectionAgeGraceInNanos,
            boolean permitKeepAliveWithoutCalls, long permitKeepAliveTimeInNanos) {
        this.address = address;
        this.channelType = checkNotNull(channelType, "channelType");
        checkNotNull(channelOptions, "channelOptions");
        this.channelOptions = new HashMap<ChannelOption<?>, Object>(channelOptions);
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.protocolNegotiator = checkNotNull(protocolNegotiator, "protocolNegotiator");
        this.streamTracerFactories = checkNotNull(streamTracerFactories, "streamTracerFactories");
        this.usingSharedBossGroup = bossGroup == null;
        this.usingSharedWorkerGroup = workerGroup == null;
        this.transportTracerFactory = transportTracerFactory;
        this.maxStreamsPerConnection = maxStreamsPerConnection;
        this.flowControlWindow = flowControlWindow;
        this.maxMessageSize = maxMessageSize;
        this.maxHeaderListSize = maxHeaderListSize;
        this.keepAliveTimeInNanos = keepAliveTimeInNanos;
        this.keepAliveTimeoutInNanos = keepAliveTimeoutInNanos;
        this.maxConnectionIdleInNanos = maxConnectionIdleInNanos;
        this.maxConnectionAgeInNanos = maxConnectionAgeInNanos;
        this.maxConnectionAgeGraceInNanos = maxConnectionAgeGraceInNanos;
        this.permitKeepAliveWithoutCalls = permitKeepAliveWithoutCalls;
        this.permitKeepAliveTimeInNanos = permitKeepAliveTimeInNanos;
    }

    @Override
    public int getPort() {
        if (channel == null) {
            return -1;
        }
        SocketAddress localAddr = channel.localAddress();
        if (!(localAddr instanceof InetSocketAddress)) {
            return -1;
        }
        return ((InetSocketAddress) localAddr).getPort();
    }

    @Override
    public void start(ServerListener serverListener) throws IOException {
        listener = checkNotNull(serverListener, "serverListener");

        // If using the shared groups, get references to them.
        allocateSharedGroups();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup);
        b.channel(channelType);
        if (NioServerSocketChannel.class.isAssignableFrom(channelType)) {
            b.option(SO_BACKLOG, 128);
            b.childOption(SO_KEEPALIVE, true);
        }

        if (channelOptions != null) {
            for (Map.Entry<ChannelOption<?>, ?> entry : channelOptions.entrySet()) {
                @SuppressWarnings("unchecked")
                ChannelOption<Object> key = (ChannelOption<Object>) entry.getKey();
                b.childOption(key, entry.getValue());
            }
        }

        b.childHandler(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(Channel ch) throws Exception {

                long maxConnectionAgeInNanos = GateWayServer.this.maxConnectionAgeInNanos;
                if (maxConnectionAgeInNanos != MAX_CONNECTION_AGE_NANOS_DISABLED) {
                    // apply a random jitter of +/-10% to max connection age
                    maxConnectionAgeInNanos =
                            (long) ((.9D + Math.random() * .2D) * maxConnectionAgeInNanos);
                }

                NettyServerTransport transport =
                        new NettyServerTransport(
                                ch, protocolNegotiator, streamTracerFactories, transportTracerFactory.create(),
                                maxStreamsPerConnection,
                                flowControlWindow, maxMessageSize, maxHeaderListSize,
                                keepAliveTimeInNanos, keepAliveTimeoutInNanos,
                                maxConnectionIdleInNanos,
                                maxConnectionAgeInNanos, maxConnectionAgeGraceInNanos,
                                permitKeepAliveWithoutCalls, permitKeepAliveTimeInNanos);
                ServerTransportListener transportListener;
                // This is to order callbacks on the listener, not to guard access to channel.
                synchronized (GateWayServer.this) {
                    if (channel != null && !channel.isOpen()) {
                        // Server already shutdown.
                        ch.close();
                        return;
                    }
                    // `channel` shutdown can race with `ch` initialization, so this is only safe to increment
                    // inside the lock.
                    eventLoopReferenceCounter.retain();
                    transportListener = listener.transportCreated(transport);
                }
                transport.start(transportListener);
                ch.closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        eventLoopReferenceCounter.release();
                    }
                });
            }
        });
        // Bind and start to accept incoming connections.
        ChannelFuture future = b.bind(address);
        try {
            future.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for bind");
        }
        if (!future.isSuccess()) {
            throw new IOException("Failed to bind", future.cause());
        }
        channel = future.channel();
    }

    @Override
    public void shutdown() {
        if (channel == null || !channel.isOpen()) {
            // Already closed.
            return;
        }
        channel.close().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    log.log(Level.WARNING, "Error shutting down server", future.cause());
                }
                synchronized (GateWayServer.this) {
                    listener.serverShutdown();
                }
                eventLoopReferenceCounter.release();
            }
        });
    }

    private void allocateSharedGroups() {
        if (bossGroup == null) {
            bossGroup = SharedResourceHolder.get(Utils.DEFAULT_BOSS_EVENT_LOOP_GROUP);
        }
        if (workerGroup == null) {
            workerGroup = SharedResourceHolder.get(Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP);
        }
    }

    @Override
    public InternalLogId getLogId() {
        return logId;
    }

    class EventLoopReferenceCounter extends AbstractReferenceCounted {
        @Override
        protected void deallocate() {
            try {
                if (usingSharedBossGroup && bossGroup != null) {
                    SharedResourceHolder.release(Utils.DEFAULT_BOSS_EVENT_LOOP_GROUP, bossGroup);
                }
            } finally {
                bossGroup = null;
                try {
                    if (usingSharedWorkerGroup && workerGroup != null) {
                        SharedResourceHolder.release(Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP, workerGroup);
                    }
                } finally {
                    workerGroup = null;
                }
            }
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            return this;
        }
    }





}
