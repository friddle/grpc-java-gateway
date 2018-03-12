package io.grpc.internal;

import com.friddle.IChannelFactory;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.*;
import io.grpc.netty.*;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static io.grpc.internal.GrpcUtil.*;

/**
 * A builder to help simplify the construction of a Netty-based GRPC server.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1784")
@CanIgnoreReturnValue
@SuppressWarnings("Duplicates")
public final class GateWayServerBuilder extends AbstractServerImplBuilder<GateWayServerBuilder> {
    public static final int DEFAULT_FLOW_CONTROL_WINDOW = 1048576; // 1MiB

    public static final long MAX_CONNECTION_IDLE_NANOS_DISABLED = Long.MAX_VALUE;
    public static final long MAX_CONNECTION_AGE_NANOS_DISABLED = Long.MAX_VALUE;
    static final long MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE = Long.MAX_VALUE;

    private static final long MIN_KEEPALIVE_TIME_NANO = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long MIN_KEEPALIVE_TIMEOUT_NANO = TimeUnit.MICROSECONDS.toNanos(499L);
    private static final long MIN_MAX_CONNECTION_IDLE_NANO = TimeUnit.SECONDS.toNanos(1L);
    private static final long MIN_MAX_CONNECTION_AGE_NANO = TimeUnit.SECONDS.toNanos(1L);
    private static final long AS_LARGE_AS_INFINITE = TimeUnit.DAYS.toNanos(1000L);

    private final SocketAddress address;
    private Class<? extends ServerChannel> channelType = NioServerSocketChannel.class;
    private final Map<ChannelOption<?>, Object> channelOptions =
            new HashMap<ChannelOption<?>, Object>();
    @Nullable
    private EventLoopGroup bossEventLoopGroup;
    @Nullable
    private EventLoopGroup workerEventLoopGroup;
    private SslContext sslContext;
    private ProtocolNegotiator protocolNegotiator;
    private int maxConcurrentCallsPerConnection = Integer.MAX_VALUE;
    private int flowControlWindow = DEFAULT_FLOW_CONTROL_WINDOW;
    private int maxMessageSize = DEFAULT_MAX_MESSAGE_SIZE;
    private int maxHeaderListSize = GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE;
    private long keepAliveTimeInNanos =  DEFAULT_SERVER_KEEPALIVE_TIME_NANOS;
    private long keepAliveTimeoutInNanos = DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS;
    private long maxConnectionIdleInNanos = MAX_CONNECTION_IDLE_NANOS_DISABLED;
    private long maxConnectionAgeInNanos = MAX_CONNECTION_AGE_NANOS_DISABLED;
    private long maxConnectionAgeGraceInNanos = MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;
    private boolean permitKeepAliveWithoutCalls;
    private long permitKeepAliveTimeInNanos = TimeUnit.MINUTES.toNanos(5);
    //设置默认的Transport
    private TransportTracer.Factory transportTracerFactory=TransportTracer.getDefaultFactory();
    protected IChannelFactory routerChanelFactory;

    /**
     * Creates a server builder that will bind to the given port.
     *
     * @param port the port on which the server is to be bound.
     * @return the server builder.
     */
    @CheckReturnValue
    public static GateWayServerBuilder forPort(int port) {
        return new GateWayServerBuilder(port);
    }


    /**
     * Creates a server builder configured with the given {@link SocketAddress}.
     *
     * @param address the socket address on which the server is to be bound.
     * @return the server builder
     */
    @CheckReturnValue
    public static GateWayServerBuilder forAddress(SocketAddress address) {
        return new GateWayServerBuilder(address);
    }

    @CheckReturnValue
    private GateWayServerBuilder(int port) {
        this.address = new InetSocketAddress(port);
    }

    @CheckReturnValue
    private GateWayServerBuilder(SocketAddress address) {
        this.address = address;
    }

    /**
     * Specify the channel type to use, by default we use {@link NioServerSocketChannel}.
     */
    public GateWayServerBuilder channelType(Class<? extends ServerChannel> channelType) {
        this.channelType = Preconditions.checkNotNull(channelType, "channelType");
        return this;
    }

    /**
     * Specifies a channel option. As the underlying channel as well as network implementation may
     * ignore this value applications should consider it a hint.
     *
     * @since 1.9.0
     */
    public <T> GateWayServerBuilder withChildOption(ChannelOption<T> option, T value) {
        this.channelOptions.put(option, value);
        return this;
    }

    /**
     * Provides the boss EventGroupLoop to the server.
     *
     * <p>It's an optional parameter. If the user has not provided one when the server is built, the
     * builder will use the default one which is static.
     *
     * <p>The server won't take ownership of the given EventLoopGroup. It's caller's responsibility
     * to shut it down when it's desired.
     *
     * <p>Grpc uses non-daemon {@link Thread}s by default and thus a {@link Server} will
     * continue to run even after the main thread has terminated. However, users have to be cautious
     * when providing their own {@link EventLoopGroup}s.
     * For example, Netty's {@link EventLoopGroup}s use daemon threads by default
     * and thus an application with only daemon threads running besides the main thread will exit as
     * soon as the main thread completes.
     * A simple solution to this problem is to call {@link Server#awaitTermination()} to
     * keep the main thread alive until the server has terminated.
     */
    public GateWayServerBuilder bossEventLoopGroup(EventLoopGroup group) {
        this.bossEventLoopGroup = group;
        return this;
    }

    /**
     * Provides the worker EventGroupLoop to the server.
     *
     * <p>It's an optional parameter. If the user has not provided one when the server is built, the
     * builder will create one.
     *
     * <p>The server won't take ownership of the given EventLoopGroup. It's caller's responsibility
     * to shut it down when it's desired.
     *
     * <p>Grpc uses non-daemon {@link Thread}s by default and thus a {@link Server} will
     * continue to run even after the main thread has terminated. However, users have to be cautious
     * when providing their own {@link EventLoopGroup}s.
     * For example, Netty's {@link EventLoopGroup}s use daemon threads by default
     * and thus an application with only daemon threads running besides the main thread will exit as
     * soon as the main thread completes.
     * A simple solution to this problem is to call {@link Server#awaitTermination()} to
     * keep the main thread alive until the server has terminated.
     */
    public GateWayServerBuilder workerEventLoopGroup(EventLoopGroup group) {
        this.workerEventLoopGroup = group;
        return this;
    }

    /**
     * Sets the TLS context to use for encryption. Providing a context enables encryption. It must
     * have been configured with {@link GrpcSslContextss}, but options could have been overridden.
     */
    public GateWayServerBuilder sslContext(SslContext sslContext) {
        if (sslContext != null) {
            checkArgument(sslContext.isServer(),
                    "Client SSL context can not be used for server");
            GrpcSslContextss.ensureAlpnAndH2Enabled(sslContext.applicationProtocolNegotiator());
        }
        this.sslContext = sslContext;
        return this;
    }

    /**
     * Sets the {@link ProtocolNegotiator} to be used. If non-{@code null}, overrides the value
     * specified in {@link #sslContext(SslContext)}.
     *
     * <p>Default: {@code null}.
     */
    @Internal
    public final GateWayServerBuilder protocolNegotiator(
            @Nullable ProtocolNegotiator protocolNegotiator) {
        this.protocolNegotiator = protocolNegotiator;
        return this;
    }

    /**
     * The maximum number of concurrent calls permitted for each incoming connection. Defaults to no
     * limit.
     */
    public GateWayServerBuilder maxConcurrentCallsPerConnection(int maxCalls) {
        checkArgument(maxCalls > 0, "max must be positive: %s", maxCalls);
        this.maxConcurrentCallsPerConnection = maxCalls;
        return this;
    }

    /**
     * Sets the HTTP/2 flow control window. If not called, the default value
     * is {@link #DEFAULT_FLOW_CONTROL_WINDOW}).
     */
    public GateWayServerBuilder flowControlWindow(int flowControlWindow) {
        checkArgument(flowControlWindow > 0, "flowControlWindow must be positive");
        this.flowControlWindow = flowControlWindow;
        return this;
    }

    /**
     * Sets the maximum message size allowed to be received on the server. If not called,
     * defaults to 4 MiB. The default provides protection to services who haven't considered the
     * possibility of receiving large messages while trying to be large enough to not be hit in normal
     * usage.
     */
    public GateWayServerBuilder maxMessageSize(int maxMessageSize) {
        checkArgument(maxMessageSize >= 0, "maxMessageSize must be >= 0");
        this.maxMessageSize = maxMessageSize;
        return this;
    }

    /**
     * Sets the maximum size of header list allowed to be received. This is cumulative size of the
     * headers with some overhead, as defined for
     * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
     * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
     */
    public GateWayServerBuilder maxHeaderListSize(int maxHeaderListSize) {
        checkArgument(maxHeaderListSize > 0, "maxHeaderListSize must be > 0");
        this.maxHeaderListSize = maxHeaderListSize;
        return this;
    }

    /**
     * Sets a custom keepalive time, the delay time for sending next keepalive ping. An unreasonably
     * small value might be increased, and {@code Long.MAX_VALUE} nano seconds or an unreasonably
     * large value will disable keepalive.
     *
     * @since 1.3.0
     */
    public GateWayServerBuilder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
        checkArgument(keepAliveTime > 0L, "keepalive time must be positive");
        keepAliveTimeInNanos = timeUnit.toNanos(keepAliveTime);
        keepAliveTimeInNanos = KeepAliveManager.clampKeepAliveTimeInNanos(keepAliveTimeInNanos);
        if (keepAliveTimeInNanos >= AS_LARGE_AS_INFINITE) {
            // Bump keepalive time to infinite. This disables keep alive.
            keepAliveTimeInNanos = SERVER_KEEPALIVE_TIME_NANOS_DISABLED;
        }
        if (keepAliveTimeInNanos < MIN_KEEPALIVE_TIME_NANO) {
            // Bump keepalive time.
            keepAliveTimeInNanos = MIN_KEEPALIVE_TIME_NANO;
        }
        return this;
    }

    /**
     * Sets a custom keepalive timeout, the timeout for keepalive ping requests. An unreasonably small
     * value might be increased.
     *
     * @since 1.3.0
     */
    public GateWayServerBuilder keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
        checkArgument(keepAliveTimeout > 0L, "keepalive timeout must be positive");
        keepAliveTimeoutInNanos = timeUnit.toNanos(keepAliveTimeout);
        keepAliveTimeoutInNanos =
                KeepAliveManager.clampKeepAliveTimeoutInNanos(keepAliveTimeoutInNanos);
        if (keepAliveTimeoutInNanos < MIN_KEEPALIVE_TIMEOUT_NANO) {
            // Bump keepalive timeout.
            keepAliveTimeoutInNanos = MIN_KEEPALIVE_TIMEOUT_NANO;
        }
        return this;
    }

    /**
     * Sets a custom max connection idle time, connection being idle for longer than which will be
     * gracefully terminated. Idleness duration is defined since the most recent time the number of
     * outstanding RPCs became zero or the connection establishment. An unreasonably small value might
     * be increased. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value will disable
     * max connection idle.
     *
     * @since 1.4.0
     */
    public GateWayServerBuilder maxConnectionIdle(long maxConnectionIdle, TimeUnit timeUnit) {
        checkArgument(maxConnectionIdle > 0L, "max connection idle must be positive");
        maxConnectionIdleInNanos = timeUnit.toNanos(maxConnectionIdle);
        if (maxConnectionIdleInNanos >= AS_LARGE_AS_INFINITE) {
            maxConnectionIdleInNanos = MAX_CONNECTION_IDLE_NANOS_DISABLED;
        }
        if (maxConnectionIdleInNanos < MIN_MAX_CONNECTION_IDLE_NANO) {
            maxConnectionIdleInNanos = MIN_MAX_CONNECTION_IDLE_NANO;
        }
        return this;
    }



    /**
     * Sets a custom max connection age, connection lasting longer than which will be gracefully
     * terminated. An unreasonably small value might be increased.  A random jitter of +/-10% will be
     * added to it. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value will disable
     * max connection age.
     *
     * @since 1.3.0
     */
    public GateWayServerBuilder maxConnectionAge(long maxConnectionAge, TimeUnit timeUnit) {
        checkArgument(maxConnectionAge > 0L, "max connection age must be positive");
        maxConnectionAgeInNanos = timeUnit.toNanos(maxConnectionAge);
        if (maxConnectionAgeInNanos >= AS_LARGE_AS_INFINITE) {
            maxConnectionAgeInNanos = MAX_CONNECTION_AGE_NANOS_DISABLED;
        }
        if (maxConnectionAgeInNanos < MIN_MAX_CONNECTION_AGE_NANO) {
            maxConnectionAgeInNanos = MIN_MAX_CONNECTION_AGE_NANO;
        }
        return this;
    }

    /**
     * Sets a custom grace time for the graceful connection termination. Once the max connection age
     * is reached, RPCs have the grace time to complete. RPCs that do not complete in time will be
     * cancelled, allowing the connection to terminate. {@code Long.MAX_VALUE} nano seconds or an
     * unreasonably large value are considered infinite.
     *
     * @see #maxConnectionAge(long, TimeUnit)
     * @since 1.3.0
     */
    public GateWayServerBuilder maxConnectionAgeGrace(long maxConnectionAgeGrace, TimeUnit timeUnit) {
        checkArgument(maxConnectionAgeGrace >= 0L, "max connection age grace must be non-negative");
        maxConnectionAgeGraceInNanos = timeUnit.toNanos(maxConnectionAgeGrace);
        if (maxConnectionAgeGraceInNanos >= AS_LARGE_AS_INFINITE) {
            maxConnectionAgeGraceInNanos = MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;
        }
        return this;
    }

    /**
     * Specify the most aggressive keep-alive time clients are permitted to configure. The server will
     * try to detect clients exceeding this rate and when detected will forcefully close the
     * connection. The default is 5 minutes.
     *
     * <p>Even though a default is defined that allows some keep-alives, clients must not use
     * keep-alive without approval from the service owner. Otherwise, they may experience failures in
     * the future if the service becomes more restrictive. When unthrottled, keep-alives can cause a
     * significant amount of traffic and CPU usage, so clients and servers should be conservative in
     * what they use and accept.
     *
     * @see #permitKeepAliveWithoutCalls(boolean)
     * @since 1.3.0
     */
    public GateWayServerBuilder permitKeepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
        checkArgument(keepAliveTime >= 0, "permit keepalive time must be non-negative");
        permitKeepAliveTimeInNanos = timeUnit.toNanos(keepAliveTime);
        return this;
    }

    /**
     * Sets whether to allow clients to send keep-alive HTTP/2 PINGs even if there are no outstanding
     * RPCs on the connection. Defaults to {@code false}.
     *
     * @see #permitKeepAliveTime(long, TimeUnit)
     * @since 1.3.0
     */
    public GateWayServerBuilder permitKeepAliveWithoutCalls(boolean permit) {
        permitKeepAliveWithoutCalls = permit;
        return this;
    }

    public GateWayServerBuilder useTransportSecurity(File certChain, File privateKey) {
        try {
            sslContext = GrpcSslContextss.forServer(certChain, privateKey).build();
        } catch (SSLException e) {
            // This should likely be some other, easier to catch exception.
            throw new RuntimeException(e);
        }
        return this;
    }




    /**
     * 1.生成一个TracerFactory
     * 2. 拿到 ServerTransportListener
     * 3. 拿到 CleintTransportListener
     * 3
     * @return
     */


    @Override
    protected InternalServer buildTransportServer(List<ServerStreamTracer.Factory> streamTracerFactories) {
        ProtocolNegotiator negotiator = protocolNegotiator;
        if (negotiator == null) {
            negotiator = sslContext != null ? ProtocolNegotiators.serverTls(sslContext) :
                    ProtocolNegotiators.serverPlaintext();
        }

        return new GateWayServer(
                address, channelType, channelOptions, bossEventLoopGroup, workerEventLoopGroup,
                negotiator, streamTracerFactories, transportTracerFactory,
                maxConcurrentCallsPerConnection, flowControlWindow,
                maxMessageSize, maxHeaderListSize, keepAliveTimeInNanos, keepAliveTimeoutInNanos,
                maxConnectionIdleInNanos,
                maxConnectionAgeInNanos, maxConnectionAgeGraceInNanos,
                permitKeepAliveWithoutCalls, permitKeepAliveTimeInNanos);
    }

    public GateWayServerBuilder setChannelFactory(IChannelFactory routerChanelFactory)
    {
        this.routerChanelFactory=routerChanelFactory;
        return this;
    }


    public GateWayServerImpl build(){
        GateWayServerImpl server = new GateWayServerImpl(this,
                buildTransportServer(Collections.unmodifiableList(getTracerFactories())),
                Context.ROOT);
        return server;
    }



    public static void main(String[] args) throws InterruptedException, IOException {
        SocketAddress address=new InetSocketAddress("127.0.0.1",50052);
        GateWayServerImpl server=(GateWayServerImpl) GateWayServerBuilder.forAddress(address).build();
        server.start();
        server.awaitTermination();
    }

}

