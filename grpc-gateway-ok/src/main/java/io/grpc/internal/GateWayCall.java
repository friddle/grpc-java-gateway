package io.grpc.internal;

import com.friddle.IChannelFactory;
import io.grpc.*;
import io.grpc.internal.*;
import io.grpc.stub.ClientCalls;
import io.netty.channel.ChannelFactory;
import sun.jvm.hotspot.utilities.MessageQueue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 *
 * 1.JumpApplicationListener可以去掉。毕竟外层进程在这个层面意义不大
 * 2.换一个Call的方法。看看能不能到底层来创建Stream
 * Created by talkweb on 2018/3/8.
 */
public class GateWayCall {

    private final CallOptions callOptions;
    private final Metadata headers;
    private final ServerStream stream;
    protected final String methodName;
    private final Context.CancellableContext context;
    private final StatsTraceContext statsTraceContext;
    private IChannelFactory channelFactory;


    public GateWayCall(
            ServerStream stream, String fullMethodName,
            Metadata headers,
            IChannelFactory channelFactory,
            Context.CancellableContext context, StatsTraceContext statsTraceCtx)
    {
        this.stream=stream;
        this.headers=headers;
        this.context=context;
        this.methodName=fullMethodName;
        this.statsTraceContext=statsTraceCtx;
        this.callOptions=CallOptions.DEFAULT;
        this.channelFactory=channelFactory;
        this.stream.request(1);
    }


    public RouterServerStreamListener newServerListener()
    {
        return new RouterServerStreamListener();
    }



    private class RouterServerStreamListener implements ServerStreamListener {

        private ConcurrentLinkedQueue<InputStream> reqInputs=new ConcurrentLinkedQueue<>();
        private  GateWayChannelImpl channel;

        @Override
        public void halfClosed() {
            while(!reqInputs.isEmpty())
            {
                try {
                    InputStream req=reqInputs.poll();
                    channel.newStreamCall(req,methodName,headers,callOptions,context,new ClientStreamListener(){

                        @Override
                        public void headersRead(Metadata headers) {
                            stream.writeHeaders(headers);
                        }

                        @Override
                        public void closed(Status status, Metadata trailers) {
                            stream.close(status,trailers);
                        }

                        @SuppressWarnings("Duplicates")
                        @Override
                        public void messagesAvailable(MessageProducer producer) {
                            InputStream rsp_message;
                            try {
                                while ((rsp_message = producer.next()) != null) {
                                    try {
                                        stream.writeMessage(rsp_message);
                                        stream.flush();
                                        rsp_message.close();
                                    } catch (Throwable t) {
                                        GrpcUtil.closeQuietly(rsp_message);
                                        throw t;
                                    }
                                }
                            }
                            catch(Throwable t)
                            {
                                GrpcUtil.closeQuietly(producer);
                                MoreThrowables.throwIfUnchecked(t);
                                throw new RuntimeException(t);
                            }
                            stream.close(Status.OK,new Metadata());
                        }

                        @Override
                        public void onReady() {
                            stream.isReady();
                        }
                    });
                    req.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void closed(Status status) {
            try {
                if (status.isOk()) {

                }

            } finally {
                // Cancel context after delivering RPC closure notification to allow the application to
                // clean up and update any state based on whether onComplete or onCancel was called.
            }
        }

        @SuppressWarnings("Duplicates")
        @Override
        public void messagesAvailable(MessageProducer producer) {
            InputStream message;
            try {
                while ((message = producer.next()) != null) {
                    try {
                        reqInputs.add(message);
                    } catch (Throwable t) {
                        GrpcUtil.closeQuietly(message);
                        throw t;
                    }
                }
            } catch (Throwable t) {
                GrpcUtil.closeQuietly(producer);
                MoreThrowables.throwIfUnchecked(t);
                throw new RuntimeException(t);
            }
        }

        @Override
        public void onReady() {
            channel=channelFactory.GetChannel(methodName,headers);
        }
    }

}
