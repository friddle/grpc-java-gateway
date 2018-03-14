package com.friddle;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.GateWayChannelImpl;

/**
 * Created by talkweb on 2018/3/12.
 */
public interface IChannelFactory {
    GateWayChannelImpl GetChannel(String method, Metadata headers);
    void FeedChannelStatus(ManagedChannel channel,Status status);
}
