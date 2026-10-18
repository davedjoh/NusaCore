package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.spawn.LobbyService;
import com.dave.nusacore.core.spawn.SpawnSurface;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Set;

/**
 * A live server level as the {@code /lobby} destination.
 *
 * <p>The view and the teleport are built from the same level, so the resolved position always belongs to the
 * level the player is moved into. The teleport builds a {@link TeleportTransition}, the mechanism portals and
 * respawns use, so vanilla loads the destination chunk and the client follows a dimension change.
 */
public final class LevelLobbyTarget implements LobbyTarget<ServerPlayer> {
    private final ServerLevel level;
    private final SpawnSurface surface;

    public LevelLobbyTarget(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        this.surface = new LevelSpawnSurface(level);
    }

    @Override
    public SpawnSurface surface() {
        return surface;
    }

    @Override
    public boolean teleport(ServerPlayer player, BlockPos destination) {
        return player.teleport(new TeleportTransition(
                level,
                LobbyService.feetPosition(destination),
                Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                Set.<Relative>of(),
                TeleportTransition.DO_NOTHING)) != null;
    }
}
