package com.dave.nusacore.core.spawn;

import com.dave.nusacore.core.config.SpawnConfig;
import com.dave.nusacore.core.message.MessageText;
import com.dave.nusacore.core.message.Messages;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LobbyServiceTest {
    private static final SpawnConfig CONFIG = new SpawnConfig(2, 2);

    @Test
    void missingOverworldFailsWithoutADestination() {
        LobbyService.Outcome outcome = new LobbyService(CONFIG).resolve(null);

        assertEquals(LobbyService.Outcome.Status.NO_OVERWORLD, outcome.status());
        assertFalse(outcome.ready());
        assertNull(outcome.destination());
        assertEquals(MessageText.resolve(Messages.LOBBY_NO_OVERWORLD), LobbyService.message(outcome).getString());
    }

    @Test
    void unsafeAreaFailsWithoutADestination() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(4, 70, -9));

        LobbyService.Outcome outcome = new LobbyService(CONFIG).resolve(surface);

        assertEquals(LobbyService.Outcome.Status.NO_SAFE_DESTINATION, outcome.status());
        assertFalse(outcome.ready());
        assertNull(outcome.destination());
        assertEquals(MessageText.resolve(Messages.LOBBY_NO_SAFE_DESTINATION), LobbyService.message(outcome).getString());
    }

    @Test
    void safeAreaProducesAReadyDestinationAtTheSpawn() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(4, 70, -9)).floorAt(69);

        LobbyService.Outcome outcome = new LobbyService(CONFIG).resolve(surface);

        assertEquals(LobbyService.Outcome.Status.READY, outcome.status());
        assertTrue(outcome.ready());
        assertEquals(new BlockPos(4, 70, -9), outcome.destination());
        assertEquals(MessageText.resolve(Messages.LOBBY_SUCCESS), LobbyService.message(outcome).getString());
    }

    @Test
    void spawnIsReadFromTheSurfaceOnEveryCall() {
        FakeSpawnSurface surface = FakeSpawnSurface.flat(new BlockPos(0, 64, 0)).floorAt(63);
        LobbyService service = new LobbyService(CONFIG);

        assertEquals(new BlockPos(0, 64, 0), service.resolve(surface).destination());

        surface.setSpawn(new BlockPos(6, 64, 6));
        assertEquals(new BlockPos(6, 64, 6), service.resolve(surface).destination());
    }

    @Test
    void feetPositionIsCenteredOnTheDestinationBlock() {
        var position = LobbyService.feetPosition(new BlockPos(10, 64, -3));

        assertEquals(10.5, position.x);
        assertEquals(64.0, position.y);
        assertEquals(-2.5, position.z);
    }
}
