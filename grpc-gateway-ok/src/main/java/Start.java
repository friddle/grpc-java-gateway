import io.grpc.internal.GateWayServerBuilder;
import io.grpc.internal.GateWayServerImpl;
import io.grpc.netty.DefaultChannelFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Created by talkweb on 2018/3/8.
 */
public class Start {
    public static void main(String[] args) throws IOException, InterruptedException, InstantiationException, IllegalAccessException {
        SocketAddress address=new InetSocketAddress("localhost",50050);
        GateWayServerImpl server= GateWayServerBuilder.
                forAddress(address).
                setChannelFactory(DefaultChannelFactory.Default("localhost",50051)).
                build();
        server.start();
        server.awaitTermination();
    }
}
