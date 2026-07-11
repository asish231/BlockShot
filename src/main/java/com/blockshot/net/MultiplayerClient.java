package com.blockshot.net;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MultiplayerClient implements AutoCloseable {

    private static final int MAX_REMOTE_PLAYERS = 64;
    private static final int MAX_PENDING_BLOCK_EDITS = 1_024;
    private static final int MAX_HOST_LENGTH = 253;
    private static final int RECEIVE_TIMEOUT_MILLIS = 250;
    private static final long HELLO_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final Object lifecycleLock = new Object();
    private final Object editLock = new Object();
    private final UUID id;
    private final String name;
    private final String host;
    private final int port;
    private final AtomicLong outgoingSequence = new AtomicLong();
    private final Map<UUID, NetworkMessage.PlayerState> remotePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> incomingSequences = new ConcurrentHashMap<>();
    private final ArrayDeque<NetworkMessage.BlockEdit> pendingBlockEdits = new ArrayDeque<>();

    private volatile DatagramSocket socket;
    private volatile Thread receiverThread;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile boolean closed;

    public MultiplayerClient(UUID id, String name, String host, int port) {
        if (id == null) {
            throw new IllegalArgumentException("Player id must not be null");
        }
        if (host == null || host.isBlank() || host.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("Host is invalid");
        }
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        NetworkMessageCodec.encode(new NetworkMessage.Hello(id, name));
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /**
     * Starts the receiver and sends the initial hello. Calling this method again while it is
     * running has no effect.
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            if (closed) {
                throw new IllegalStateException("Client is closed");
            }

            DatagramSocket created = null;
            try {
                InetAddress serverAddress = InetAddress.getByName(host);
                created = new DatagramSocket();
                created.connect(new InetSocketAddress(serverAddress, port));
                created.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
                send(new NetworkMessage.Hello(id, name), created);
            } catch (IOException exception) {
                if (created != null) {
                    created.close();
                }
                throw new IllegalStateException("Unable to start multiplayer client", exception);
            }

            DatagramSocket receivingSocket = created;
            Thread thread = new Thread(
                    () -> receiveLoop(receivingSocket),
                    "blockshot-multiplayer-client-" + id);
            thread.setDaemon(true);
            socket = receivingSocket;
            receiverThread = thread;
            running = true;
            connected = true;
            try {
                thread.start();
            } catch (RuntimeException exception) {
                connected = false;
                running = false;
                socket = null;
                receiverThread = null;
                receivingSocket.close();
                throw exception;
            }
        }
    }

    public void sendPlayerState(double x, double y, double z, double yaw) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(yaw, "yaw");
        DatagramSocket sendingSocket = activeSocket();
        long sequence = nextSequence();
        sendOrThrow(new NetworkMessage.PlayerState(id, name, x, y, z, yaw, sequence),
                sendingSocket);
    }

    public void sendBlockEdit(BlockPos pos, BlockMaterial material, boolean placed) {
        if (pos == null) {
            throw new IllegalArgumentException("Block position must not be null");
        }
        if (material == null) {
            throw new IllegalArgumentException("Block material must not be null");
        }
        DatagramSocket sendingSocket = activeSocket();
        long sequence = nextSequence();
        sendOrThrow(new NetworkMessage.BlockEdit(id, sequence, pos, material, placed),
                sendingSocket);
    }

    public Map<UUID, NetworkMessage.PlayerState> remotePlayers() {
        return Map.copyOf(remotePlayers);
    }

    public List<NetworkMessage.BlockEdit> drainBlockEdits() {
        synchronized (editLock) {
            if (pendingBlockEdits.isEmpty()) {
                return List.of();
            }
            List<NetworkMessage.BlockEdit> snapshot =
                    List.copyOf(new ArrayList<>(pendingBlockEdits));
            pendingBlockEdits.clear();
            return snapshot;
        }
    }

    public boolean connected() {
        return connected;
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
            socketToClose = socket;
            threadToJoin = receiverThread;
            if (running && socketToClose != null && !socketToClose.isClosed()) {
                try {
                    send(new NetworkMessage.Goodbye(id), socketToClose);
                } catch (IOException | IllegalArgumentException exception) {
                    // Closing remains best effort when the server is already unavailable.
                }
            }
            connected = false;
            running = false;
            socket = null;
            receiverThread = null;
        }

        if (socketToClose != null) {
            socketToClose.close();
        }
        join(threadToJoin);
        remotePlayers.clear();
        incomingSequences.clear();
        synchronized (editLock) {
            pendingBlockEdits.clear();
        }
    }

    private void receiveLoop(DatagramSocket receivingSocket) {
        byte[] buffer = new byte[NetworkMessageCodec.MAX_PACKET_BYTES + 1];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        long nextHello = System.nanoTime() + HELLO_INTERVAL_NANOS;
        try {
            while (isActive(receivingSocket)) {
                try {
                    packet.setLength(buffer.length);
                    receivingSocket.receive(packet);
                    processPacket(packet);
                } catch (SocketTimeoutException exception) {
                    // The timeout keeps lifecycle and heartbeat checks bounded.
                } catch (SocketException exception) {
                    break;
                } catch (IOException exception) {
                    break;
                }

                long now = System.nanoTime();
                if (now >= nextHello && isActive(receivingSocket)) {
                    try {
                        send(new NetworkMessage.Hello(id, name), receivingSocket);
                    } catch (IOException | IllegalArgumentException exception) {
                        break;
                    }
                    nextHello = now + HELLO_INTERVAL_NANOS;
                }
            }
        } finally {
            receivingSocket.close();
            synchronized (lifecycleLock) {
                if (socket == receivingSocket) {
                    socket = null;
                    receiverThread = null;
                    running = false;
                    connected = false;
                }
            }
        }
    }

    private boolean isActive(DatagramSocket receivingSocket) {
        return running && socket == receivingSocket && !receivingSocket.isClosed();
    }

    private void processPacket(DatagramPacket packet) {
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

        if (message instanceof NetworkMessage.PlayerState state) {
            if (state.id().equals(id) || !hasCapacityFor(state.id())
                    || !acceptSequence(state.id(), state.sequence())) {
                return;
            }
            remotePlayers.put(state.id(), state);
        } else if (message instanceof NetworkMessage.BlockEdit edit) {
            if (edit.actor().equals(id) || !acceptSequence(edit.actor(), edit.sequence())) {
                return;
            }
            synchronized (editLock) {
                if (pendingBlockEdits.size() == MAX_PENDING_BLOCK_EDITS) {
                    pendingBlockEdits.removeFirst();
                }
                pendingBlockEdits.addLast(edit);
            }
        } else if (message instanceof NetworkMessage.Goodbye goodbye
                && !goodbye.id().equals(id)) {
            remotePlayers.remove(goodbye.id());
            incomingSequences.remove(goodbye.id());
        }
    }

    private boolean hasCapacityFor(UUID playerId) {
        return remotePlayers.containsKey(playerId) || remotePlayers.size() < MAX_REMOTE_PLAYERS;
    }

    private boolean acceptSequence(UUID actor, long sequence) {
        if (sequence <= 0) {
            return false;
        }
        Long previous = incomingSequences.get(actor);
        if (previous != null) {
            if (sequence <= previous) {
                return false;
            }
        } else if (incomingSequences.size() >= MAX_REMOTE_PLAYERS) {
            return false;
        }
        incomingSequences.put(actor, sequence);
        return true;
    }

    private DatagramSocket activeSocket() {
        DatagramSocket active = socket;
        if (!running || !connected || active == null || active.isClosed()) {
            throw new IllegalStateException("Client is not connected");
        }
        return active;
    }

    private long nextSequence() {
        while (true) {
            long current = outgoingSequence.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("Outgoing sequence is exhausted");
            }
            if (outgoingSequence.compareAndSet(current, current + 1)) {
                return current + 1;
            }
        }
    }

    private static void requireFinite(double value, String fieldName) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(fieldName + " must be finite");
        }
    }

    private static void sendOrThrow(NetworkMessage message, DatagramSocket sendingSocket) {
        try {
            send(message, sendingSocket);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to send multiplayer message", exception);
        }
    }

    private static void send(NetworkMessage message, DatagramSocket sendingSocket)
            throws IOException {
        byte[] bytes = NetworkMessageCodec.encode(message).getBytes(StandardCharsets.UTF_8);
        sendingSocket.send(new DatagramPacket(bytes, bytes.length));
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length)
            throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString();
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
}