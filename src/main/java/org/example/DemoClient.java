package org.example;

import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

public class DemoClient implements Runnable {

    public static final int MESSAGE_COUNT = 10000;
    public static final int MIN_MESSAGE_LENGTH = 4;
    public static final int MAX_MESSAGE_LENGTH = 1024 * 4;

    @Nonnull
    private final CountDownLatch latch;

    public DemoClient(@Nonnull CountDownLatch latch) {
        this.latch = latch;
    }

    /**
     * Connects to the server and sends {@link #MESSAGE_COUNT} random generated random length messages.
     * Also checks whether the server answers with the same messages.
     */
    public static void test() {
        CountDownLatch latch = new CountDownLatch(1);
        System.out.println("Starting demo client");
        new Thread(new DemoClient(latch)).start();
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Demo client finished");
    }

    @Override
    public void run() {
        WebSocketClient client = new WebSocketClient();
        LifeCycle.start(client);

        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        Future<Session> connect = null;
        try {
            ClientSocket socket = new ClientSocket();
            connect = client.connect(socket, DemoServer.SERVER_URI, clientUpgradeRequest);
            // the http response from the server
            UpgradeResponse resp = connect.get().getUpgradeResponse();
            //System.out.println(resp);

            ArrayList<ByteBuffer> buffers = generate(MESSAGE_COUNT, MAX_MESSAGE_LENGTH);
            System.out.println("Generated " + buffers.size() + " buffers.");
            for ( int i = 0; i < buffers.size(); i++ )
                socket.send(buffers.get(i));
        }
        catch ( Exception e ) {
            throw new RuntimeException(e);
        }
        finally {
            latch.countDown();
        }
    }

    @Nonnull
    private static ArrayList<ByteBuffer> generate(int count, int capacity) {
        ArrayList<ByteBuffer> buffers = new ArrayList<>(count);
        for ( int i = 0; i < count; i++ ) {
            ByteBuffer buffer = generate((int) (Math.random() * capacity));
            buffers.add(buffer);
        }
        return buffers;
    }

    @Nonnull
    private static ByteBuffer generate(int capacity) {
        if ( capacity < MIN_MESSAGE_LENGTH )
            capacity = MIN_MESSAGE_LENGTH;

        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.position(0);
        buffer.putInt(capacity);
        for ( int i = 4; i < capacity; i++ )
            buffer.put((byte) (i % 256));
        return buffer.flip();
    }

    /**
     * Returns {@code True} if the {@code buffer} contains the expected amount of data.
     */
    synchronized static boolean check(@Nonnull String message, @Nonnull ByteBuffer buffer) {
        //System.out.println(message + buffer);
        boolean result = true;
        int position = buffer.position();
        buffer.position(0);
        int capacity = buffer.getInt();
        if ( capacity != buffer.limit() ) {
            System.out.println(message + buffer);
            System.out.println("Expected " + capacity + " received " + buffer.limit());
            System.out.println("\n");
            result = false;
        }
        for ( int i = 4; i < capacity; i++ ) {
            byte expected = ((byte) (i % 256));
            byte found = buffer.get();
            if (found != expected)
                System.out.println("Expected " + expected + " found " + found + " at " + buffer.position());
        }

        buffer.position(position);
        System.out.println(".");
        return result;
    }

    @WebSocket
    public static class ClientSocket {

        private Session session;

        @Nonnull
        private ByteBufferAccumulator accumulator = new ByteBufferAccumulator();

        @OnWebSocketOpen
        public void onWebSocketConnect(Session session) {
            this.session = session;

            session.setInputBufferSize(DemoServer.BUFFER_SIZE);
            session.setOutputBufferSize(DemoServer.BUFFER_SIZE);
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
                                check("Client receiving ", message);
                            }
                            finally {
                                accumulator.close();
                            }
                        }
                        break;
                }
            }
        }



        public void send(@Nonnull ByteBuffer payload) {
            try {
                //System.out.println("Client sending " + payload);
                session.sendBinary(payload, Callback.NOOP);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }


}
