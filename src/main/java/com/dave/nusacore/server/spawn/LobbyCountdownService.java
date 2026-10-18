package com.dave.nusacore.server.spawn;

import com.dave.nusacore.core.message.Messages;
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
 * restarting the countdown. Nothing here runs on its own: {@link LobbyCountdownRuntime} calls {@link #tick} once
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

    /** Server ticks per countdown second, taken from the state machine so the two cannot drift. */
    public static final int TICKS_PER_SECOND = LobbyCountdown.TICKS_PER_SECOND;

    /** What one start produced. */
    public enum StartResult {
        STARTED,
        ALREADY_PENDING,
        DENIED,
        NO_OVERWORLD,
        NO_SAFE_DESTINATION,
        PLAYER_GONE;

        /** True when a countdown is now running. */
        public boolean started() {
            return this == STARTED;
        }

        /** The message for a failed start, or {@code null} when a countdown started. */
        public Component message() {
            return switch (this) {
                case STARTED -> null;
                case ALREADY_PENDING -> Messages.lobbyAlreadyPending();
                case DENIED -> Messages.noPermission();
                case NO_OVERWORLD -> Messages.lobbyNoOverworld();
                case NO_SAFE_DESTINATION -> Messages.lobbyNoSafeDestination();
                case PLAYER_GONE -> Messages.lobbyCountdownCancelledDisconnected();
            };
        }
    }

    /** The game operations one countdown needs. Implementations run on the server thread. */
    public interface Adapter<P> {

        /** The player's unique id, which is the countdown key. */
        UUID id(P player);

        /** True when the player may still run {@code /lobby}: the same node the command gate uses. */
        boolean permitted(P player);

        /** The player's current dimension as a comparable key, or {@code null} when unavailable. */
        Object dimension(P player);

        /** The player's current position. */
        Pose pose(P player);

        /** True when the player is alive and still attached to the server. */
        boolean alive(P player);

        /** The Overworld destination for this player, or {@code null} when the Overworld is missing. */
        LobbyTarget<P> overworld(P player);

        /** True when the player is still connected and not removed, so feedback can be delivered. */
        boolean available(P player);

        /** Sends countdown feedback to the player. */
        void send(P player, Component message);

        /** Plays a countdown step sound for the second being announced. */
        void playStepSound(P player, int remainingSeconds);

        /** Plays the completion cue and moves the player. Returns false when the server refused the move. */
        boolean complete(P player, LobbyTarget<P> target, net.minecraft.core.BlockPos destination);
    }

    private final LobbyService lobbyService;
    private final Adapter<P> adapter;
    private final int seconds;
    private final int ticksPerSecond;
    private final Map<UUID, Pending<P>> pending = new LinkedHashMap<>();

    /**
     * @param lobbyService resolves the safe destination with the same logic the command used before this slice
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

    /** The shipping service: the countdown length from {@link Messages} at {@link #TICKS_PER_SECOND}. */
    public static <P> LobbyCountdownService<P> create(LobbyService lobbyService, Adapter<P> adapter) {
        return new LobbyCountdownService<>(
                lobbyService, adapter, Messages.LOBBY_COUNTDOWN_SECONDS, TICKS_PER_SECOND);
    }

    /** True when this player already has a countdown running. */
    public boolean isPending(P player) {
        return pending.containsKey(adapter.id(player));
    }

    /** The keys of the running countdowns, in the order they started. */
    public Collection<UUID> pendingKeys() {
        return List.copyOf(pending.keySet());
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
     * @return what happened, so the caller can choose the command result
     */
    public StartResult start(P player) {
        Objects.requireNonNull(player, "player");
        if (pending.containsKey(adapter.id(player))) {
            return report(player, StartResult.ALREADY_PENDING);
        }
        if (!adapter.permitted(player)) {
            return report(player, StartResult.DENIED);
        }
        LobbyTarget<P> target = adapter.overworld(player);
        LobbyService.Outcome outcome = lobbyService.resolve(target == null ? null : target.surface());
        if (!outcome.ready()) {
            return report(player, outcome.status() == LobbyService.Outcome.Status.NO_OVERWORLD
                    ? StartResult.NO_OVERWORLD
                    : StartResult.NO_SAFE_DESTINATION);
        }
        Pose pose = adapter.pose(player);
        if (pose == null || !adapter.available(player) || !adapter.alive(player)) {
            return report(player, StartResult.PLAYER_GONE);
        }
        pending.put(adapter.id(player), new Pending<>(
                player,
                target,
                outcome.destination(),
                adapter.dimension(player),
                LobbyCountdown.start(seconds, ticksPerSecond, pose)));
        adapter.send(player, Messages.lobbyCountdownStarted(seconds));
        adapter.playStepSound(player, seconds);
        return StartResult.STARTED;
    }

    private StartResult report(P player, StartResult result) {
        Component message = result.message();
        if (message != null) {
            adapter.send(player, message);
        }
        return result;
    }

    /**
     * Advances every running countdown by one server tick.
     *
     * <p>A countdown that ends is removed from the map before anything is sent, so an ending is reported once and
     * the player can start a new request on the next tick.
     */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        for (Pending<P> entry : new ArrayList<>(pending.values())) {
            UUID key = adapter.id(entry.player());
            if (pending.get(key) != entry) {
                continue;
            }
            if (!adapter.available(entry.player())) {
                // The player is already gone, so nothing is sent: the countdown just ends.
                pending.remove(key, entry);
                continue;
            }
            finish(key, entry, advance(entry));
        }
    }

    private LobbyCountdown.Tick advance(Pending<P> entry) {
        return entry.countdown().tick(
                adapter.pose(entry.player()),
                adapter.alive(entry.player()),
                adapter.permitted(entry.player()),
                Objects.equals(adapter.dimension(entry.player()), entry.dimension()));
    }

    private void finish(UUID key, Pending<P> entry, LobbyCountdown.Tick result) {
        if (result == null) {
            return;
        }
        if (result.secondsToShow() > 0) {
            adapter.send(entry.player(), Messages.lobbyCountdownTick(result.secondsToShow()));
            adapter.playStepSound(entry.player(), result.secondsToShow());
        }
        State state = result.state();
        if (!state.ended()) {
            return;
        }
        pending.remove(key, entry);
        if (state.teleports()) {
            if (adapter.complete(entry.player(), entry.target(), entry.destination())) {
                adapter.send(entry.player(), Messages.lobbySuccess());
            } else {
                adapter.send(entry.player(), Messages.lobbyTeleportFailed());
            }
            return;
        }
        Component message = state.message();
        if (message != null) {
            adapter.send(entry.player(), message);
        }
    }

    /**
     * Cancels a player's countdown for a reason outside the tick loop, such as a disconnect or a death.
     *
     * @param reported true when the player can still receive the message
     * @return the ending state, or {@code null} when the player had no countdown
     */
    public State cancel(P player, State reason, boolean reported) {
        Objects.requireNonNull(reason, "reason");
        Pending<P> entry = pending.get(adapter.id(player));
        if (entry == null) {
            return null;
        }
        LobbyCountdown.Tick result = entry.countdown().cancel(reason);
        if (result == null) {
            return null;
        }
        pending.remove(adapter.id(player), entry);
        if (reported) {
            Component message = result.state().message();
            if (message != null) {
                adapter.send(entry.player(), message);
            }
        }
        return result.state();
    }

    /** Cancels every running countdown, for a server stop. Returns how many were cancelled. */
    public int cancelAll(State reason, boolean reported) {
        int cancelled = 0;
        for (Pending<P> entry : new ArrayList<>(pending.values())) {
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