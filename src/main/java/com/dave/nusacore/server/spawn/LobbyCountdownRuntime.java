package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.permission.PermissionNodes;
import com.dave.nusacore.core.spawn.LobbyCountdown.Pose;
import com.dave.nusacore.core.spawn.LobbyCountdown.State;
import com.dave.nusacore.core.spawn.LobbyService;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import me.lucko.fabric.api.permissions.v0.Permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * The live-server side of the {@code /lobby} countdown: the {@link ServerPlayer} adapter and the event hooks that
 * drive and clean up countdowns.
 *
 * <p>Timing uses the server tick loop, the same mechanism the rest of the server runs on: {@link #tick} runs once
 * per server tick and only advances countdowns that exist, so an idle server does no countdown work. Nothing here
 * sleeps, blocks, starts a thread, or reads a file.
 *
 * <p>Cleanup: a disconnect cancels that player's countdown before the player object is discarded, and a server
 * stop cancels every remaining countdown while players can still be told. Death and dimension changes are handled
 * by the countdown itself, so every ending the player can see has one code path.
 *
 * <p>Feedback is vanilla and private: the countdown plays {@code ui.button.click} on the UI channel at a pitch
 * that rises each second, and the teleport plays {@code ui.toast.challenge_complete}. Sounds are sent to the
 * player's own connection only, so no other player hears another player's countdown.
 */
public final class LobbyCountdownRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("nusacore");

    /** Sound volume, the pitch of the first countdown second, and the pitch added per later second. */
    private static final float SOUND_VOLUME = 1.0F;
    private static final float MIN_PITCH = 1.0F;
    private static final float PITCH_STEP = 0.1F;
    private static final float MAX_PITCH = 2.0F;

    private final LobbyCountdownService<ServerPlayer> service;

    /**
     * @param service the countdown owner
     */
    public LobbyCountdownRuntime(LobbyCountdownService<ServerPlayer> service) {
        this.service = service;
    }

    /**
     * Creates the runtime with the shipping countdown and subscribes it to the server events that drive it.
     *
     * @param lobbyService the safe destination logic
     * @return the runtime, which owns the only live countdown state
     */
    public static LobbyCountdownRuntime install(LobbyService lobbyService) {
        LobbyCountdownRuntime runtime =
                new LobbyCountdownRuntime(LobbyCountdownService.create(lobbyService, new ServerPlayerAdapter()));
        runtime.registerEvents();
        return runtime;
    }

    /** The countdown state for commands to start and inspect. */
    public LobbyCountdownService<ServerPlayer> service() {
        return service;
    }

    /** Advances every running countdown by one tick. */
    public void tick() {
        service.tick();
    }

    /** Cancels every running countdown for a server stop, telling each player who can still hear it. */
    public int cancelAllOnStop() {
        return service.cancelAll(State.CANCELLED_SERVER_STOPPING, true);
    }

    private void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            @Override
            public void onEndTick(MinecraftServer server) {
                if (service.pendingCount() > 0) {
                    service.tick();
                }
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register(new ServerPlayConnectionEvents.Disconnect() {
            @Override
            public void onPlayDisconnect(
                    net.minecraft.server.network.ServerGamePacketListenerImpl handler, MinecraftServer server) {
                service.cancel(handler.getPlayer(), State.CANCELLED_DISCONNECTED, false);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(new ServerLifecycleEvents.ServerStopping() {
            @Override
            public void onServerStopping(MinecraftServer server) {
                int cancelled = cancelAllOnStop();
                if (cancelled > 0) {
                    LOGGER.info("Cancelled {} pending /lobby countdown(s): the server is stopping", cancelled);
                }
            }
        });
    }

    /** Sends one sound to one player's client only, on the UI channel so it is not positioned in the world. */
    static void play(ServerPlayer player, Holder<SoundEvent> sound, float pitch) {
        if (!ServerPlayerAdapter.canReceive(player)) {
            return;
        }
        player.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.UI, 0.0, 0.0, 0.0, SOUND_VOLUME, pitch, player.getRandom().nextLong()));
    }

    /** The two vanilla sounds the countdown uses, kept together so they stay distinct. */
    public static final class Sounds {
        /** Countdown step, on the UI channel so it is not positioned in the world. */
        public static final Holder<SoundEvent> STEP = SoundEvents.UI_BUTTON_CLICK;
        /** Completion cue, also used by vanilla's challenge-complete toast. */
        public static final SoundEvent COMPLETE = SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;

        private Sounds() {
        }
    }

    /** Sends one completion sound, resolved as a holder through the player's own registry. */
    static void playComplete(ServerPlayer player) {
        if (!ServerPlayerAdapter.canReceive(player)) {
            return;
        }
        play(player, player.level().getServer().registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .wrapAsHolder(Sounds.COMPLETE), 1.0F);
    }

    /**
     * The countdown step sound and its pitch for a second.
     *
     * <p>The countdown announces the full number first, then each later second, so the pitch rises with every
     * announcement and the last second before the move is the highest.
     *
     * @param remainingSeconds the number being announced, at least 1
     */
    static void playStep(ServerPlayer player, int remainingSeconds) {
        int elapsed = Math.max(0, Messages.LOBBY_COUNTDOWN_SECONDS - remainingSeconds);
        play(player, Sounds.STEP, Math.min(MAX_PITCH, MIN_PITCH + elapsed * PITCH_STEP));
    }

    /**
     * The {@link ServerPlayer} side of the countdown.
     *
     * <p>It uses the production permission helper, so the countdown's last check is exactly the check the command
     * gate made.
     */
    public static final class ServerPlayerAdapter implements LobbyCountdownService.Adapter<ServerPlayer> {

        @Override
        public UUID id(ServerPlayer player) {
            return player.getUUID();
        }

        @Override
        public boolean permitted(ServerPlayer player) {
            return Permissions.check(player, PermissionNodes.SPAWN_LOBBY, PermissionNodes.DEFAULT_DENIED);
        }

        @Override
        public Object dimension(ServerPlayer player) {
            ServerLevel level = player.level();
            return level == null ? null : level.dimension();
        }

        @Override
        public Pose pose(ServerPlayer player) {
            return new Pose(player.getX(), player.getY(), player.getZ());
        }

        @Override
        public boolean alive(ServerPlayer player) {
            return player.isAlive() && !player.isRemoved();
        }

        @Override
        public boolean available(ServerPlayer player) {
            return canReceive(player);
        }

        /** True when the player is still connected and attached, so a packet or message can be delivered. */
        static boolean canReceive(ServerPlayer player) {
            return !player.hasDisconnected() && !player.isRemoved();
        }

        @Override
        public LobbyTarget<ServerPlayer> overworld(ServerPlayer player) {
            ServerLevel level = player.level();
            MinecraftServer server = level == null ? null : level.getServer();
            ServerLevel overworld = server == null ? null : server.overworld();
            return overworld == null ? null : new LevelLobbyTarget(overworld);
        }

        @Override
        public void send(ServerPlayer player, Component message) {
            if (available(player)) {
                player.sendSystemMessage(message);
            }
        }

        @Override
        public void playStepSound(ServerPlayer player, int remainingSeconds) {
            playStep(player, remainingSeconds);
        }

        /**
         * Plays the completion cue and moves the player.
         *
         * <p>The cue is sent only when the server accepted the move, so the sound never promises a teleport that
         * did not happen. The success text is sent by the caller.
         */
        @Override
        public boolean complete(ServerPlayer player, LobbyTarget<ServerPlayer> target, BlockPos destination) {
            if (!available(player)) {
                return false;
            }
            boolean moved = target.teleport(player, destination);
            if (moved) {
                playComplete(player);
            }
            return moved;
        }
    }
}
