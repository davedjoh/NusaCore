package com.dave.nusacore.core.spawn;

import com.dave.nusacore.core.message.MessageText;
import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.spawn.LobbyCountdown.Pose;
import com.dave.nusacore.core.spawn.LobbyCountdown.State;
import com.dave.nusacore.core.spawn.LobbyCountdown.Tick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real countdown state machine, {@link LobbyCountdown}, tick by tick.
 *
 * <p>Every transition is production code: the step timing, the last-second revalidation, and every cancellation
 * reason. A test drives the ticks itself, so the assertions are about the state machine rather than about the
 * server clock.
 */
class LobbyCountdownTest {
    private static final int TICKS_PER_SECOND = 20;
    private static final Pose START = new Pose(10.5, 64.0, -3.5);

    @Test
    void theSharedTickRateIsVanillaTwentyPerSecond() {
        assertEquals(20, LobbyCountdown.TICKS_PER_SECOND);
    }

    @Test
    void theFirstNumberIsTheFullCountdown() {
        LobbyCountdown countdown = LobbyCountdown.start(Messages.LOBBY_COUNTDOWN_SECONDS, TICKS_PER_SECOND, START);

        assertEquals(Messages.LOBBY_COUNTDOWN_SECONDS, countdown.remainingSeconds());
        assertEquals(State.RUNNING, countdown.state());
        assertTrue(countdown.running());
    }

    @Test
    void oneSecondPassesOnlyAfterAFullSecondOfTicks() {
        LobbyCountdown countdown = LobbyCountdown.start(3, TICKS_PER_SECOND, START);

        for (int tick = 1; tick < TICKS_PER_SECOND; tick++) {
            assertEquals(Tick.NOTHING, countdown.tick(START, true, true, true),
                    "no second is announced before a full second of ticks");
        }
        Tick second = countdown.tick(START, true, true, true);

        assertEquals(State.RUNNING, second.state());
        assertEquals(2, second.secondsToShow(), "the second announcement is one lower");
        assertEquals(2, countdown.remainingSeconds());
    }

    @Test
    void theCountdownCompletesOnlyAfterTheLastSecondHasElapsed() {
        LobbyCountdown countdown = LobbyCountdown.start(1, TICKS_PER_SECOND, START);

        for (int tick = 1; tick < TICKS_PER_SECOND; tick++) {
            Tick ticked = countdown.tick(START, true, true, true);
            assertEquals(Tick.NOTHING, ticked, "the move waits for the full last second");
            assertEquals(State.RUNNING, countdown.state());
        }

        Tick completion = countdown.tick(START, true, true, true);

        assertEquals(State.COMPLETED, completion.state());
        assertEquals(0, completion.secondsToShow(), "completion shows no number");
        assertTrue(completion.state().teleports());
        assertFalse(countdown.running());
    }

    @Test
    void aTwoSecondCountdownAnnouncesOneThenCompletesASecondLater() {
        LobbyCountdown countdown = LobbyCountdown.start(2, TICKS_PER_SECOND, START);

        assertEquals(1, advance(countdown, TICKS_PER_SECOND).secondsToShow(),
                "the last second is announced");
        assertEquals(State.RUNNING, countdown.state());

        assertEquals(State.COMPLETED, advance(countdown, TICKS_PER_SECOND).state(),
                "the move happens one full second after the last announcement");
    }

    @Test
    void aCompletedCountdownNeverTicksAgain() {
        LobbyCountdown countdown = LobbyCountdown.start(1, 1, START);
        assertEquals(State.COMPLETED, advance(countdown, 1).state());

        assertEquals(Tick.NOTHING, countdown.tick(START, true, true, true));
        assertEquals(Tick.NOTHING, countdown.tick(null, false, false, false),
                "a finished countdown cannot be cancelled afterwards");
        assertEquals(State.COMPLETED, countdown.state());
    }

    @Test
    void aCancelEndsTheCountdownAndIsReportedOnce() {
        LobbyCountdown countdown = LobbyCountdown.start(5, TICKS_PER_SECOND, START);

        Tick cancelled = countdown.cancel(State.CANCELLED_SERVER_STOPPING);

        assertEquals(State.CANCELLED_SERVER_STOPPING, cancelled.state());
        assertEquals(0, cancelled.secondsToShow());
        assertFalse(cancelled.state().teleports());
        assertNull(countdown.cancel(State.CANCELLED_MOVED), "a second cancel changes nothing");
        assertEquals(Tick.NOTHING, countdown.tick(START, true, true, true));
        assertEquals(State.CANCELLED_SERVER_STOPPING, countdown.state());
    }

    @Test
    void movingCancelsOnTheTickItHappens() {
        LobbyCountdown countdown = LobbyCountdown.start(5, TICKS_PER_SECOND, START);

        Tick moved = countdown.tick(new Pose(START.x() + 0.5, START.y(), START.z()), true, true, true);

        assertEquals(State.CANCELLED_MOVED, moved.state());
        assertFalse(moved.state().teleports());
    }

    @Test
    void lookingAroundIsNotMovement() {
        LobbyCountdown countdown = LobbyCountdown.start(2, TICKS_PER_SECOND, START);

        // Pose carries no rotation at all, so turning can never cancel a countdown.
        assertEquals(State.RUNNING, countdown.tick(START, true, true, true).state());
        assertEquals(1, advance(countdown, TICKS_PER_SECOND - 1).secondsToShow());
    }

    @Test
    void tinyFloatingPointNoiseIsNotMovement() {
        LobbyCountdown countdown = LobbyCountdown.start(2, TICKS_PER_SECOND, START);

        Tick tick = countdown.tick(new Pose(START.x() + 1.0E-6, START.y(), START.z() - 1.0E-6), true, true, true);

        assertEquals(State.RUNNING, tick.state());
    }

    @Test
    void dyingCancelsTheCountdown() {
        LobbyCountdown countdown = LobbyCountdown.start(5, TICKS_PER_SECOND, START);

        Tick died = countdown.tick(START, false, true, true);

        assertEquals(State.CANCELLED_DIED, died.state());
        assertFalse(died.state().teleports());
    }

    @Test
    void changingDimensionCancelsTheCountdown() {
        LobbyCountdown countdown = LobbyCountdown.start(5, TICKS_PER_SECOND, START);

        Tick changed = countdown.tick(START, true, true, false);

        assertEquals(State.CANCELLED_DIMENSION, changed.state());
    }

    @Test
    void losingTheNodeCancelsTheCountdown() {
        LobbyCountdown countdown = LobbyCountdown.start(5, TICKS_PER_SECOND, START);

        Tick denied = countdown.tick(START, true, false, true);

        assertEquals(State.CANCELLED_DENIED, denied.state());
    }

    @Test
    void aMissingPlayerCancelsTheCountdown() {
        LobbyCountdown countdown = LobbyCountdown.start(5, TICKS_PER_SECOND, START);

        Tick gone = countdown.tick(null, true, true, true);

        assertEquals(State.CANCELLED_DISCONNECTED, gone.state());
    }

    @Test
    void theLastSecondRevalidatesBeforeItCompletes() {
        LobbyCountdown countdown = LobbyCountdown.start(1, TICKS_PER_SECOND, START);

        for (int tick = 1; tick < TICKS_PER_SECOND; tick++) {
            assertEquals(State.RUNNING, countdown.tick(START, true, true, true).state());
        }

        Tick denied = countdown.tick(START, true, false, true);

        assertEquals(State.CANCELLED_DENIED, denied.state(),
                "a node removed during the last second stops the teleport");
        assertFalse(denied.state().teleports());
    }

    @Test
    void aCountdownCannotBeLongerThanTheSharedLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> LobbyCountdown.start(LobbyCountdown.MAX_SECONDS + 1, TICKS_PER_SECOND, START));
        assertThrows(IllegalArgumentException.class, () -> LobbyCountdown.start(0, TICKS_PER_SECOND, START));
        assertThrows(IllegalArgumentException.class, () -> LobbyCountdown.start(1, 0, START));
    }

    @Test
    void everyEndingHasPlayerFacingTextAndTheRunningStateHasNone() {
        assertNull(State.RUNNING.message(), "a running countdown is described by its own tick messages");

        for (State state : State.values()) {
            if (state == State.RUNNING) {
                continue;
            }
            assertNotNull(state.message(), "no message for " + state);
            assertNotEquals(state.name(), state.message().getString(), "missing text for " + state);
        }
        assertEquals(MessageText.resolve(Messages.LOBBY_SUCCESS), State.COMPLETED.message().getString());
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_MOVED),
                State.CANCELLED_MOVED.message().getString());
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_DIED),
                State.CANCELLED_DIED.message().getString());
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_DIMENSION),
                State.CANCELLED_DIMENSION.message().getString());
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_DENIED),
                State.CANCELLED_DENIED.message().getString());
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_DISCONNECTED),
                State.CANCELLED_DISCONNECTED.message().getString());
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_SERVER_STOPPING),
                State.CANCELLED_SERVER_STOPPING.message().getString());
    }

    @Test
    void aCountdownAnnouncesEachWholeSecondInOrder() {
        LobbyCountdown countdown = LobbyCountdown.start(Messages.LOBBY_COUNTDOWN_SECONDS, TICKS_PER_SECOND, START);

        StringBuilder announced = new StringBuilder().append(countdown.remainingSeconds());
        while (countdown.running()) {
            Tick tick = countdown.tick(START, true, true, true);
            if (tick.secondsToShow() > 0) {
                announced.append(',').append(tick.secondsToShow());
            }
        }

        assertEquals("5,4,3,2,1", announced.toString(), "each second is announced exactly once, in order");
        assertEquals(State.COMPLETED, countdown.state());
    }

    /** Ticks the countdown the given number of times, returning the last result. */
    private static Tick advance(LobbyCountdown countdown, int ticks) {
        Tick last = Tick.NOTHING;
        for (int tick = 0; tick < ticks; tick++) {
            last = countdown.tick(START, true, true, true);
        }
        return last;
    }
}
