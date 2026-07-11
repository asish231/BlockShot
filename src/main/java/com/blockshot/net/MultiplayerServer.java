package com.blockshot.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class MultiplayerServer implements AutoCloseable {

    private static final int MAX_CLIENTS = 64;
    private static final int RECEIVE_TIMEOUT_MILLIS = 250;
    private static final long CLIENT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long EXPIRY_CHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Object lifecycleLock = new Object();
    private final int requestedPort;
    private final Map<SocketAddress, ClientSession> clientsByAddress = new ConcurrentHashMap<>();
    private final Map<UUID, ClientSession> clientsById = new ConcurrentHashMap<>();
    private final Map<UUID, NetworkMessage.PlayerState> latestStates = new ConcurrentHashMap<>();

    private volatile DatagramSocket socket;
    private volatile Thread receiverThread;
    private volatile boolean running;
    private volatile boolean closed;
    private volatile int boundPort;

    public MultiplayerServer(int requestedPort) {
        if (requestedPort < 0 || requestedPort > 65_535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        this.requestedPort = requestedPort;
        boundPort = requestedPort;
    }

    /**
     * Starts the receiver. Calling this method again while it is running has no effect.
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            if (closed) {
                throw new IllegalStateException("Server is closed");
            }

            DatagramSocket created = null;
            try {
                created = new DatagramSocket(null);
                created.setReuseAddress(false);
                created.bind(new InetSocketAddress(requestedPort));
                created.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
            } catch (IOException exception) {
                if (created != null) {
                    created.close();
                }
                throw new IllegalStateException("Unable to start multiplayer server", exception);
            }

            DatagramSocket receivingSocket = created;
            Thread thread = new Thread(
                    () -> receiveLoop(receivingSocket),
                    "blockshot-multiplayer-server-" + receivingSocket.getLocalPort());
            thread.setDaemon(true);
            socket = receivingSocket;
            boundPort = receivingSocket.getLocalPort();
            receiverThread = thread;
            running = true;
            try {
                thread.start();
            } catch (RuntimeException exception) {
                running = false;
                socket = null;
                receiverThread = null;
                receivingSocket.close();
                throw exception;
            }
        }
    }

    public int port() {
        return boundPort;
    }

    public int clientCount() {
        return clientsByAddress.size();
    }

    @Override
    public void close() {
        DatagramSocket socketToClose;
        Thread threadToJoin;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            socketToClose = socket;
            threadToJoin = receiverThread;
            socket = null;
            receiverThread = null;
        }

        if (socketToClose != null) {
            socketToClose.close();
        }
        join(threadToJoin);
        clearClients();
    }

    private void receiveLoop(DatagramSocket receivingSocket) {
        byte[] buffer = new byte[NetworkMessageCodec.MAX_PACKET_BYTES + 1];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        long nextExpiryCheck = System.nanoTime() + EXPIRY_CHECK_NANOS;
        try {
            while (isActive(receivingSocket)) {
                try {
                    packet.setLength(buffer.length);
                    receivingSocket.receive(packet);
                    processPacket(packet, receivingSocket);
                } catch (SocketTimeoutException exception) {
                    // The timeout provides a bounded opportunity to expire inactive clients.
                } catch (SocketException exception) {
                    break;
                } catch (IOException exception) {
                    break;
                }

                long now = System.nanoTime();
                if (now >= nextExpiryCheck) {
                    expireClients(now, receivingSocket);
                    nextExpiryCheck = now + EXPIRY_CHECK_NANOS;
                }
            }
        } finally {
            receivingSocket.close();
            synchronized (lifecycleLock) {
                if (socket == receivingSocket) {
                    clearClients();
                    socket = null;
                    receiverThread = null;
                    running = false;
                }
            }
        }
    }

    private boolean isActive(DatagramSocket receivingSocket) {
        return running && socket == receivingSocket && !receivingSocket.isClosed();
    }

    private void processPacket(DatagramPacket packet, DatagramSocket receivingSocket) {
        if (packet.getLength() > NetworkMessageCodec.MAX_PACKET_BYTES) {
            return;
        }

        NetworkMessage message;
        try {
            String text = decodeUtf8(packet.getData(), packet.getOffset(), packet.getLength());
            message = NetworkMessageCodec.decode(text);
        } catch (CharacterCodingException | IllegalArgumentException exception) {
            return;
        }

        SocketAddress source = packet.getSocketAddress();
        long now = System.nanoTime();
        if (message instanceof NetworkMessage.Hello hello) {
            handleHello(hello, source, now, receivingSocket);
            return;
        }

        ClientSession client = clientsByAddress.get(source);
        if (client == null) {
            return;
        }

        if (message instanceof NetworkMessage.PlayerState state) {
            if (!state.id().equals(client.id) || !acceptSequence(client, state.sequence(), now)) {
                return;
            }
            latestStates.put(state.id(), state);
            relay(state, source, receivingSocket);
        } else if (message instanceof NetworkMessage.BlockEdit edit) {
            if (!edit.actor().equals(client.id) || !acceptSequence(client, edit.sequence(), now)) {
                return;
            }
            relay(edit, source, receivingSocket);
        } else if (message instanceof NetworkMessage.Goodbye goodbye
                && goodbye.id().equals(client.id)) {
            removeClient(client, receivingSocket);
        }
    }

    private void handleHello(NetworkMessage.Hello hello, SocketAddress source, long now,
                             DatagramSocket receivingSocket) {
        ClientSession knownAddress = clientsByAddress.get(source);
        if (knownAddress != null) {
            if (knownAddress.id.equals(hello.id()) && knownAddress.name.equals(hello.name())) {
                knownAddress.lastSeenNanos = now;
            }
            return;
        }
        if (clientsByAddress.size() >= MAX_CLIENTS || clientsById.containsKey(hello.id())) {
            return;
        }

        ClientSession client = new ClientSession(hello.id(), hello.name(), source, now);
        clientsByAddress.put(source, client);
        clientsById.put(client.id, client);
        for (NetworkMessage.PlayerState state : latestStates.values()) {
            if (!state.id().equals(client.id)) {
                send(state, source, receivingSocket);
            }
        }
    }

    private static boolean acceptSequence(ClientSession client, long sequence, long now) {
        if (sequence <= 0 || sequence <= client.lastSequence) {
            return false;
        }
        client.lastSequence = sequence;
        client.lastSeenNanos = now;
        return true;
    }

    private void relay(NetworkMessage message, SocketAddress source,
                       DatagramSocket receivingSocket) {
        byte[] bytes;
        try {
            bytes = encode(message);
        } catch (IllegalArgumentException exception) {
            return;
        }
        for (ClientSession client : clientsByAddress.values()) {
            if (!client.address.equals(source)) {
                send(bytes, client.address, receivingSocket);
            }
        }
    }

    private void expireClients(long now, DatagramSocket receivingSocket) {
        for (ClientSession client : clientsByAddress.values()) {
            if (now - client.lastSeenNanos >= CLIENT_TIMEOUT_NANOS) {
                removeClient(client, receivingSocket);
            }
        }
    }

    private void removeClient(ClientSession client, DatagramSocket receivingSocket) {
        if (!clientsByAddress.remove(client.address, client)) {
            return;
        }
        clientsById.remove(client.id, client);
        latestStates.remove(client.id);
        relay(new NetworkMessage.Goodbye(client.id), client.address, receivingSocket);
    }

    private static void send(NetworkMessage message, SocketAddress destination,
                             DatagramSocket sendingSocket) {
        try {
            send(encode(message), destination, sendingSocket);
        } catch (IllegalArgumentException exception) {
            // Only validated protocol messages reach this path.
        }
    }

    private static void send(byte[] bytes, SocketAddress destination,
                             DatagramSocket sendingSocket) {
        try {
            sendingSocket.send(new DatagramPacket(bytes, bytes.length, destination));
        } catch (IOException exception) {
            // UDP delivery is best effort; one failed peer must not stop the receiver.
        }
    }

    private static byte[] encode(NetworkMessage message) {
        return NetworkMessageCodec.encode(message).getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length)
            throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString();
    }

    private void clearClients() {
        clientsByAddress.clear();
        clientsById.clear();
        latestStates.clear();
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ClientSession {

        private final UUID id;
        private final String name;
        private final SocketAddress address;
        private long lastSeenNanos;
        private long lastSequence;

        private ClientSession(UUID id, String name, SocketAddress address, long lastSeenNanos) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.lastSeenNanos = lastSeenNanos;
        }
    }
}