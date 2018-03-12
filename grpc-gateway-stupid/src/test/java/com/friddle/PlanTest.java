package com.friddle;

import com.talkweb.babystory.protobuf.core.AD;
import com.talkweb.babystory.protobuf.core.Common;
import com.talkweb.babystory.protobuf.core.Plan;
import com.talkweb.babystory.protobuf.grpc.ADServiceGrpc;
import com.talkweb.babystory.protobuf.grpc.PlanServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.netty.handler.ssl.SslContext;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static com.talkweb.babystory.protobuf.grpc.ADServiceGrpc.METHOD_AD_POSITION;

/**
 * Created by talkweb on 2018/3/8.
 */

public class PlanTest {


    @Test
    public void test1()
    {
        System.out.println(ADServiceGrpc.newBlockingStub(ManagedChannelBuilder.
                        forAddress("localhost",50051).
                usePlaintext(true).build()).adPosition(AD.AdPositionRequest.newBuilder().
                setPosition(Common.Position.indexCarousel).
                build()));
    }

    @Test
    public void test2() throws IOException {

        SslContext context = GrpcSslContexts.forClient().trustManager(new ClassPathResource("sites/appapi.newtest.babystory365.com").getFile()).build();
        ManagedChannel channel= NettyChannelBuilder.
                forAddress("appapi.newtest.babystory365.com",50051).
                sslContext(context).
                overrideAuthority("appapi.newtest.babystory365.com").build();
        AD.AdPositionRequest request=AD.AdPositionRequest.newBuilder().
                        setPosition(Common.Position.indexCarousel).build();
        AD.AdPositionResponse response =ClientCalls.blockingUnaryCall(channel,METHOD_AD_POSITION, CallOptions.DEFAULT,request);
        System.out.println(response);
    }
}
