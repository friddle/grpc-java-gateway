Java版本的Grpc的GateWay
-------

提前说
---
1. 没做测试


为啥做不完
---
1. Java项目确实工作量太大。不适合我这种业余没时间选手
2. 暂时只有一个demo。什么有时间在弄


用法
---
```
        SocketAddress address=new InetSocketAddress("localhost",50050);
        GateWayServerImpl server= GateWayServerBuilder.
                forAddress(address).
                setChannelFactory(DefaultChannelFactory.Default("localhost",50051)).
                build();
        server.start();
        server.awaitTermination();
```

GateWayChannelBuilder
```
GateWayChannelBuilder.forAddress(address,port).usePlaintext(true).build()
```


IChannelFactory
```
    GateWayChannel GetChannel(String method, Metadata headers);
```




自己拿着请求头和方法名字去转发到不同的地址去。



真正吐槽的地方
---

1. 因为Grpc的Netty代码和什么代码都是final。private。default。所以没办法只能复制代码
2. Java这种就不适合写底层。太受限制了。
3. 压力测试没做。
4. 实在没有其他选项可以用这个。大概率不推荐用。


