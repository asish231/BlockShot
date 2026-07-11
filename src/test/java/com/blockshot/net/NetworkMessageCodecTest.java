package com.blockshot.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkMessageCodecTest {

    @Test
    void givenPlayerState_whenEncodedAndDecoded_thenRoundTripIsLossless() {
        NetworkMessage.PlayerState state = new NetworkMessage.PlayerState(
                UUID.randomUUID(), "Pilot One", 12.5, 8, -3.25, 91, 42);

        NetworkMessage decoded = NetworkMessageCodec.decode(NetworkMessageCodec.encode(state));

        assertEquals(state, decoded);
    }

    @Test
    void givenBlockEdit_whenEncodedAndDecoded_thenStableMaterialIdIsUsed() {
        NetworkMessage.BlockEdit edit = new NetworkMessage.BlockEdit(
                UUID.randomUUID(), 17, new BlockPos(-22, 5, 40), BlockMaterial.GLASS, true);

        assertEquals(edit, NetworkMessageCodec.decode(NetworkMessageCodec.encode(edit)));
    }

    @Test
    void givenMalformedPacket_whenDecoded_thenItIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> NetworkMessageCodec.decode("STATE|missing|fields"));
    }
}