package io.grpc.netty;

import io.grpc.internal.TransportTracer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.Map;

/**
 * Created by talkweb on 2018/3/13.
 */
public class GateWayClientTransport extends NettyClientTransport{
    public GateWayClientTransport(SocketAddress address, Class<? extends Channel> channelType, Map<ChannelOption<?>, ?> channelOptions, EventLoopGroup group, ProtocolNegotiator negotiator, int flowControlWindow, int maxMessageSize, int maxHeaderListSize, long keepAliveTimeNanos, long keepAliveTimeoutNanos, boolean keepAliveWithoutCalls, String authority, @Nullable String userAgent, Runnable tooManyPingsRunnable, TransportTracer transportTracer) {
        super(address, channelType, channelOptions, group, negotiator, flowControlWindow, maxMessageSize, maxHeaderListSize, keepAliveTimeNanos, keepAliveTimeoutNanos, keepAliveWithoutCalls, authority, userAgent, tooManyPingsRunnable, transportTracer);
    }
}
