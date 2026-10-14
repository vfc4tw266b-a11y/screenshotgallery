package com.example.screenshotgallery;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Zeichnet den Spielerkopf. Faellt auf einen Platzhalter zurueck, falls das nicht klappt. */
final class HeadRenderer {

    private static boolean resolved = false;
    private static Method skinGetter;
    private static Method drawMethod;
    private static int extraInts;

    private HeadRenderer() {}

    static boolean draw(GuiGraphicsExtractor g, int x, int y, int size) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return false;
            }
            if (!resolved) {
                resolve(mc);
            }
            if (skinGetter == null || drawMethod == null) {
                return false;
            }
            Object skin = skinGetter.invoke(mc.player);
            if (skin == null) {
                return false;
            }
            if (extraInts == 3) {
                drawMethod.invoke(null, g, skin, x, y, size);
            } else {
                drawMethod.invoke(null, g, skin, x, y, size, 0xFFFFFFFF);
            }
            return true;
        } catch (Throwable t) {
            drawMethod = null;
            return false;
        }
    }

    private static void resolve(Minecraft mc) {
        resolved = true;
        try {
            skinGetter = mc.player.getClass().getMethod("getSkin");
        } catch (Throwable ignored) {
            skinGetter = null;
        }
        String[] candidates = {
                "net.minecraft.client.gui.components.PlayerFaceExtractor",
                "net.minecraft.client.gui.PlayerFaceExtractor",
                "net.minecraft.client.gui.components.PlayerFaceRenderer"
        };
        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name);
                for (Method m : c.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!Modifier.isStatic(m.getModifiers()) || p.length < 5 || p.length > 6) {
                        continue;
                    }
                    if (p[0] != GuiGraphicsExtractor.class || p[2] != int.class || p[3] != int.class
                            || p[4] != int.class) {
                        continue;
                    }
                    if (p.length == 6 && p[5] != int.class) {
                        continue;
                    }
                    if (skinGetter != null && !p[1].isAssignableFrom(skinGetter.getReturnType())) {
                        continue;
                    }
                    int ints = p.length == 5 ? 3 : 4;
                    if (drawMethod == null || ints == 3) {
                        drawMethod = m;
                        extraInts = ints;
                    }
                }
                if (drawMethod != null) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
