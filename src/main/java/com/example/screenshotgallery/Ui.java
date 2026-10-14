package com.example.screenshotgallery;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Kleine Zeichen- und Animations-Helfer. */
final class Ui {

    private Ui() {}

    // ---------- Mathe / Easing
    static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    static float easeOutCubic(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u * u;
    }

    static float easeInOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Naehert einen Wert weich (zeitabhaengig) an ein Ziel an. */
    static float approach(float value, float target, float dt, float speed) {
        float v = value + (target - value) * (1f - (float) Math.exp(-dt * speed));
        return Math.abs(v - target) < 0.002f ? target : v;
    }

    // ---------- Farben
    static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * clamp01(a));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    static int mix(int c1, int c2, float t) {
        t = clamp01(t);
        int a = Math.round(lerp((c1 >>> 24) & 0xFF, (c2 >>> 24) & 0xFF, t));
        int r = Math.round(lerp((c1 >> 16) & 0xFF, (c2 >> 16) & 0xFF, t));
        int g = Math.round(lerp((c1 >> 8) & 0xFF, (c2 >> 8) & 0xFF, t));
        int b = Math.round(lerp(c1 & 0xFF, c2 & 0xFF, t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---------- Formen
    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static boolean inside(double mx, double my, int[] r) {
        return inside(mx, my, r[0], r[1], r[2], r[3]);
    }

    /** Gefuelltes Rechteck mit runden Ecken. */
    static void rrect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    /** Malt die Ecken ausserhalb eines runden Rechtecks in der Hintergrundfarbe zu (rundet Bilder ab). */
    static void cornerMask(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            if (inset <= 0) {
                continue;
            }
            g.fill(x, y + i, x + inset, y + i + 1, color);
            g.fill(x + w - inset, y + i, x + w, y + i + 1, color);
            g.fill(x, y + h - i - 1, x + inset, y + h - i, color);
            g.fill(x + w - inset, y + h - i - 1, x + w, y + h - i, color);
        }
    }

    /** Pfeil nach links (14 x 14 Pixel). */
    static void arrowLeft(GuiGraphicsExtractor g, int x, int y, int color) {
        int tip = y + 7;
        g.fill(x + 2, tip - 1, x + 13, tip + 1, color);
        for (int i = 0; i < 6; i++) {
            g.fill(x + 1 + i, tip - 1 - i, x + 3 + i, tip - i, color);
            g.fill(x + 1 + i, tip + i, x + 3 + i, tip + 1 + i, color);
        }
    }
}
