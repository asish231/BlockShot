package com.blockshot.net;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.UUID;

public sealed interface NetworkMessage permits NetworkMessage.Hello, NetworkMessage.PlayerState,
        NetworkMessage.BlockEdit, NetworkMessage.Goodbye {

    record Hello(UUID id, String name) implements NetworkMessage {
    }

    record PlayerState(UUID id, String name, double x, double y, double z, double yaw,
                       long sequence) implements NetworkMessage {
    }

    record BlockEdit(UUID actor, long sequence, BlockPos pos, BlockMaterial material,
                     boolean placed) implements NetworkMessage {
    }

    record Goodbye(UUID id) implements NetworkMessage {
    }
}