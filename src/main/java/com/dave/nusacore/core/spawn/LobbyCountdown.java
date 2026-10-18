package com.dave.nusacore.core.spawn;

import com.dave.nusacore.core.message.Messages;

import net.minecraft.network.chat.Component;

/**
 * The pure state of one pending {@code /lobby} countdown: whether it may still finish, how many seconds are left,
 * and how many server ticks each second lasts.
 *
 * <p>A countdown is started once, allowed to tick once per second of server time, and ends in exactly one way:
 * it completes and the player is teleported, or it is cancelled and the player stays where they are. There is no
 * way to restart, extend, or skip a countdown, so a player cannot make a pending teleport outlast the conditions
 * it was started under.
 *
 * <p>Every transition is explicit and returns the feedback to send, so the whole decision is testable without a
 * running server. {@link LobbyCountdownService} owns the instance and the clock; this class holds neither a
 * player, a level, nor the current tick.
 *
 * <p>Player-facing text comes from {@link Messages} through {@link State#message()}; this class holds no text.
 */
public final class LobbyCountdown {

    /** The most seconds a countdown may last, taken from the shared countdown length. */
    public static final int MAX_SECONDS = Messages.LOBBY_COUNTDOWN_SECONDS;

    /** Server ticks in one countdown second: vanilla's nominal tick rate is 20 per second. */
    public static final int TICKS_PER_SECOND = 20;

    /** Position changes at or below this distance are treated as the same spot, not as movement. */
    private static final double POSITION_EPSILON = 1.0E-4;

    /** How a countdown ended, or that it is still running. */
    public enum State {
        RUNNING,
        COMPLETED,
        CANCELLED_MOVED,
        CANCELLED_DIED,
        CANCELLED_DIMENSION,
        CANCELLED_DENIED,
        CANCELLED_DISCONNECTED,
        CANCELLED_SERVER_STOPPING;

        /** True once the countdown will never tick again. */
        public boolean ended() {
            return this != RUNNING;
        }

        /** True when this ending teleports the player. */
        public boolean teleports() {
            return this == COMPLETED;
        }

        /** The message explaining this state, or {@code null} for {@link #RUNNING}. */
        public Component message() {
            return switch (this) {
                case RUNNING -> null;
                case COMPLETED -> Messages.lobbySuccess();
                case CANCELLED_MOVED -> Messages.lobbyCountdownCancelledMoved();
                case CANCELLED_DIED -> Messages.lobbyCountdownCancelledDied();
                case CANCELLED_DIMENSION -> Messages.lobbyCountdownCancelledDimension();
                case CANCELLED_DENIED -> Messages.lobbyCountdownCancelledDenied();
                case CANCELLED_DISCONNECTED -> Messages.lobbyCountdownCancelledDisconnected();
                case CANCELLED_SERVER_STOPPING -> Messages.lobbyCountdownServerStopping();
            };
        }
    }

    /** The player position a countdown remembers, so a later tick can tell whether the player moved. */
    public record Pose(double x, double y, double z) {
        /** True when {@code other} is the same spot. Looking around is not movement and is not recorded. */
        public boolean samePlace(Pose other) {
            return Math.abs(x - other.x) <= POSITION_EPSILON
                    && Math.abs(y - other.y) <= POSITION_EPSILON
                    && Math.abs(z - other.z) <= POSITION_EPSILON;
        }
    }

    /** What one tick produced: the new state and the seconds to show, or {@code 0} when nothing is shown. */
    public record Tick(State state, int secondsToShow) {
        /** A tick that showed nothing and kept the countdown running. */
        public static final Tick NOTHING = new Tick(State.RUNNING, 0);
    }

    private final int ticksPerSecond;
    private final Pose start;
    private int remainingSeconds;
    private int ticksInCurrentStep;
    private State state = State.RUNNING;

    private LobbyCountdown(int totalSeconds, int ticksPerSecond, Pose start) {
        this.ticksPerSecond = ticksPerSecond;
        this.start = start;
        this.remainingSeconds = totalSeconds;
    }

    /**
     * Starts a countdown of whole seconds.
     *
     * @param seconds how many one-second steps the countdown has
     * @param ticksPerSecond how many server ticks make one step, so a paused or retimed server cannot rush it
     * @param start the player pose every later tick is compared against
     * @throws IllegalArgumentException when a value is outside its allowed range
     */
    public static LobbyCountdown start(int seconds, int ticksPerSecond, Pose start) {
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("seconds must be between 1 and " + MAX_SECONDS);
        }
        if (ticksPerSecond < 1) {
            throw new IllegalArgumentException("ticksPerSecond must be positive");
        }
        return new LobbyCountdown(seconds, ticksPerSecond, start);
    }

    /** Seconds still to go. The first number the player sees is the full countdown. */
    public int remainingSeconds() {
        return remainingSeconds;
    }

    /** The last state this countdown reached. */
    public State state() {
        return state;
    }

    /** True while the countdown can still complete. */
    public boolean running() {
        return !state.ended();
    }

    /**
     * Advances by one server tick.
     *
     * <p>The last second checks the world again before it teleports: the player must still be alive, still hold
     * the permission node, and still be in the dimension the countdown was started in. A cancelled condition ends
     * the countdown on that tick and is never revisited, so a player cannot wait out a condition change.
     *
     * <p>The full countdown is announced by the caller when it starts. Each later second is announced after a full
     * second of server time, and the countdown completes on the tick after the last shown second has lasted its
     * full second, so the player sees the whole countdown and then moves.
     *
     * @param pose the player pose now, or {@code null} when the player is gone
     * @param alive whether the player is currently alive
     * @param permitted whether the permission node still allows the request
     * @param sameDimension whether the player is still in the starting dimension
     * @return the state to report and how many seconds to show, or {@link Tick#NOTHING}
     */
    public Tick tick(Pose pose, boolean alive, boolean permitted, boolean sameDimension) {
        if (state.ended()) {
            return Tick.NOTHING;
        }
        if (pose == null) {
            return cancel(State.CANCELLED_DISCONNECTED);
        }
        if (!alive) {
            return cancel(State.CANCELLED_DIED);
        }
        if (!sameDimension) {
            return cancel(State.CANCELLED_DIMENSION);
        }
        if (!permitted) {
            return cancel(State.CANCELLED_DENIED);
        }
        if (!start.samePlace(pose)) {
            return cancel(State.CANCELLED_MOVED);
        }
        ticksInCurrentStep++;
        if (ticksInCurrentStep < ticksPerSecond) {
            return Tick.NOTHING;
        }
        ticksInCurrentStep = 0;
        remainingSeconds--;
        if (remainingSeconds > 0) {
            return new Tick(state, remainingSeconds);
        }
        state = State.COMPLETED;
        return new Tick(state, 0);
    }

    /**
     * Ends the countdown for a reason the player caused or should be told about.
     *
     * @return the state to report, or {@code null} when the countdown had already ended or the reason is not a
     *         cancellation
     */
    public Tick cancel(State reason) {
        if (state.ended() || !reason.ended() || reason.teleports()) {
            return null;
        }
        state = reason;
        return new Tick(state, 0);
    }
}
