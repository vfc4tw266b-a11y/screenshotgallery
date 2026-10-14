package com.example.screenshotgallery;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ScreenshotGalleryClient implements ClientModInitializer {

    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        // Noetig, damit das Kopieren in die Zwischenablage (AWT) funktionieren kann
        System.setProperty("java.awt.headless", "false");

        // Erscheint in Optionen > Steuerung > Tastenbelegung unter "Screenshot Gallery"
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("screenshotgallery", "main"));

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.screenshotgallery.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player != null) {
                    client.setScreenAndShow(new GalleryScreen());
                }
            }
        });
    }
}
