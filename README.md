Java版本的Grpc的GateWay.非常傻逼版本
-------

提前说
---
1. 没做完.
2. 做的方式很傻逼.


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
                addService(new GreeterGrpc.GreeterImplBase(){}).
                setChannelFactory(DefaultChannelFactory.Default("localhost",50051)).
                build();
        server.start();
        server.awaitTermination();
```



IChannelFactory
```
    ManagedChannel GetChannel(MethodDescriptor method, Metadata headers);
```

自己拿着请求头和方法名字去转发到不同的地址去。



真正吐槽的地方
---

1. 因为Grpc的Netty代码和什么代码都是final。private。default。所以没办法只能复制代码
2. 还有压缩什么。进程什么。压测什么都没做。只保证能用。果断时间完善
3. Java的Abstract类是没办法动态实例化的。所以你看到的调用时还要new一个。
4. 为什么作为一个GateWay还要把服务的定义加上去：（因为Java代码都是泛型。泛型。泛型你懂吗。
本来想做一个Stream对Stream的。发现根本无法复用代码.自己写工作量太大。（果断时间再弄吧））
5. Java这种就不适合写底层。太受限制了。
6. 压力测试没做。
7. 即使不做压力测试也是有问题的。因为调用时用了进程的。ServiceCall也是进程的。所以一个请求产生两个进程。没有走EventLoop。
8. 找个时间再改一个底层版本。
9. 实在没有其他选项可以用这个。大概率不推荐用。


