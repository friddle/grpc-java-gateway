package io.grpc.netty;

import com.friddle.IChannelFactory;
import io.grpc.*;
import io.grpc.internal.*;
import io.grpc.stub.ClientCalls;

import java.io.InputStream;

/**
 * Created by talkweb on 2018/3/8.
 */
public class GateWayCall {

    public static class RouterServerStreamListener<ReqT,RespT>implements ServerStreamListener {

        private final MethodDescriptor<ReqT,RespT> methodDef;
        private final CallOptions callOptions;
        private final Metadata headers;
        private final ServerStream stream;
        private final Context.CancellableContext context;
        private final StatsTraceContext statsTraceContext;
        private Channel channel;
        private ReqT reqObject;
        private IChannelFactory channelFactory;


        public RouterServerStreamListener(
                ServerStream stream, String fullMethodName,
                ServerMethodDefinition<ReqT, RespT> methodDef, Metadata headers,
                IChannelFactory channelFactory,
                Context.CancellableContext context, StatsTraceContext statsTraceCtx)
        {
            this.stream=stream;
            this.headers=headers;
            this.context=context;
            this.statsTraceContext=statsTraceCtx;
            this.methodDef=methodDef.getMethodDescriptor();
            this.callOptions=CallOptions.DEFAULT;
            this.channelFactory=channelFactory;

        }

        @Override
        public void halfClosed() {
            try{
                RespT resp=ClientCalls.blockingUnaryCall(this.channel,this.methodDef,this.callOptions,this.reqObject);
                stream.writeHeaders(this.headers);
                stream.writeMessage(methodDef.streamResponse(resp));
                stream.flush();
                stream.close(Status.OK,new Metadata());
            }
            catch (StatusRuntimeException e)
            {
                stream.close(e.getStatus(),e.getTrailers());
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

        @SuppressWarnings("Finally") // The code avoids suppressing the exception thrown from try
        @Override
        public void messagesAvailable(final MessageProducer producer) {
            InputStream message;
            try {
                while ((message = producer.next()) != null) {
                    try {
                        this.reqObject=methodDef.parseRequest(message);
                    } catch (Throwable t) {
                        throw t;
                    }
                    message.close();
                }
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }


        @Override
        public void onReady() {
            channel= channelFactory.GetChannel(this.methodDef,headers);
        }
    }

}
