package io.grpc.internal;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.grpc.Contexts.statusFromCancelled;
import static io.grpc.Status.DEADLINE_EXCEEDED;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.TIMEOUT_KEY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.friddle.IChannelFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.InternalLogId;
import io.grpc.InternalWithLogId;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import io.grpc.Status;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.GuardedBy;

/**
 * Default implementation of {@link io.grpc.Server}, for creation by transports.
 *
 * <p>Expected usage (by a theoretical TCP transport):
 * <pre><code>public class TcpTransportServerFactory {
 *   public static Server newServer(Executor executor, HandlerRegistry registry,
 *       String configuration) {
 *     return new GateWayServerImpl(executor, registry, new TcpTransportServer(configuration));
 *   }
 * }</code></pre>
 *
 * <p>Starting the server starts the underlying transport for servicing requests. Stopping the
 * server stops servicing new requests and waits for all connections to terminate.
 */
@SuppressWarnings("Duplicates")
public  class GateWayServerImpl extends io.grpc.Server implements InternalWithLogId {
    private static final Logger log = Logger.getLogger(GateWayServerImpl.class.getName());
    private static final ServerStreamListener NOOP_LISTENER = new NoopListener();

    private final InternalLogId logId = InternalLogId.allocate(getClass().getName());
    private final ObjectPool<? extends Executor> executorPool;
    /** Executor for application processing. Safe to read after {@link #start()}. */
    private Executor executor;
    private final InternalHandlerRegistry registry;
    private final HandlerRegistry fallbackRegistry;
    private final List<ServerTransportFilter> transportFilters;
    // This is iterated on a per-call basis.  Use an array instead of a Collection to avoid iterator
    // creations.
    private final ServerInterceptor[] interceptors;
    private final long handshakeTimeoutMillis;
    @GuardedBy("lock") private boolean started;
    @GuardedBy("lock") private boolean shutdown;
    /** non-{@code null} if immediate shutdown has been requested. */
    @GuardedBy("lock") private Status shutdownNowStatus;
    /** {@code true} if ServerListenerImpl.serverShutdown() was called. */
    @GuardedBy("lock") private boolean serverShutdownCallbackInvoked;
    @GuardedBy("lock") private boolean terminated;
    /** Service encapsulating something similar to an accept() socket. */
    private final InternalServer transportServer;
    private final Object lock = new Object();
    @GuardedBy("lock") private boolean transportServerTerminated;
    /** {@code transportServer} and services encapsulating something similar to a TCP connection. */
    @GuardedBy("lock") private final Collection<ServerTransport> transports =
            new HashSet<ServerTransport>();

    private final Context rootContext;

    private final DecompressorRegistry decompressorRegistry;
    private final CompressorRegistry compressorRegistry;
    private final IChannelFactory routerChannelFactory;

    /**
     * Construct a server.
     *
     * @param builder builder with configuration for server
     * @param transportServer transport server that will create new incoming transports
     * @param rootContext context that callbacks for new RPCs should be derived from
     */
    GateWayServerImpl(
            AbstractServerImplBuilder<?> builder,
            InternalServer transportServer,
            Context rootContext) {
        this.executorPool = Preconditions.checkNotNull(builder.executorPool, "executorPool");
        this.registry = Preconditions.checkNotNull(builder.registryBuilder.build(), "registryBuilder");
        this.routerChannelFactory=Preconditions.checkNotNull(((GateWayServerBuilder)builder).routerChanelFactory);
        this.fallbackRegistry =
                Preconditions.checkNotNull(builder.fallbackRegistry, "fallbackRegistry");
        this.transportServer = Preconditions.checkNotNull(transportServer, "transportServer");
        // Fork from the passed in context so that it does not propagate cancellation, it only
        // inherits values.
        this.rootContext = Preconditions.checkNotNull(rootContext, "rootContext").fork();
        this.decompressorRegistry = builder.decompressorRegistry;
        this.compressorRegistry = builder.compressorRegistry;
        this.transportFilters = Collections.unmodifiableList(
                new ArrayList<ServerTransportFilter>(builder.transportFilters));
        this.interceptors =
                builder.interceptors.toArray(new ServerInterceptor[builder.interceptors.size()]);
        this.handshakeTimeoutMillis = builder.handshakeTimeoutMillis;
    }

    /**
     * Bind and start the server.
     *
     * @return {@code this} object
     * @throws IllegalStateException if already started
     * @throws IOException if unable to bind
     */
    @Override
    public GateWayServerImpl start() throws IOException {
        synchronized (lock) {
            checkState(!started, "Already started");
            checkState(!shutdown, "Shutting down");
            // Start and wait for any port to actually be bound.
            transportServer.start(new ServerListenerImpl());
            executor = Preconditions.checkNotNull(executorPool.getObject(), "executor");
            started = true;
            return this;
        }
    }

    @Override
    public int getPort() {
        synchronized (lock) {
            checkState(started, "Not started");
            checkState(!terminated, "Already terminated");
            return transportServer.getPort();
        }
    }

    @Override
    public List<ServerServiceDefinition> getServices() {
        List<ServerServiceDefinition> fallbackServices = fallbackRegistry.getServices();
        if (fallbackServices.isEmpty()) {
            return registry.getServices();
        } else {
            List<ServerServiceDefinition> registryServices = registry.getServices();
            int servicesCount = registryServices.size() + fallbackServices.size();
            List<ServerServiceDefinition> services =
                    new ArrayList<ServerServiceDefinition>(servicesCount);
            services.addAll(registryServices);
            services.addAll(fallbackServices);
            return Collections.unmodifiableList(services);
        }
    }

    @Override
    public List<ServerServiceDefinition> getImmutableServices() {
        return registry.getServices();
    }

    @Override
    public List<ServerServiceDefinition> getMutableServices() {
        return Collections.unmodifiableList(fallbackRegistry.getServices());
    }

    /**
     * Initiates an orderly shutdown in which preexisting calls continue but new calls are rejected.
     */
    @Override
    public GateWayServerImpl shutdown() {
        boolean shutdownTransportServer;
        synchronized (lock) {
            if (shutdown) {
                return this;
            }
            shutdown = true;
            shutdownTransportServer = started;
            if (!shutdownTransportServer) {
                transportServerTerminated = true;
                checkForTermination();
            }
        }
        if (shutdownTransportServer) {
            transportServer.shutdown();
        }
        return this;
    }

    @Override
    public GateWayServerImpl shutdownNow() {
        shutdown();
        Collection<ServerTransport> transportsCopy;
        Status nowStatus = Status.UNAVAILABLE.withDescription("Server shutdownNow invoked");
        boolean savedServerShutdownCallbackInvoked;
        synchronized (lock) {
            // Short-circuiting not strictly necessary, but prevents transports from needing to handle
            // multiple shutdownNow invocations if shutdownNow is called multiple times.
            if (shutdownNowStatus != null) {
                return this;
            }
            shutdownNowStatus = nowStatus;
            transportsCopy = new ArrayList<ServerTransport>(transports);
            savedServerShutdownCallbackInvoked = serverShutdownCallbackInvoked;
        }
        // Short-circuiting not strictly necessary, but prevents transports from needing to handle
        // multiple shutdownNow invocations, between here and the serverShutdown callback.
        if (savedServerShutdownCallbackInvoked) {
            // Have to call shutdownNow, because serverShutdown callback only called shutdown, not
            // shutdownNow
            for (ServerTransport transport : transportsCopy) {
                transport.shutdownNow(nowStatus);
            }
        }
        return this;
    }

    @Override
    public boolean isShutdown() {
        synchronized (lock) {
            return shutdown;
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        synchronized (lock) {
            long timeoutNanos = unit.toNanos(timeout);
            long endTimeNanos = System.nanoTime() + timeoutNanos;
            while (!terminated && (timeoutNanos = endTimeNanos - System.nanoTime()) > 0) {
                NANOSECONDS.timedWait(lock, timeoutNanos);
            }
            return terminated;
        }
    }

    @Override
    public void awaitTermination() throws InterruptedException {
        synchronized (lock) {
            while (!terminated) {
                lock.wait();
            }
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (lock) {
            return terminated;
        }
    }

    /**
     * Remove transport service from accounting collection and notify of complete shutdown if
     * necessary.
     *
     * @param transport service to remove
     */
    private void transportClosed(ServerTransport transport) {
        synchronized (lock) {
            if (!transports.remove(transport)) {
                throw new AssertionError("Transport already removed");
            }
            checkForTermination();
        }
    }

    /** Notify of complete shutdown if necessary. */
    private void checkForTermination() {
        synchronized (lock) {
            if (shutdown && transports.isEmpty() && transportServerTerminated) {
                if (terminated) {
                    throw new AssertionError("Server already terminated");
                }
                terminated = true;
                if (executor != null) {
                    executor = executorPool.returnObject(executor);
                }
                // TODO(carl-mastrangelo): move this outside the synchronized block.
                lock.notifyAll();
            }
        }
    }

    private final class ServerListenerImpl implements ServerListener {
        @Override
        public ServerTransportListener transportCreated(ServerTransport transport) {
            synchronized (lock) {
                transports.add(transport);
            }
            ServerTransportListenerImpl stli = new ServerTransportListenerImpl(transport);
            stli.init();
            return stli;
        }

        @Override
        public void serverShutdown() {
            ArrayList<ServerTransport> copiedTransports;
            Status shutdownNowStatusCopy;
            synchronized (lock) {
                // transports collection can be modified during shutdown(), even if we hold the lock, due
                // to reentrancy.
                copiedTransports = new ArrayList<ServerTransport>(transports);
                shutdownNowStatusCopy = shutdownNowStatus;
                serverShutdownCallbackInvoked = true;
            }
            for (ServerTransport transport : copiedTransports) {
                if (shutdownNowStatusCopy == null) {
                    transport.shutdown();
                } else {
                    transport.shutdownNow(shutdownNowStatusCopy);
                }
            }
            synchronized (lock) {
                transportServerTerminated = true;
                checkForTermination();
            }
        }
    }

    private final class ServerTransportListenerImpl implements ServerTransportListener {
        private final ServerTransport transport;
        private Future<?> handshakeTimeoutFuture;
        private Attributes attributes;

        ServerTransportListenerImpl(ServerTransport transport) {
            this.transport = transport;
        }

        public void init() {
            class TransportShutdownNow implements Runnable {
                @Override public void run() {
                    transport.shutdownNow(Status.CANCELLED.withDescription("Handshake timeout exceeded"));
                }
            }

            if (handshakeTimeoutMillis != Long.MAX_VALUE) {
                handshakeTimeoutFuture = transport.getScheduledExecutorService()
                        .schedule(new TransportShutdownNow(), handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                // Noop, to avoid triggering Thread creation in InProcessServer
                handshakeTimeoutFuture = new FutureTask<Void>(new Runnable() {
                    @Override public void run() {}
                }, null);
            }
        }

        @Override
        public Attributes transportReady(Attributes attributes) {
            handshakeTimeoutFuture.cancel(false);
            handshakeTimeoutFuture = null;

            for (ServerTransportFilter filter : transportFilters) {
                attributes = Preconditions.checkNotNull(filter.transportReady(attributes),
                        "Filter %s returned null", filter);
            }
            this.attributes = attributes;
            return attributes;
        }

        @Override
        public void transportTerminated() {
            if (handshakeTimeoutFuture != null) {
                handshakeTimeoutFuture.cancel(false);
                handshakeTimeoutFuture = null;
            }
            for (ServerTransportFilter filter : transportFilters) {
                filter.transportTerminated(attributes);
            }
            transportClosed(transport);
        }

        @Override
        public void streamCreated(
                final ServerStream stream, final String methodName, final Metadata headers) {
            if (headers.containsKey(MESSAGE_ENCODING_KEY)) {
                String encoding = headers.get(MESSAGE_ENCODING_KEY);
                Decompressor decompressor = decompressorRegistry.lookupDecompressor(encoding);
                if (decompressor == null) {
                    stream.close(
                            Status.UNIMPLEMENTED.withDescription(
                                    String.format("Can't find decompressor for %s", encoding)),
                            new Metadata());
                    return;
                }
                stream.setDecompressor(decompressor);
            }
            final StatsTraceContext statsTraceCtx = Preconditions.checkNotNull(
                    stream.statsTraceContext(), "statsTraceCtx not present from stream");
            final Context.CancellableContext context = createContext(stream, headers, statsTraceCtx);
            stream.setListener(new GateWayCall(stream,methodName,headers,routerChannelFactory,context,statsTraceCtx).newServerListener());

        }


        private Context.CancellableContext createContext(
                final ServerStream stream, Metadata headers, StatsTraceContext statsTraceCtx) {
            Long timeoutNanos = headers.get(TIMEOUT_KEY);

            Context baseContext = statsTraceCtx.serverFilterContext(rootContext);

            if (timeoutNanos == null) {
                return baseContext.withCancellation();
            }

            Context.CancellableContext context = baseContext.withDeadlineAfter(
                    timeoutNanos, NANOSECONDS, transport.getScheduledExecutorService());
            final class ServerStreamCancellationListener implements Context.CancellationListener {
                @Override
                public void cancelled(Context context) {
                    Status status = statusFromCancelled(context);
                    if (DEADLINE_EXCEEDED.getCode().equals(status.getCode())) {
                        // This should rarely get run, since the client will likely cancel the stream before
                        // the timeout is reached.
                        stream.cancel(status);
                    }
                }
            }

            context.addListener(new ServerStreamCancellationListener(), directExecutor());

            return context;
        }

        /** Never returns {@code null}. */
    }





    @Override
    public InternalLogId getLogId() {
        return logId;
    }

    private static final class NoopListener implements ServerStreamListener {
        @Override
        public void messagesAvailable(MessageProducer producer) {
            InputStream message;
            while ((message = producer.next()) != null) {
                try {
                    message.close();
                } catch (IOException e) {
                    // Close any remaining messages
                    while ((message = producer.next()) != null) {
                        try {
                            message.close();
                        } catch (IOException ioException) {
                            // just log additional exceptions as we are already going to throw
                            log.log(Level.WARNING, "Exception closing stream", ioException);
                        }
                    }
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void halfClosed() {}

        @Override
        public void closed(Status status) {}

        @Override
        public void onReady() {}
    }

    /**
     * Dispatches callbacks onto an application-provided executor and correctly propagates
     * exceptions.
     */
    @VisibleForTesting
    static final class JumpToApplicationThreadServerStreamListener implements ServerStreamListener {
        private final Executor callExecutor;
        private final Executor cancelExecutor;
        private final Context.CancellableContext context;
        private final ServerStream stream;
        // Only accessed from callExecutor.
        private ServerStreamListener listener;

        public JumpToApplicationThreadServerStreamListener(Executor executor,
                                                           Executor cancelExecutor, ServerStream stream, Context.CancellableContext context) {
            this.callExecutor = executor;
            this.cancelExecutor = cancelExecutor;
            this.stream = stream;
            this.context = context;
        }

        /**
         * This call MUST be serialized on callExecutor to avoid races.
         */
        private ServerStreamListener getListener() {
            if (listener == null) {
                throw new IllegalStateException("listener unset");
            }
            return listener;
        }

        @VisibleForTesting
        void setListener(ServerStreamListener listener) {
            Preconditions.checkNotNull(listener, "listener must not be null");
            Preconditions.checkState(this.listener == null, "Listener already set");
            this.listener = listener;
        }

        /**
         * Like {@link ServerCall#close(Status, Metadata)}, but thread-safe for internal use.
         */
        private void internalClose() {
            // TODO(ejona86): this is not thread-safe :)
            stream.close(Status.UNKNOWN, new Metadata());
        }

        @Override
        public void messagesAvailable(final MessageProducer producer) {

            final class MessagesAvailable extends ContextRunnable {

                MessagesAvailable() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    try {
                        getListener().messagesAvailable(producer);
                    } catch (RuntimeException e) {
                        internalClose();
                        throw e;
                    } catch (Error e) {
                        internalClose();
                        throw e;
                    }
                }
            }

            callExecutor.execute(new MessagesAvailable());
        }

        @Override
        public void halfClosed() {
            final class HalfClosed extends ContextRunnable {
                HalfClosed() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    try {
                        getListener().halfClosed();
                    } catch (RuntimeException e) {
                        internalClose();
                        throw e;
                    } catch (Error e) {
                        internalClose();
                        throw e;
                    }
                }
            }

            callExecutor.execute(new HalfClosed());
        }

        @Override
        public void closed(final Status status) {
            // For cancellations, promptly inform any users of the context that their work should be
            // aborted. Otherwise, we can wait until pending work is done.
            if (!status.isOk()) {
                // The callExecutor might be busy doing user work. To avoid waiting, use an executor that
                // is not serializing.
                cancelExecutor.execute(new ContextCloser(context, status.getCause()));
            }

            final class Closed extends ContextRunnable {
                Closed() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    getListener().closed(status);
                }
            }

            callExecutor.execute(new Closed());
        }

        @Override
        public void onReady() {
            final class OnReady extends ContextRunnable {
                OnReady() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    try {
                        getListener().onReady();
                    } catch (RuntimeException e) {
                        internalClose();
                        throw e;
                    } catch (Error e) {
                        internalClose();
                        throw e;
                    }
                }
            }

            callExecutor.execute(new OnReady());
        }
    }

    @VisibleForTesting
    static final class ContextCloser implements Runnable {
        private final Context.CancellableContext context;
        private final Throwable cause;

        ContextCloser(Context.CancellableContext context, Throwable cause) {
            this.context = context;
            this.cause = cause;
        }

        @Override
        public void run() {
            context.cancel(cause);
        }
    }
}

