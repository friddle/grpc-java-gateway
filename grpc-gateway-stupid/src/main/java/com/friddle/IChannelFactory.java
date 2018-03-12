package com.friddle;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Created by talkweb on 2018/3/12.
 */
public interface IChannelFactory {
    ManagedChannel GetChannel(MethodDescriptor method, Metadata headers);
    void FeedChannelStatus(ManagedChannel channel,Status status);
}
