package me.cortex.voxy.client.config;

import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Registers the NeoForge "Mods → Config" button for Voxy. Clicking it opens the
 * Sodium Video Settings screen — VoxyConfigScreenPages is injected into
 * SodiumOptionsGUI via MixinSodiumOptionsGUI.
 */
public final class VoxyConfigRegistration {
    private VoxyConfigRegistration() {}

    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mc, parent) -> VideoSettingsScreen.createScreen(parent));
    }
}
