package com.friddle;

import com.friddle.IChannelFactory;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.GateWayChannelBuilder;
import io.grpc.internal.GateWayChannelImpl;
import io.grpc.internal.GateWayServerBuilder;
import io.grpc.internal.GateWayServerImpl;
import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Created by talkweb on 2018/3/13.
 */
public class BabystoryStart {

    public static void main(String[] args) throws IOException, InterruptedException {

        SocketAddress address=new InetSocketAddress("localhost",50051);
        GateWayServerImpl server= GateWayServerBuilder.
                forAddress(address).
                setChannelFactory(new IChannelFactory() {
                    public GateWayChannelImpl channel;
                    @Override
                    public GateWayChannelImpl GetChannel(String method, Metadata headers) {
                        if(channel!=null)
                        {
                            return channel;
                        }
                        try {
                            SslContext context = GrpcSslContexts.forClient().trustManager(
                                    new File("grpc-gateway-ok/src/main/resources/sites/appapi.newtest.babystory365.pem")).build();
                            channel=GateWayChannelBuilder.
                                    forAddress("appapi.newtest.babystory365.com",50051).
                                    overrideAuthority("appapi.newtest.babystory365.com").
                                    sslContext(context).build();
                            return channel;
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
