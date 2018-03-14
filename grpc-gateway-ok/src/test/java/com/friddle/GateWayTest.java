package com.friddle;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.GateWayChannelBuilder;
import io.grpc.internal.GateWayChannelImpl;
import io.grpc.internal.GateWayServerBuilder;
import io.grpc.internal.GateWayServerImpl;
import io.grpc.netty.DefaultChannelFactory;
import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.SslContext;
import org.junit.Test;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Created by talkweb on 2018/3/13.
 */

public class GateWayTest {

    @Test
    public void test() throws InterruptedException, IOException {
        SocketAddress address=new InetSocketAddress("localhost",50051);
        GateWayServerImpl server= GateWayServerBuilder.
                forAddress(address).
                setChannelFactory(new IChannelFactory() {
                    @Override
                    public GateWayChannelImpl GetChannel(String method, Metadata headers) {
                        try {
                            SslContext context = GrpcSslContexts.forClient().trustManager(
                                    new File("test/resources/sites/appapi.newtest.babystory365.pem")).build();
                            return GateWayChannelBuilder.
                                    forAddress("appapi.newtest.babystory365.pem",50051).
                                    usePlaintext(false).
                                    overrideAuthority("appapi.newtest.babystory365.pem").
                                    sslContext(context).build();
                        } catch (SSLException e) {
                            e.printStackTrace();
                            return null;
                        }
                    }

                    @Override
                    public void FeedChannelStatus(ManagedChannel channel, Status status) {

                    }
                }).
                build();
        server.start();
        server.awaitTermination();
    }
}
