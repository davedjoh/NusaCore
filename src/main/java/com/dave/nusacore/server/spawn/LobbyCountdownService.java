package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.permission.PermissionNodes;
import com.dave.nusacore.core.spawn.LobbyCountdown;
import com.dave.nusacore.core.spawn.LobbyCountdown.Pose;
import com.dave.nusacore.core.spawn.LobbyCountdown.State;
import com.dave.nusacore.core.spawn.LobbyService;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One pending {@code /lobby} countdown per player, and the rules for starting, ticking, and ending them.
 *
 * <p>This is the single owner of countdown state. It holds one {@link LobbyCountdown} per player at most, so a
 * second request from the same player while one is pending is refused instead of queuing, replacing, or
 * restarting the countdown. Nothing here runs on its own: {@link LobbyCountdownTicker} calls {@link #tick} once
 * per server tick, and the player lifecycle hooks call {@link #cancel} when a player leaves, dies, or the server
 * stops.
 *
 * <p>Everything is in memory and on the server thread. There is no saved state, no disk access, no timer thread,
 * and no {@code Thread#sleep}: a countdown that is pending when the server stops is simply gone.
 *
 * <p>The player type is a parameter because a live {@code ServerPlayer} cannot be built in a unit test. Production
 * uses {@code ServerPlayer}; a test uses a token player and records what the adapter receives. Every game call
 * sits behind {@link Adapter}, which also holds the teleport, so the countdown can never move a player on its own.
 */
public final class LobbyCountdownService<P> {

    /** What one start produced. */
    public enum StartResult {
        STARTED,
        ALREADY_PENDING,
        /** The permission node is gone by the time the request runs. */
        DENIED,
        /** There is no Overworld to teleport to. */
        NO_OVERWORLD,
        /** Nothing safe was found near the world spawn. */
        NO_SAFE_DESTINATION
    }

    /** The game operations one countdown needs. Implementations run on the server thread. */
    public interface Adapter<P> {

        /** The player's unique id, which is the countdown key. */
        UUID id(P);

        /** True when the player may still run {@code /lobby}: the same node the command gate uses. */
        boolean permitted(P player);

        /** The player's current dimension as a comparable key, or {@code null} when unavailable. */
        Object dimension(P player);

        /** The player's current position. */
        Pose pose(P player);

        /** True when the player is alive and still attached to the server. */
        boolean alive(P player);

        /** The Overworld destination, or {@code null} when the server has no Overworld. */
        LobbyTarget<P> overworld();

        /** Sends countdown feedback to the player. */
        void send(P player, Component message);

        /** Plays a countdown step sound for the player. */
        void playStepSound(P player);

        /** Plays the completion sound and reports success for the player. */
        void playCompleteSound(P player);

        /** Moves the player. Called only after every check passed on the last second. */
        void teleport(P player, LobbyTarget<P> target, net.minecraft.core.BlockPos destination);
    }

    private final LobbyService lobbyService;
    private final Adapter<P> adapter;
    private final int seconds;
    private final int ticksPerSecond;
    private final Map<UUID, Pending<P>> pending = new LinkedHashMap<>();

    /**
     * @param lobbyService resolves the safe destination, the same logic the command used before this slice
     * @param adapter the game operations
     * @param seconds countdown length in one-second steps
     * @param ticksPerSecond server ticks per step
     */
    public LobbyCountdownService(LobbyService lobbyService, Adapter<P> adapter, int seconds, int ticksPerSecond) {
        this.lobbyService = Objects.requireNonNull(lobbyService, "lobbyService");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.seconds = seconds;
        this.ticksPerSecond = ticksPerSecond;
    }

    /** The default service: the countdown length from {@link Messages}, at the vanilla 20 ticks per second. */
    public static <P> LobbyCountdownService<P> create(LobbyService lobbyService, Adapter<P> adapter) {
        return new LobbyCountdownService<>(
                lobbyService, adapter, Messages.LOBBY_COUNTDOWN_SECONDS, TICKS_PER_SECOND);
    }

    /** True when this player already has a countdown running. */
    public boolean isPending(P player) {
        return pending.containsKey(adapter.id(player));
    }

    /** The players with a running countdown, in the order they started. */
    public Collection<UUID> pendingPlayers() {
        return pending.keySet();
    }

    /** How many countdowns are running. */
    public int pendingCount() {
        return pending.size();
    }

    /** The remaining seconds for a running countdown, or empty when the player has none. */
    public Optional<Integer> remainingSeconds(P player) {
        Pending<P> entry = pending.get(adapter.id(player));
        return entry == null ? Optional.empty() : Optional.of(entry.countdown().remainingSeconds());
    }

    /**
     * Starts a countdown for one player.
     *
     * <p>The checks run in a fixed order and each one can end the request: the node, the Overworld, the safe
     * destination, the player's liveness, and only then the countdown. The destination is resolved once, here, so
     * the safety rules see the world as it is when the player asks; the teleport itself re-checks the player.
     *
     * @return what happened, so the caller can decide the command result
     */
    public StartResult start(P player) {
        Objects.requireNonNull(player, "player");
        UUID id = adapter.id(player);
        if (pending.containsKey(id)) {
            adapter.send(player, Messages.lobbyAlreadyPending());
            return StartResult.ALREADY_PENDING;
        }
        if (!adapter.permitted(player)) {
            adapter.send(player, Messages.noPermission());
            return StartResult.DENIED;
        }
        LobbyTarget<P> target = adapter.overworld();
        LobbyService.Outcome outcome = lobbyService.resolve(target == null ? null : target.surface());
        if (outcome.status() == LobbyService.Outcome.Status.NO_OVERWORLD) {
            adapter.send(player, LobbyService.message(outcome));
            return StartResult.NO_OVERWORLD;
        }
        if (!outcome.ready()) {
            adapter.send(player, LobbyService.message(outcome));
            return StartResult.NO_SAFE_DESTINATION;
        }
        Pose pose = adapter.pose(player);
        if (pose == null || !adapter.alive(player)) {
            adapter.send(player, Messages.lobbyCountdownCancelledDisconnected());
            return StartResult.DENIED;
        }
        Object dimension = adapter.dimension(player);
        pending.put(id, new Pending<>(
                player,
                target,
                outcome.destination(),
                dimension,
                LobbyCountdown.start(seconds, ticksPerSecond, pose)));
        adapter.send(player, Messages.lobbyCountdownStarted(seconds));
        adapter.playStepSound(player);
        return StartResult.STARTED;
    }

    /**
     * Advanced every running countdown by one server tick.
     *
     * <p>A countdown that ends is removed from the map before anything is sent, so no ending can be reported
     * twice and a player can start a new request on the next tick.
     */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        List<Pending<P>> entries = new ArrayList<>(pending.values());
        for (Pending<P> entry : entries) {
            UUID id = adapter.id(entry.player());
            if (pending.get(id) != entry) {
                continue;
            }
            if (!adapter.alive(entry.player())) {
                end(id, entry, entry.countdown().cancel(State.CANCELLED_DISCONNECTED));
                continue;
            }
            LobbyCountdown.Tick result = entry.countdown().tick(
                    adapter.pose(entry.player()),
                    true,
                    adapter.permitted(entry.player()),
                    Objects.equals(adapter.dimension(entry.player()), entry.dimension()));
            if (result.secondsToShow() > 0) {
                adapter.send(entry.player(), Messages.lobbyCountdownTick(result.secondsToShow()));
                adapter.playStepSound(entry.player());
            }
            if (result.state().ended()) {
                end(id, entry, result);
            }
        }
    }

    private void end(UUID id, Pending<P> entry, LobbyCountdown.Tick result) {
        if (result == null) {
            return;
        }
        pending.remove(id, entry);
        State state = result.state();
        if (state.teleports()) {
            adapter.playCompleteSound(entry.player());
            adapter.teleport(entry.player(), entry.target(), entry.destination());
        }
        Component message = state.message();
        if (message != null) {
            adapter.send(entry.player(), message);
        }
    }

    /**
     * Cancels a player's countdown for a reason outside the tick loop, such as a disconnect.
     *
     * @param reported true when the player can still receive the message
     * @return the ending state, or {@code null} when the player had no countdown
     */
    public State cancel(P player, State reason, boolean reported) {
        Objects.requireNonNull(reason, "reason");
        UUID id = adapter.id(player);
        Pending<P> entry = pending.get(id);
        if (entry == null) {
            return null;
        }
        pending.remove(id);
        LobbyCountdown.Tick result = entry.countdown().cancel(reason);
        if (result == null) {
            return null;
        }
        if (reported) {
            Component message = result.state().message();
            if (message != null) {
                adapter.send(entry.player(), message);
            }
        }
        return result.state();
    }

    /** Cancels every running countdown, for a server stop. */
    public int cancelAll(State reason, boolean reported) {
        List<Pending<P>> entries = new ArrayList<>(pending.values());
        int cancelled = 0;
        for (Pending<P> entry : entries) {
            if (cancel(entry.player(), reason, reported) != null) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /** One player's pending request: where it will go, and how long until it may. */
    private record Pending<P>(
            P player,
            LobbyTarget<P> target,
            net.minecraft.core.BlockPos destination,
            Object dimension,
            LobbyCountdown countdown) {
    }
}
