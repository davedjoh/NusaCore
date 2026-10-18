package com.dave.nusacore.server.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.dave.nusacore.core.spawn.FakeSpawnSurface;
import com.dave.nusacore.core.spawn.LobbyCountdown.Pose;
import com.dave.nusacore.core.spawn.SpawnSurface;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * A token player and the recorded game calls one countdown makes, for tests that need the real
 * {@link LobbyCountdownService} without a running server.
 *
 * <p>It fills the role {@link LobbyCountdownRuntime.ServerPlayerAdapter} fills in production, including a real
 * {@link LobbyTarget} that decides where the player goes. A test flips one field to simulate a second request, a
 * move, a death, a dimension change, a permission change, a disconnect, or a refused teleport.
 */
public final class FakeLobbyPlayer implements LobbyCountdownService.Adapter<FakeLobbyPlayer.Player> {

    /** The token player. Two tokens with the same id are the same player, exactly as on a server. */
    public record Player(int id) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Player that && that.id == id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return "player" + id;
        }
    }

    /** One recorded player move. */
    public record Move(Player player, BlockPos destination) {
    }

    private boolean permitted = true;
    private boolean alive = true;
    private boolean available = true;
    private boolean missingOverworld;
    private boolean unsafeSurface;
    private Object dimension = "overworld";
    private double x = 0.5;
    private double y = 64.0;
    private double z = 0.5;

    private final List<String> messages = new ArrayList<>();
    private final List<Integer> stepSounds = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private int completionSounds;
    private int overworldOpened;
    private int teleportRefusals;

    private FakeLobbyPlayer() {
    }

    /** A player standing in a flat world with a floor, so the real safe-destination search finds the spawn. */
    public static FakeLobbyPlayer onSafeFloor() {
        return new FakeLobbyPlayer();
    }

    /** A player whose server has no Overworld. */
    public static FakeLobbyPlayer withoutOverworld() {
        return new FakeLobbyPlayer().noOverworld();
    }

    /** A player in a flat world with no floor, so no safe destination exists. */
    public static FakeLobbyPlayer onUnsafeFloor() {
        return new FakeLobbyPlayer().unsafeSurface();
    }

    private FakeLobbyPlayer noOverworld() {
        this.missingOverworld = true;
        return this;
    }

    private FakeLobbyPlayer unsafeSurface() {
        this.unsafeSurface = true;
        return this;
    }

    public Player player() {
        return new Player(1);
    }

    public Player other() {
        return new Player(2);
    }

    public FakeLobbyPlayer deny() {
        permitted = false;
        return this;
    }

    public FakeLobbyPlayer kill() {
        alive = false;
        return this;
    }

    public FakeLobbyPlayer disconnect() {
        available = false;
        return this;
    }

    public FakeLobbyPlayer inDimension(Object key) {
        dimension = key;
        return this;
    }

    public FakeLobbyPlayer standAt(double newX, double newY, double newZ) {
        x = newX;
        y = newY;
        z = newZ;
        return this;
    }

    /** Makes the next move fail, as a server refusing a player in an unexpected state would. */
    public FakeLobbyPlayer refuseTeleport() {
        teleportRefusals++;
        return this;
    }

    public List<String> messages() {
        return messages;
    }

    public List<Integer> stepSounds() {
        return stepSounds;
    }

    public int completionSounds() {
        return completionSounds;
    }

    public List<Move> moves() {
        return moves;
    }

    public int overworldOpened() {
        return overworldOpened;
    }

    @Override
    public UUID id(Player player) {
        return new UUID(0L, player.id());
    }

    @Override
    public boolean permitted(Player player) {
        return permitted;
    }

    @Override
    public Object dimension(Player player) {
        return dimension;
    }

    @Override
    public Pose pose(Player player) {
        return new Pose(x, y, z);
    }

    @Override
    public boolean alive(Player player) {
        return alive;
    }

    @Override
    public boolean available(Player player) {
        return available;
    }

    @Override
    public LobbyTarget<Player> overworld(Player player) {
        if (missingOverworld) {
            return null;
        }
        overworldOpened++;
        return new FakeTarget(unsafeSurface, moves);
    }

    @Override
    public void send(Player player, Component message) {
        messages.add(message.getString());
    }

    @Override
    public void playStepSound(Player player, int remainingSeconds) {
        stepSounds.add(remainingSeconds);
    }

    @Override
    public boolean complete(Player player, LobbyTarget<Player> target, BlockPos destination) {
        if (teleportRefusals > 0) {
            teleportRefusals--;
            return false;
        }
        if (!target.teleport(player, destination)) {
            return false;
        }
        completionSounds++;
        return true;
    }

    /** A target over a fake surface, recording the moves it is asked to make. */
    private static final class FakeTarget implements LobbyTarget<Player> {
        private final SpawnSurface surface;
        private final List<Move> moves;

        private FakeTarget(boolean unsafe, List<Move> moves) {
            this.surface = unsafe
                    ? new FakeSpawnSurface(new BlockPos(0, 70, 0), -64, 319, true)
                    : FakeSpawnSurface.flat(new BlockPos(0, 70, 0)).floorAt(69);
            this.moves = moves;
        }

        @Override
        public SpawnSurface surface() {
            return surface;
        }

        @Override
        public boolean teleport(Player player, BlockPos destination) {
            moves.add(new Move(player, destination));
            return true;
        }
    }
}
