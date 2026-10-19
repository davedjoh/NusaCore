package com.dave.nusacore;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapMetadataTest {
    @Test
    void packagedMetadataDeclaresClientAndServerEntrypoints() throws Exception {
        try (var jar = new ZipFile(System.getProperty("nusacore.jar"))) {
            JsonObject metadata;
            try (var reader = new InputStreamReader(jar.getInputStream(jar.getEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
                metadata = JsonParser.parseReader(reader).getAsJsonObject();
            }
            assertEquals("nusacore", metadata.get("id").getAsString());
            assertEquals("NusaCore", metadata.get("name").getAsString());
            assertEquals(System.getProperty("nusacore.version"), metadata.get("version").getAsString());
            assertEquals("*", metadata.get("environment").getAsString());
            var entrypoints = metadata.getAsJsonObject("entrypoints");
            assertEquals(2, entrypoints.size());
            assertEquals("com.dave.nusacore.NusaCore", entrypoints.getAsJsonArray("server").get(0).getAsString());
            assertEquals("com.dave.nusacore.client.NusaCoreClient", entrypoints.getAsJsonArray("client").get(0).getAsString());
            assertTrue(DedicatedServerModInitializer.class.isAssignableFrom(NusaCore.class));
            assertNotNull(jar.getEntry("com/dave/nusacore/NusaCore.class"));
            assertNotNull(jar.getEntry("com/dave/nusacore/client/NusaCoreClient.class"));
            assertEquals("26.2", metadata.getAsJsonObject("depends").get("minecraft").getAsString());
            assertEquals(">=25", metadata.getAsJsonObject("depends").get("java").getAsString());
            assertFalse(metadata.has("mixins"));
            assertFalse(metadata.getAsJsonObject("depends").has("voxy"));
            assertFalse(metadata.getAsJsonObject("depends").has("voxyserver"));
            var nested = metadata.getAsJsonArray("jars");
            List<String> nestedFiles = new ArrayList<>();
            nested.forEach(element -> nestedFiles.add(element.getAsJsonObject().get("file").getAsString()));
            assertEquals(2, nestedFiles.size(), () -> "nested jars changed: " + nestedFiles);
            String permissions = nestedFiles.stream()
                    .filter(file -> file.endsWith("fabric-permissions-api-0.7.0.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the permissions api jar is no longer nested"));
            String sqlite = nestedFiles.stream()
                    .filter(file -> file.contains("sqlite-jdbc"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the sqlite driver jar is not nested"));
            for (String file : nestedFiles) {
                assertNotNull(jar.getEntry(file), () -> "declared nested jar is missing from the archive: " + file);
            }
            assertTrue(sqlite.endsWith("sqlite-jdbc-" + System.getProperty("nusacore.sqlite.version") + ".jar"));
            assertTrue(jar.stream().noneMatch(e -> e.getName().contains("voxy") || e.getName().contains("sodium")));
        }
    }

    @Test
    void initializerDoesNotNeedGameState() {
        assertDoesNotThrow(() -> new NusaCore().onInitializeServer());
    }

    @Test
    void permissionApiIsDeclaredAsADependencyBecauseCodeCallsIt() throws Exception {
        try (var jar = new ZipFile(System.getProperty("nusacore.jar"))) {
            JsonObject metadata;
            try (var reader = new InputStreamReader(jar.getInputStream(jar.getEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
                metadata = JsonParser.parseReader(reader).getAsJsonObject();
            }
            var depends = metadata.getAsJsonObject("depends");
            assertEquals("*", depends.get("fabric-permissions-api-v0").getAsString());
            assertTrue(depends.has("fabric-api"));
        }
    }

    @Test
    void clientInitializerStaysOutOfServerPackages() throws Exception {
        assertTrue(ClientModInitializer.class.isAssignableFrom(
                Class.forName("com.dave.nusacore.client.NusaCoreClient")));
        try (var jar = new ZipFile(System.getProperty("nusacore.jar"))) {
            List<String> clientEntries = new ArrayList<>();
            jar.stream()
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .filter(entry -> entry.getName().startsWith("com/dave/nusacore/client/"))
                    .forEach(entry -> clientEntries.add(entry.getName()));
            assertEquals(List.of("com/dave/nusacore/client/NusaCoreClient.class"), clientEntries);
            assertNotNull(jar.getEntry("com/dave/nusacore/server/spawn/LobbyCommand.class"));
            assertNotNull(jar.getEntry("com/dave/nusacore/core/spawn/LobbyService.class"));
        }
    }
}
