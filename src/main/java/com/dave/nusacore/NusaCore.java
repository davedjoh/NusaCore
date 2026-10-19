package com.dave.nusacore;

import com.dave.nusacore.server.NusaCoreServer;

import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.LoggerFactory;

public final class NusaCore implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        NusaCoreServer.registerCommands();
        LoggerFactory.getLogger("nusacore").info(
                "NusaCore initialized: /lobby enabled, claim storage opens with the server");
    }
}
