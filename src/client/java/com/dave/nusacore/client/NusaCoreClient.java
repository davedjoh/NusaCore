package com.dave.nusacore.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.LoggerFactory;

/** Client lifecycle boundary only. GUI, HUD, keybinds, and packets are future slices. */
public final class NusaCoreClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LoggerFactory.getLogger("nusacore").info("NusaCore client bootstrap initialized");
    }
}
