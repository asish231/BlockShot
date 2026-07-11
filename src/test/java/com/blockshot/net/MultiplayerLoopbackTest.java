package com.blockshot.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class MultiplayerLoopbackTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final long AWAIT_PAUSE_NANOS = Duration.ofMillis(2).toNanos();

    @Test
    void givenTwoClients_whenStateAndEditAreSent_thenPeerReceivesThem() {
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        MultiplayerServer server = new MultiplayerServer(0);
        server.start();
        MultiplayerClient sender = new MultiplayerClient(
                senderId, "Sender | \u2603", "127.0.0.1", server.port());
        MultiplayerClient receiver = new MultiplayerClient(
                receiverId, "Receiver", "127.0.0.1", server.port());

        try (server; sender; receiver) {
            assertTrue(server.port() > 0);
            int boundPort = server.port();
            server.start();
            assertEquals(boundPort, server.port());
            sender.start();
            sender.start();
            receiver.start();
            await("both clients to join", () -> server.clientCount() == 2);

            sender.sendPlayerState(12.5, -3.0, 8.25, 91.0);
            await("the player state to arrive", () -> {
                NetworkMessage.PlayerState state = receiver.remotePlayers().get(senderId);
                return state != null
                        && state.name().equals("Sender | \u2603")
                        && state.x() == 12.5
                        && state.y() == -3.0
                        && state.z() == 8.25
                        && state.yaw() == 91.0;
            });

            assertFalse(sender.remotePlayers().containsKey(senderId));
            assertFalse(receiver.remotePlayers().containsKey(receiverId));

            BlockPos position = new BlockPos(-4, 7, 19);
            sender.sendBlockEdit(position, BlockMaterial.GLASS, true);
            AtomicReference<NetworkMessage.BlockEdit> receivedEdit = new AtomicReference<>();
            await("the block edit to arrive", () -> receiver.drainBlockEdits().stream()
                    .filter(edit -> edit.actor().equals(senderId))
                    .findFirst()
                    .map(edit -> {
                        receivedEdit.set(edit);
                        return true;
                    })
                    .orElse(false));

            NetworkMessage.BlockEdit edit = receivedEdit.get();
            assertEquals(position, edit.pos());
            assertEquals(BlockMaterial.GLASS, edit.material());
            assertTrue(edit.placed());
        }

        assertFalse(sender.connected());
        assertFalse(receiver.connected());
        assertEquals(0, server.clientCount());
        sender.close();
        receiver.close();
        server.close();
    }

    @Test
    void givenAnExistingState_whenAnotherClientJoins_thenCachedStateIsSent() {
        UUID senderId = UUID.randomUUID();
        MultiplayerServer server = new MultiplayerServer(0);
        server.start();
        MultiplayerClient sender = new MultiplayerClient(
                senderId, "Sender", "127.0.0.1", server.port());
        MultiplayerClient witness = new MultiplayerClient(
                UUID.randomUUID(), "Witness", "127.0.0.1", server.port());
        MultiplayerClient lateJoiner = new MultiplayerClient(
                UUID.randomUUID(), "Late", "127.0.0.1", server.port());

        try (server; sender; witness; lateJoiner) {
            sender.start();
            witness.start();
            await("the initial clients to join", () -> server.clientCount() == 2);
            sender.sendPlayerState(3, 4, 5, 6);
            await("the server to relay the initial state",
                    () -> witness.remotePlayers().containsKey(senderId));

            lateJoiner.start();

            await("the cached state to reach the late joiner",
                    () -> lateJoiner.remotePlayers().containsKey(senderId));
        }
    }

    @Test
    void givenMalformedOrUntrustedPackets_whenReceived_thenServerKeepsOnlyValidTraffic()
            throws IOException {
        UUID observerId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID spoofedId = UUID.randomUUID();
        MultiplayerServer server = new MultiplayerServer(0);
        server.start();
        MultiplayerClient observer = new MultiplayerClient(
                observerId, "Observer", "127.0.0.1", server.port());

        try (server; observer; DatagramSocket rawPeer = new DatagramSocket()) {
            observer.start();
            await("the observer to join", () -> server.clientCount() == 1);

            send(rawPeer, server.port(), new NetworkMessage.PlayerState(
                    peerId, "Raw", 1, 1, 1, 1, 1));
            sendRaw(rawPeer, server.port(), "not-a-valid-packet");
            send(rawPeer, server.port(), new NetworkMessage.Hello(peerId, "Raw"));
            await("the valid hello after malformed traffic", () -> server.clientCount() == 2);
            assertFalse(observer.remotePlayers().containsKey(peerId));

            send(rawPeer, server.port(), new NetworkMessage.PlayerState(
                    spoofedId, "Spoofed", 9, 9, 9, 9, 1));
            send(rawPeer, server.port(), new NetworkMessage.PlayerState(
                    peerId, "Raw", 0, 0, 0, 0, 0));
            send(rawPeer, server.port(), new NetworkMessage.PlayerState(
                    peerId, "Raw", 2, 2, 2, 2, 2));
            await("the valid state", () -> {
                NetworkMessage.PlayerState state = observer.remotePlayers().get(peerId);
                return state != null && state.x() == 2;
            });
            assertFalse(observer.remotePlayers().containsKey(spoofedId));

            send(rawPeer, server.port(), new NetworkMessage.PlayerState(
                    peerId, "Raw", 1, 1, 1, 1, 1));
            send(rawPeer, server.port(), new NetworkMessage.BlockEdit(
                    peerId, 3, new BlockPos(1, 2, 3), BlockMaterial.STONE, false));
            await("the sequence barrier block edit", () -> !observer.drainBlockEdits().isEmpty());
            assertEquals(2, observer.remotePlayers().get(peerId).x());

            send(rawPeer, server.port(), new NetworkMessage.Goodbye(peerId));
            await("the raw peer to leave", () -> server.clientCount() == 1);
            await("the departed player to be removed",
                    () -> !observer.remotePlayers().containsKey(peerId));
        }
    }

    @Test
    void givenOutOfOrderServerPackets_whenClientReceivesThem_thenOnlyNewTrafficIsExposed()
            throws IOException {
        UUID clientId = UUID.randomUUID();
        UUID remoteId = UUID.randomUUID();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket fakeServer = new DatagramSocket(0, loopback)) {
            fakeServer.setSoTimeout((int) AWAIT_TIMEOUT.toMillis());
            MultiplayerClient client = new MultiplayerClient(
                    clientId, "Client", "127.0.0.1", fakeServer.getLocalPort());

            try (client) {
                client.start();
                byte[] helloBuffer = new byte[NetworkMessageCodec.MAX_PACKET_BYTES];
                DatagramPacket helloPacket = new DatagramPacket(helloBuffer, helloBuffer.length);
                fakeServer.receive(helloPacket);
                assertEquals(new NetworkMessage.Hello(clientId, "Client"),
                        NetworkMessageCodec.decode(new String(
                                helloPacket.getData(), helloPacket.getOffset(),
                                helloPacket.getLength(), StandardCharsets.UTF_8)));
                SocketAddress clientAddress = helloPacket.getSocketAddress();

                sendRaw(fakeServer, clientAddress, "malformed");
                send(fakeServer, clientAddress, new NetworkMessage.PlayerState(
                        remoteId, "Remote", 2, 2, 2, 2, 2));
                await("the latest remote state", () -> {
                    NetworkMessage.PlayerState state = client.remotePlayers().get(remoteId);
                    return state != null && state.x() == 2;
                });

                send(fakeServer, clientAddress, new NetworkMessage.PlayerState(
                        remoteId, "Remote", 1, 1, 1, 1, 1));
                send(fakeServer, clientAddress, new NetworkMessage.PlayerState(
                        clientId, "Client", 9, 9, 9, 9, 100));
                send(fakeServer, clientAddress, new NetworkMessage.BlockEdit(
                        remoteId, 3, new BlockPos(6, 7, 8), BlockMaterial.GLASS, true));
                await("the client sequence barrier", () -> !client.drainBlockEdits().isEmpty());

                assertEquals(2, client.remotePlayers().get(remoteId).x());
                assertFalse(client.remotePlayers().containsKey(clientId));

                send(fakeServer, clientAddress, new NetworkMessage.Goodbye(remoteId));
                await("the remote player to be removed",
                        () -> !client.remotePlayers().containsKey(remoteId));
            }
        }
    }

    @Test
    void givenClientSnapshots_whenRead_thenTheyAreImmutable() {
        MultiplayerClient client = new MultiplayerClient(
                UUID.randomUUID(), "Player", "127.0.0.1", 1);

        Map<UUID, NetworkMessage.PlayerState> snapshot = client.remotePlayers();
        List<NetworkMessage.BlockEdit> edits = client.drainBlockEdits();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(UUID.randomUUID(), null));
        assertThrows(UnsupportedOperationException.class,
                () -> edits.add(null));
        client.close();
        assertFalse(client.connected());
    }

    @Test
    void givenInvalidArguments_whenRuntimeIsUsed_thenTheyAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MultiplayerServer(-1));
        assertThrows(IllegalArgumentException.class, () -> new MultiplayerServer(65_536));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiplayerClient(null, "Player", "localhost", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiplayerClient(UUID.randomUUID(), "", "localhost", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiplayerClient(UUID.randomUUID(), "Player", "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiplayerClient(UUID.randomUUID(), "Player", "localhost", 0));

        MultiplayerClient client = new MultiplayerClient(
                UUID.randomUUID(), "Player", "localhost", 1);
        assertThrows(IllegalArgumentException.class,
                () -> client.sendPlayerState(Double.NaN, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> client.sendBlockEdit(null, BlockMaterial.STONE, true));
        assertThrows(IllegalArgumentException.class,
                () -> client.sendBlockEdit(new BlockPos(0, 0, 0), null, true));
        assertThrows(IllegalStateException.class,
                () -> client.sendPlayerState(0, 0, 0, 0));
        client.close();
        assertFalse(client.connected());
        assertThrows(IllegalStateException.class, client::start);
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(AWAIT_PAUSE_NANOS);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description);
    }

    private static void send(DatagramSocket socket, int port, NetworkMessage message)
            throws IOException {
        sendRaw(socket, port, NetworkMessageCodec.encode(message));
    }

    private static void sendRaw(DatagramSocket socket, int port, String message)
            throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(
                bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }

    private static void send(DatagramSocket socket, SocketAddress destination,
                             NetworkMessage message) throws IOException {
        sendRaw(socket, destination, NetworkMessageCodec.encode(message));
    }

    private static void sendRaw(DatagramSocket socket, SocketAddress destination, String message)
            throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, destination));
    }
}