package com.dave.nusacore.server.spawn;

import java.util.List;

import com.dave.nusacore.core.config.SpawnConfig;
import com.dave.nusacore.core.message.MessageText;
import com.dave.nusacore.core.message.Messages;
import com.dave.nusacore.core.spawn.LobbyCountdown.State;
import com.dave.nusacore.core.spawn.LobbyService;
import com.dave.nusacore.server.spawn.FakeLobbyPlayer.Player;
import com.dave.nusacore.server.spawn.LobbyCountdownService.StartResult;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real countdown service with token players: what a start does, what each second does, what ends a
 * countdown, and what happens when a player asks twice.
 *
 * <p>Everything asserted here is production behavior except the player itself. The
 * {@link LobbyCountdownService} enforces one countdown per player, calls the real {@link LobbyService} and safe
 * destination search through a {@link com.dave.nusacore.core.spawn.SpawnSurface}, and reports through the shared
 * message set. A live server is not started, so this proves the decision logic and not a live teleport.
 */
class LobbyCountdownServiceTest {
    private static final SpawnConfig CONFIG = new SpawnConfig(2, 2);
    private static final int TICKS_PER_SECOND = LobbyCountdownService.TICKS_PER_SECOND;
    private static final BlockPos SPAWN = new BlockPos(0, 70, 0);

    private static LobbyCountdownService<Player> service(FakeLobbyPlayer adapter) {
        return LobbyCountdownService.create(new LobbyService(CONFIG), adapter);
    }

    @Test
    void aStartAnnouncesTheCountdownAndDoesNotMoveThePlayer() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);

        StartResult result = service.start(adapter.player());

        assertTrue(result.started());
        assertEquals(List.of(MessageText.resolve(Messages.LOBBY_COUNTDOWN_STARTED, 5)), adapter.messages());
        assertEquals(List.of(5), adapter.stepSounds(), "the first second is announced with a sound");
        assertEquals(List.of(), adapter.moves(), "a start never teleports");
        assertEquals(0, adapter.completionSounds());
        assertTrue(service.isPending(adapter.player()));
        assertEquals(1, service.pendingCount());
        assertEquals(5, service.remainingSeconds(adapter.player()).orElseThrow());
    }

    @Test
    void aSecondRequestWhilePendingIsRefusedAndDoesNotRestartTheCountdown() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());
        ticks(service, 3 * TICKS_PER_SECOND - 1);
        assertEquals(3, service.remainingSeconds(adapter.player()).orElseThrow(),
                "the check itself must not change the countdown");

        StartResult second = service.start(adapter.player());

        assertEquals(StartResult.ALREADY_PENDING, second);
        assertFalse(adapter.messages().contains(MessageText.resolve(Messages.LOBBY_ALREADY_PENDING)),
                "the service returns the refusal and sends nothing, so the caller reports it once");
        assertEquals(1, service.pendingCount(), "one player never has two countdowns");
        assertEquals(3, service.remainingSeconds(adapter.player()).orElseThrow(),
                "the refused request did not reset the countdown");
        assertEquals(List.of(5, 4, 3), adapter.stepSounds(),
                "the refused request played no extra start sound");
    }

    @Test
    void theFirstAnnouncedSecondLastsAFullSecond() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());

        ticks(service, TICKS_PER_SECOND - 1);

        assertEquals(5, service.remainingSeconds(adapter.player()).orElseThrow(),
                "the first number stays up for its full second");
        assertEquals(List.of(5), adapter.stepSounds(), "no second announcement before one second passed");
    }

    @Test
    void aSecondRequestIsAcceptedOnlyAfterTheFirstCountdownEnded() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());
        ticks(service, completedTicks());
        assertFalse(service.isPending(adapter.player()));

        StartResult again = service.start(adapter.player());

        assertTrue(again.started(), "a player can ask again once the countdown is over");
        assertEquals(1, service.pendingCount());
    }

    @Test
    void twoPlayersHaveIndependentCountdowns() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);

        assertTrue(service.start(adapter.player()).started());
        assertTrue(service.start(adapter.other()).started());

        assertEquals(2, service.pendingCount());
    }

    @Test
    void aCountdownCompletesAndTeleportsToTheSafeDestination() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());

        ticks(service, completedTicks());

        assertFalse(service.isPending(adapter.player()));
        assertEquals(List.of(new FakeLobbyPlayer.Move(adapter.player(), SPAWN)), adapter.moves());
        assertEquals(1, adapter.completionSounds(), "the completion sound plays exactly once");
        assertEquals(List.of(5, 4, 3, 2, 1), adapter.stepSounds(), "one sound per announced second");
        assertEquals(MessageText.resolve(Messages.LOBBY_SUCCESS),
                adapter.messages().get(adapter.messages().size() - 1),
                "the success line is the last thing the player reads");
        assertEquals(1 + (Messages.LOBBY_COUNTDOWN_SECONDS - 1) + 1, adapter.messages().size(),
                "the start line, one line per later second, and the success line");
    }

    @Test
    void theCountdownAnnouncesEverySecondBeforeTheMove() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());

        ticks(service, TICKS_PER_SECOND);
        assertEquals(4, service.remainingSeconds(adapter.player()).orElseThrow());
        assertEquals(List.of(), adapter.moves(), "the player has not moved after one second");

        ticks(service, 3 * TICKS_PER_SECOND);
        assertEquals(1, service.remainingSeconds(adapter.player()).orElseThrow());
        assertEquals(List.of(), adapter.moves(), "the player has not moved while the last second is shown");
    }

    @Test
    void movingDuringTheCountdownCancelsWithoutTeleporting() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());

        ticks(service, TICKS_PER_SECOND);
        adapter.standAt(3.5, 64.0, 0.5);
        ticks(service, completedTicks());

        assertEquals(List.of(), adapter.moves(), "a moved player is never teleported");
        assertEquals(0, adapter.completionSounds());
        assertFalse(service.isPending(adapter.player()));
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_MOVED),
                adapter.messages().get(adapter.messages().size() - 1));
    }

    @Test
    void dyingDuringTheCountdownCancelsWithTheDeathMessage() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());

        adapter.kill();
        ticks(service, TICKS_PER_SECOND);

        assertEquals(List.of(), adapter.moves());
        assertFalse(service.isPending(adapter.player()));
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_DIED),
                adapter.messages().get(adapter.messages().size() - 1));
    }

    @Test
    void changingDimensionDuringTheCountdownCancels() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());

        adapter.inDimension("the_nether");
        ticks(service, TICKS_PER_SECOND);

        assertEquals(List.of(), adapter.moves());
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_DIMENSION),
                adapter.messages().get(adapter.messages().size() - 1));
    }

    @Test
    void losingTheNodeDuringTheCountdownCancels() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());

        adapter.deny();
        ticks(service, TICKS_PER_SECOND);

        assertEquals(List.of(), adapter.moves(), "the node is checked again before the teleport");
        assertEquals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_CANCELLED_DENIED),
                adapter.messages().get(adapter.messages().size() - 1));
    }

    @Test
    void aDisconnectedPlayerIsDroppedSilently() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());
        adapter.disconnect();
        int messagesBefore = adapter.messages().size();

        ticks(service, TICKS_PER_SECOND);

        assertEquals(0, service.pendingCount(), "the pending countdown is gone");
        assertEquals(messagesBefore, adapter.messages().size(), "a disconnected player is not messaged");
        assertEquals(List.of(), adapter.moves());
    }

    @Test
    void aDisconnectHookCancelsWithoutMessaging() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());
        int messagesBefore = adapter.messages().size();

        State state = service.cancel(adapter.player(), State.CANCELLED_DISCONNECTED, false);

        assertEquals(State.CANCELLED_DISCONNECTED, state);
        assertEquals(messagesBefore, adapter.messages().size());
        assertEquals(0, service.pendingCount());
    }

    @Test
    void cancellingAPlayerWithNoCountdownChangesNothing() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);

        assertNull(service.cancel(adapter.player(), State.CANCELLED_DISCONNECTED, true));
        assertEquals(List.of(), adapter.messages());
    }

    @Test
    void aServerStopCancelsEveryoneAndTellsEachPlayer() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());
        service.start(adapter.other());

        int cancelled = service.cancelAll(State.CANCELLED_SERVER_STOPPING, true);

        assertEquals(2, cancelled);
        assertEquals(0, service.pendingCount());
        assertEquals(2, adapter.messages().stream()
                .filter(text -> text.equals(MessageText.resolve(Messages.LOBBY_COUNTDOWN_SERVER_STOPPING)))
                .count());
        assertEquals(List.of(), adapter.moves());
    }

    @Test
    void aRefusedTeleportIsReportedAndNeverClaimsSuccess() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor().refuseTeleport();
        LobbyCountdownService<Player> service = service(adapter);
        service.start(adapter.player());

        ticks(service, completedTicks());

        assertEquals(0, adapter.completionSounds(), "no completion sound without a move");
        assertEquals(MessageText.resolve(Messages.LOBBY_TELEPORT_FAILED),
                adapter.messages().get(adapter.messages().size() - 1));
        assertFalse(adapter.messages().contains(MessageText.resolve(Messages.LOBBY_SUCCESS)));
    }

    @Test
    void aDeniedStartNeverReachesTheOverworld() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor().deny();
        LobbyCountdownService<Player> service = service(adapter);

        StartResult result = service.start(adapter.player());

        assertEquals(StartResult.DENIED, result);
        assertEquals(List.of(), adapter.messages(), "the service sends nothing for a refused start");
        assertEquals(0, adapter.overworldOpened(), "a denied request does no world work");
        assertEquals(0, service.pendingCount());
    }

    @Test
    void aMissingOverworldStartsNoCountdown() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.withoutOverworld();
        LobbyCountdownService<Player> service = service(adapter);

        assertEquals(StartResult.NO_OVERWORLD, service.start(adapter.player()));
        assertEquals(List.of(), adapter.messages(), "the service sends nothing for a refused start");
        assertEquals(0, service.pendingCount());
    }

    @Test
    void anUnsafeSpawnStartsNoCountdown() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onUnsafeFloor();
        LobbyCountdownService<Player> service = service(adapter);

        assertEquals(StartResult.NO_SAFE_DESTINATION, service.start(adapter.player()));
        assertEquals(List.of(), adapter.messages(), "the service sends nothing for a refused start");
        assertEquals(0, service.pendingCount());
    }

    @Test
    void anUnavailablePlayerStartsNoCountdown() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor().disconnect();
        LobbyCountdownService<Player> service = service(adapter);

        assertEquals(StartResult.PLAYER_GONE, service.start(adapter.player()));
        assertEquals(List.of(), adapter.messages(), "a gone player is not sent a start failure by the service");
        assertEquals(0, service.pendingCount());
    }

    @Test
    void remainingSecondsIsEmptyForAPlayerWithNoCountdown() {
        FakeLobbyPlayer adapter = FakeLobbyPlayer.onSafeFloor();
        LobbyCountdownService<Player> service = service(adapter);

        assertEquals(java.util.Optional.empty(), service.remainingSeconds(adapter.player()));
        assertEquals(List.of(), service.pendingKeys());
    }

    /** Ticks the service the given number of times. */
    private static void ticks(LobbyCountdownService<Player> service, int count) {
        for (int tick = 0; tick < count; tick++) {
            service.tick();
        }
    }

    /**
     * The ticks from a start to the teleport: the first number lasts one second, then every later number one
     * second each, and the move happens when the last shown second has elapsed.
     */
    private static int completedTicks() {
        return Messages.LOBBY_COUNTDOWN_SECONDS * TICKS_PER_SECOND;
    }}
