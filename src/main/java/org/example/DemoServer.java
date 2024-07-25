package org.example;

import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee10.websocket.server.*;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

public class DemoServer {

    public static final String SERVER_HOST = "localhost";
    public static final int SERVER_PORT = 20000;
    public static final String SERVER_PATH = "/messaging";

    public static final int BUFFER_SIZE = 1024 * 1024 * 2;

    public static URI SERVER_URI = URI.create("ws://" + SERVER_HOST + ":" + SERVER_PORT + SERVER_PATH);

    /**
     * Starts the webserver, waits for websocket messages from the {@link DemoClient#test() client} then exists.
     * The webserver sends back the {@link DemoClient#check(String, ByteBuffer) correct} messages to the client.
     */
    public static void main(String... args) throws Exception {
        long heapMaxSize = Runtime.getRuntime().maxMemory();
        System.out.println("Heap max size: " + heapMaxSize);

        var server = new DemoServer();
        server.stopOnShutdown();
        server.start();

        DemoClient.test();

        System.exit(0);
    }

    final Server server;

    DemoServer() {
        this.server = new Server(getServerInetSocketAddress());
    }

    @Nonnull
    protected URI getServerUri() {
        return SERVER_URI;
    }

    @Nonnull
    protected InetSocketAddress getServerInetSocketAddress() {
        return InetSocketAddress.createUnresolved(getServerUri().getHost(), getServerUri().getPort());
    }


    void start() throws Exception {
        //var connector = new ServerConnector(server);
        //server.addConnector(connector);

        var handler = createServletAndHandler();
        server.setHandler(handler);

        server.start();
        //System.out.println("Server started at port " + connector.getLocalPort());
        System.out.println("Server started at port " + SERVER_URI);
    }

    private ServletContextHandler createServletAndHandler() {
        var servlet = new JettyWebSocketServlet() {
            @Override
            protected void configure(JettyWebSocketServletFactory factory) {
                //factory.addMapping("/", new EchoSocketCreator());
                factory.setCreator(new ServerSocketCreator());
            }
        };
        var handler = new ServletContextHandler();
        handler.addServlet(
                new ServletHolder(servlet),
                "/"
        );
        handler.setContextPath(SERVER_PATH);
        JettyWebSocketServletContainerInitializer.configure(handler, null);
        return handler;
    }

    void stopOnShutdown() {
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::safeStop)
        );
    }

    private void safeStop() {
        System.out.println("Shutting down WebSocketServer");
        try {
            server.stop();
            System.out.println("Exiting WebSocketServer");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class ServerSocketCreator implements JettyWebSocketCreator {

        @Override
        public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp) {
            return new ServerSocket();
        }

    }

    @WebSocket
    public static class ServerSocket {

        private Session session;

        @Nonnull
        private ByteBufferAccumulator accumulator = new ByteBufferAccumulator();

        @OnWebSocketOpen
        public void onWebSocketConnect(Session session) {
            this.session = session;

            session.setInputBufferSize(BUFFER_SIZE);
            session.setOutputBufferSize(BUFFER_SIZE);
        }

        @OnWebSocketFrame
        public void onWebSocketFrame(@Nonnull Session session, @Nonnull Frame frame, @Nonnull Callback callback) {
            Frame.Type webSocketFrameType = frame.getType();
            if (frame.hasPayload()) {
                switch (webSocketFrameType) {
                    case BINARY:
                        /* falls through */
                    case CONTINUATION:
                        ByteBuffer buffer = frame.getPayload();
                        accumulator.copyBuffer(buffer);
                        if ( frame.isFin() ) {
                            // take complete ByteBuffer,
                            ByteBuffer message = accumulator.takeByteBuffer();
                            try {
                                if ( DemoClient.check("Server receiving ", message) )
                                    session.sendBinary(message, Callback.NOOP);
                            }
                            finally {
                                accumulator.close();
                            }
                        }
                        break;
                }
            }
        }
    }

}
