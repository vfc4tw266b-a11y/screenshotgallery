package com.example.screenshotgallery;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GalleryScreen extends Screen {

    // ---------------------------------------------------------------- Farben
    private static final int CARD = 0xFF12151A;
    private static final int CARD_EDGE = 0xFF262B34;
    private static final int SURFACE = 0xFF1A1F26;
    private static final int SURFACE_HI = 0xFF252B35;
    private static final int ACCENT = 0xFF5B8CFF;
    private static final int ACCENT_HI = 0xFF7FA3FF;
    private static final int GREEN = 0xFF3DBE7A;
    private static final int RED = 0xFFD65A5A;
    private static final int TEXT = 0xFFE9EDF3;
    private static final int SUB = 0xFF8C95A5;

    // ---------------------------------------------------------------- Masse
    private static final int PAD = 14;
    private static final int HEADER_H = 44;
    private static final int GAP = 12;
    private static final int CAPTION_H = 14;
    private static final int RADIUS = 8;
    private static final int BTN_W = 160;
    private static final int BTN_H = 24;
    private static final int MAX_THUMBS = 48;

    // ---------------------------------------------------------------- Daten
    private final List<ScreenshotEntry> entries = new ArrayList<>();
    private volatile List<ScreenshotEntry> scanned = null;
    private boolean listLoaded = false;

    // ---------------------------------------------------------------- Animationszustand
    private long lastNs = System.nanoTime();
    private int frame = 0;
    private float visible = 0f;
    private boolean closing = false;

    private float scroll = 0f;
    private float scrollTarget = 0f;
    private float scrollbarAlpha = 0f;

    private int detailIndex = -1;
    private boolean detailOpen = false;
    private float detailT = 0f;

    private float backHover = 0f;
    private float copyHover = 0f;
    private int copyColor = ACCENT;
    private volatile int copyState = 0; // 0 bereit, 1 kopiert, 2 fertig, 3 Fehler
    private volatile long copyChangedNs = 0;

    // ---------------------------------------------------------------- Layout (pro Frame berechnet)
    private int cardX, cardY, cardW, cardH;
    private int contentX, contentY, contentW, contentH;
    private int thumbW, thumbH, gridX, cols, rowH;
    private int reqThumbPx;
    private float maxScroll;
    private int backX, backY, copyX, copyY;

    private String playerName = "Spieler";

    public GalleryScreen() {
        super(Component.literal("Screenshots"));
        Thread t = new Thread(() -> scanned = ScreenshotEntry.scan(), "screenshotgallery-scan");
        t.setDaemon(true);
        t.start();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            playerName = mc.player.getName().getString();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        closing = true; // eigentliches Schliessen nach der Ausblend-Animation (siehe tick)
    }

    @Override
    public void tick() {
        if (closing && visible <= 0f) {
            super.onClose();
        }
    }

    @Override
    public void removed() {
        Minecraft mc = Minecraft.getInstance();
        for (ScreenshotEntry e : entries) {
            if (e.thumb != null) {
                ImageLoader.release(mc.getTextureManager(), e.thumb);
                e.thumb = null;
            }
            if (e.full != null) {
                ImageLoader.release(mc.getTextureManager(), e.full);
                e.full = null;
            }
        }
        ImageLoader.reset();
        super.removed();
    }

    // ================================================================ Layout

    private void layout() {
        int off = Math.round((1f - Ui.easeOutCubic(visible)) * 18f);
        cardW = Math.min(this.width - 24, 720);
        cardH = Math.min(this.height - 24, 440);
        cardX = (this.width - cardW) / 2;
        cardY = (this.height - cardH) / 2 + off;
        contentX = cardX + PAD;
        contentY = cardY + HEADER_H;
        contentW = cardW - PAD * 2;
        contentH = cardH - HEADER_H - PAD;

        cols = contentW >= 380 ? 2 : 1;
        // So gross, dass zwei Reihen (bei 2 Spalten = 4 Bilder) gleichzeitig sichtbar sind
        int h = (contentH + GAP) / 2 - CAPTION_H - GAP;
        int w = h * 16 / 9;
        int maxW = (contentW - 10 - GAP * (cols - 1)) / cols;
        if (w > maxW) {
            w = maxW;
        }
        thumbW = Math.max(60, w);
        thumbH = thumbW * 9 / 16;
        rowH = thumbH + CAPTION_H + GAP;
        int gridW = cols * thumbW + (cols - 1) * GAP;
        gridX = contentX + (contentW - gridW) / 2;

        int rows = (entries.size() + cols - 1) / cols;
        float totalH = Math.max(0, rows * rowH - GAP);
        maxScroll = Math.max(0f, totalH - contentH);

        int gs = Math.max(1, (int) Math.round(this.minecraft.getWindow().getGuiScale()));
        reqThumbPx = Math.max(320, Math.min(1400, thumbW * gs));
    }

    private int[] thumbRect(int i) {
        int col = i % cols;
        int row = i / cols;
        int x = gridX + col * (thumbW + GAP);
        int y = Math.round(contentY + row * rowH - scroll);
        return new int[] {x, y, thumbW, thumbH};
    }

    private int indexAt(double mx, double my) {
        if (my < contentY || my >= contentY + contentH) {
            return -1;
        }
        double relX = mx - gridX;
        if (relX < 0) {
            return -1;
        }
        int col = (int) (relX / (thumbW + GAP));
        if (col >= cols || relX - col * (thumbW + GAP) >= thumbW) {
            return -1;
        }
        double relY = my - contentY + scroll;
        int row = (int) (relY / rowH);
        if (relY < 0 || relY - row * rowH >= thumbH) {
            return -1;
        }
        int idx = row * cols + col;
        return idx < entries.size() ? idx : -1;
    }

    // ================================================================ Update

    private void update(float dt, long now) {
        if (!listLoaded && scanned != null) {
            entries.addAll(scanned);
            listLoaded = true;
        }

        visible = Ui.clamp01(visible + (closing ? -dt / 0.20f : dt / 0.30f));

        if (detailOpen) {
            detailT = Math.min(1f, detailT + dt / 0.36f);
        } else {
            detailT = Math.max(0f, detailT - dt / 0.30f);
            if (detailT <= 0f && detailIndex >= 0) {
                finishDetail();
            }
        }

        scrollTarget = Math.max(0f, Math.min(maxScroll, scrollTarget));
        scroll = Ui.approach(scroll, scrollTarget, dt, 14f);
        scrollbarAlpha = Math.max(0f, scrollbarAlpha - dt * 1.4f);

        if ((copyState == 2 || copyState == 3) && now - copyChangedNs > 1_800_000_000L) {
            copyState = 0;
        }

        ImageLoader.pump(this.minecraft.getTextureManager(), 2);

        if (frame % 20 == 0) {
            evictOldThumbs(now);
        }
    }

    private void evictOldThumbs(long now) {
        int loaded = 0;
        for (ScreenshotEntry e : entries) {
            if (e.thumb != null) {
                loaded++;
            }
        }
        if (loaded <= MAX_THUMBS) {
            return;
        }
        for (ScreenshotEntry e : entries) {
            if (e.thumb != null && now - e.wantedNs > 3_000_000_000L) {
                ImageLoader.release(this.minecraft.getTextureManager(), e.thumb);
                e.thumb = null;
                e.thumbRequested = false;
            }
        }
    }

    private void finishDetail() {
        if (detailIndex >= 0 && detailIndex < entries.size()) {
            ScreenshotEntry en = entries.get(detailIndex);
            en.fullWanted = false;
            if (en.full != null) {
                ImageLoader.release(this.minecraft.getTextureManager(), en.full);
                en.full = null;
            }
            en.fullRequested = false;
        }
        detailIndex = -1;
    }

    private void openDetail(int idx) {
        detailIndex = idx;
        detailOpen = true;
        copyState = 0;
        ScreenshotEntry en = entries.get(idx);
        en.fullWanted = true;
        if (en.full == null && !en.fullRequested) {
            int gs = Math.max(1, (int) Math.round(this.minecraft.getWindow().getGuiScale()));
            int maxW = Math.min(2560, contentW * gs);
            int maxH = Math.min(1600, (contentH - BTN_H - 16) * gs);
            ImageLoader.request(en, true, maxW, maxH);
        }
    }

    private void closeDetail() {
        detailOpen = false;
    }

    private void startCopy() {
        if (copyState == 1 || detailIndex < 0 || detailIndex >= entries.size()) {
            return;
        }
        copyState = 1;
        copyChangedNs = System.nanoTime();
        ImageLoader.copyToClipboard(entries.get(detailIndex).path, ok -> {
            copyState = ok ? 2 : 3;
            copyChangedNs = System.nanoTime();
        });
    }

    // ================================================================ Rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastNs) / 1_000_000_000f);
        lastNs = now;
        frame++;

        layout();
        update(dt, now);

        float vis = Ui.easeOutCubic(visible);
        if (vis <= 0.001f) {
            return;
        }
        float e = Ui.easeInOutCubic(detailT);

        // Hintergrund abdunkeln + weicher Schatten
        g.fill(0, 0, this.width, this.height, Ui.alpha(0xFF000000, 0.55f * vis));
        for (int i = 1; i <= 7; i++) {
            Ui.rrect(g, cardX - i, cardY - i + 3, cardW + 2 * i, cardH + 2 * i, 14 + i,
                    Ui.alpha(0xFF000000, 0.06f * vis));
        }

        // Karte
        Ui.rrect(g, cardX - 1, cardY - 1, cardW + 2, cardH + 2, 14, CARD_EDGE);
        Ui.rrect(g, cardX, cardY, cardW, cardH, 13, CARD);

        drawHeader(g, mouseX, mouseY, dt, e);
        drawContent(g, mouseX, mouseY, now, dt, e);

        // Ein-/Ausblenden der gesamten Karte
        if (vis < 0.999f) {
            Ui.rrect(g, cardX - 1, cardY - 1, cardW + 2, cardH + 2, 14, Ui.alpha(CARD, 1f - vis));
        }
    }

    // ---------------------------------------------------------------- Text-Helfer

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        if (((color >>> 24) & 0xFF) < 12) {
            return; // sehr transparente Schrift wuerde sonst voll sichtbar gezeichnet
        }
        g.text(this.font, s, x, y, color, false);
    }

    private void text(GuiGraphicsExtractor g, Component c, int x, int y, int color) {
        if (((color >>> 24) & 0xFF) < 12) {
            return;
        }
        g.text(this.font, c, x, y, color, false);
    }

    // ---------------------------------------------------------------- Kopfzeile

    private void drawHeader(GuiGraphicsExtractor g, int mx, int my, float dt, float e) {
        int cy = cardY + HEADER_H / 2;

        // Zurueck-Pfeil
        backX = cardX + PAD;
        backY = cy - 12;
        boolean overBack = e > 0.5f && Ui.inside(mx, my, backX, backY, 24, 24);
        backHover = Ui.approach(backHover, overBack ? 1f : 0f, dt, 16f);
        if (e > 0.02f) {
            int bg = Ui.mix(SURFACE, SURFACE_HI, backHover);
            Ui.rrect(g, backX, backY, 24, 24, 8, Ui.alpha(bg, e));
            Ui.arrowLeft(g, backX + 5, backY + 5, Ui.alpha(Ui.mix(SUB, TEXT, backHover), e));
        }

        // Titel (Galerie) bzw. Datum (Einzelansicht), per Ueberblendung
        int tx = cardX + PAD;
        Component title = Component.literal("Screenshots").withStyle(ChatFormatting.BOLD);
        if (e < 0.98f) {
            text(g, title, tx, cy - 4, Ui.alpha(TEXT, 1f - e));
            text(g, String.valueOf(entries.size()), tx + this.font.width(title) + 8, cy - 4,
                    Ui.alpha(SUB, 1f - e));
        }
        if (e > 0.02f && detailIndex >= 0 && detailIndex < entries.size()) {
            ScreenshotEntry en = entries.get(detailIndex);
            Component date = Component.literal(en.dateText).withStyle(ChatFormatting.BOLD);
            text(g, date, tx + 34, cy - 4, Ui.alpha(TEXT, e));
            text(g, en.relText, tx + 34 + this.font.width(date) + 8, cy - 4, Ui.alpha(SUB, e));
        }

        // Spieler oben rechts (Kopf + Name)
        int headSize = 22;
        int headX = cardX + cardW - PAD - headSize;
        int headY = cardY + (HEADER_H - headSize) / 2;
        int nameW = this.font.width(playerName);
        int pillX = headX - 8 - nameW - 10;
        int pillW = headX + headSize + 3 - pillX;
        Ui.rrect(g, pillX, headY - 3, pillW, headSize + 6, (headSize + 6) / 2, SURFACE);
        text(g, playerName, headX - 8 - nameW, cy - 4, TEXT);
        if (!HeadRenderer.draw(g, headX, headY, headSize)) {
            Ui.rrect(g, headX, headY, headSize, headSize, 4, ACCENT);
            String ini = playerName.isEmpty() ? "?" : playerName.substring(0, 1).toUpperCase(Locale.ROOT);
            text(g, ini, headX + (headSize - this.font.width(ini)) / 2, headY + (headSize - 8) / 2, TEXT);
        }

        // Trennlinie
        g.fill(cardX + PAD, cardY + HEADER_H - 1, cardX + cardW - PAD, cardY + HEADER_H, CARD_EDGE);
    }

    // ---------------------------------------------------------------- Inhalt

    private void drawContent(GuiGraphicsExtractor g, int mx, int my, long now, float dt, float e) {
        if (!listLoaded) {
            centered(g, "Lade Screenshots ...", contentY + contentH / 2 - 4, SUB);
            return;
        }
        if (entries.isEmpty()) {
            centered(g, "Keine Screenshots gefunden", contentY + contentH / 2 - 10, TEXT);
            centered(g, "Mit F2 machst du im Spiel einen Screenshot.", contentY + contentH / 2 + 4, SUB);
            return;
        }
        drawGrid(g, mx, my, now, dt);
        if (detailIndex >= 0) {
            drawDetail(g, mx, my, now, dt, e);
        }
    }

    private void centered(GuiGraphicsExtractor g, String s, int y, int color) {
        text(g, s, contentX + (contentW - this.font.width(s)) / 2, y, color);
    }

    // ---------------------------------------------------------------- Raster

    private void drawGrid(GuiGraphicsExtractor g, int mx, int my, long now, float dt) {
        int n = entries.size();
        int firstRow = Math.max(0, (int) (scroll / rowH) - 1);
        int lastRow = (int) ((scroll + contentH) / rowH) + 1;
        int first = firstRow * cols;
        int last = Math.min(n - 1, (lastRow + 1) * cols - 1);
        boolean interactive = visible > 0.85f && !closing && !detailOpen && detailT <= 0f;

        g.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
        for (int i = first; i <= last; i++) {
            ScreenshotEntry en = entries.get(i);
            en.wantedNs = now;
            if (en.thumb == null && !en.thumbRequested && !en.thumbFailed) {
                ImageLoader.request(en, false, reqThumbPx, reqThumbPx);
            }
            int[] r = thumbRect(i);
            boolean onScreen = r[1] + thumbH + CAPTION_H > contentY && r[1] < contentY + contentH;
            if (!onScreen) {
                continue;
            }
            if (i == detailIndex && detailT > 0f) {
                continue; // dieses Bild wird gerade vergroessert dargestellt
            }
            boolean over = interactive && Ui.inside(mx, my, r) && my >= contentY && my < contentY + contentH;
            en.hover = Ui.approach(en.hover, over ? 1f : 0f, dt, 16f);
            drawCard(g, en, r, now);
        }

        // Scrollleiste
        if (maxScroll > 0.5f) {
            float totalH = maxScroll + contentH;
            int barH = Math.max(24, Math.round(contentH * contentH / totalH));
            int barY = contentY + Math.round((contentH - barH) * (scroll / maxScroll));
            int color = Ui.mix(SURFACE_HI, ACCENT, scrollbarAlpha);
            Ui.rrect(g, contentX + contentW - 4, barY, 3, barH, 1, color);
        }
        g.disableScissor();
    }

    private void drawCard(GuiGraphicsExtractor g, ScreenshotEntry en, int[] r, long now) {
        int pad = Math.round(en.hover * 4f);
        int x = r[0] - pad;
        int y = r[1] - pad;
        int w = r[2] + pad * 2;
        int h = r[3] + pad * 2;

        if (en.hover > 0.02f) {
            Ui.rrect(g, x - 2, y - 2, w + 4, h + 4, RADIUS + 2, Ui.alpha(ACCENT, en.hover));
        }
        Ui.rrect(g, x, y, w, h, RADIUS, SURFACE);

        if (en.thumb != null) {
            blitCover(g, en.thumb, x, y, w, h);
            float a = 1f - Ui.clamp01((now - en.thumbReadyNs) / 250_000_000f);
            if (a > 0.01f) {
                Ui.rrect(g, x, y, w, h, RADIUS, Ui.alpha(SURFACE, a));
            }
            Ui.cornerMask(g, x, y, w, h, RADIUS, CARD);
        } else if (en.thumbFailed) {
            Ui.rrect(g, x, y, w, h, RADIUS, SURFACE_HI);
            String s = "Nicht lesbar";
            text(g, s, x + (w - this.font.width(s)) / 2, y + h / 2 - 4, SUB);
        } else {
            drawSkeleton(g, x, y, w, h, now, 0);
        }

        // Beschriftung
        int cy = r[1] + r[3] + 3;
        text(g, en.dateText, r[0] + 2, cy, SUB);
        int rw = this.font.width(en.relText);
        text(g, en.relText, r[0] + r[2] - 2 - rw, cy, Ui.mix(SUB, ACCENT_HI, en.hover));
    }

    private void drawSkeleton(GuiGraphicsExtractor g, int x, int y, int w, int h, long now, int seed) {
        Ui.rrect(g, x, y, w, h, RADIUS, SURFACE);
        float t = ((now / 1_000_000_000f) * 0.9f + seed * 0.13f) % 1f;
        int bandW = Math.max(30, w / 4);
        int bx = x - bandW + Math.round(t * (w + bandW));
        int x0 = Math.max(x + 2, bx);
        int x1 = Math.min(x + w - 2, bx + bandW);
        if (x1 > x0) {
            g.fill(x0, y + 2, x1, y + h - 2, Ui.alpha(0xFFFFFFFF, 0.05f));
        }
        Ui.cornerMask(g, x, y, w, h, RADIUS, CARD);
    }

    // ---------------------------------------------------------------- Bilder zeichnen

    /** Zeichnet ein Bild so, dass es den Rahmen ausfuellt (mittig zugeschnitten). */
    private void blitCover(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h) {
        float[] c = coverFractions(t, (float) w / h);
        blitRegion(g, t, x, y, w, h, c[0], c[1], c[2], c[3]);
    }

    private static float[] coverFractions(Tex t, float frameAspect) {
        float imgAspect = (float) t.w() / t.h();
        if (imgAspect > frameAspect) {
            float keep = frameAspect / imgAspect;
            float off = (1f - keep) / 2f;
            return new float[] {off, 0f, off + keep, 1f};
        }
        float keep = imgAspect / frameAspect;
        float off = (1f - keep) / 2f;
        return new float[] {0f, off, 1f, off + keep};
    }

    private void blitRegion(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h,
                            float u0, float v0, float u1, float v1) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int u = Math.round(u0 * t.w());
        int v = Math.round(v0 * t.h());
        int rw = Math.max(1, Math.round((u1 - u0) * t.w()));
        int rh = Math.max(1, Math.round((v1 - v0) * t.h()));
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, (float) u, (float) v, w, h, rw, rh,
                t.w(), t.h());
    }

    // ---------------------------------------------------------------- Einzelansicht

    private void drawDetail(GuiGraphicsExtractor g, int mx, int my, long now, float dt, float e) {
        if (detailIndex < 0 || detailIndex >= entries.size()) {
            return;
        }
        ScreenshotEntry en = entries.get(detailIndex);

        // Raster ueberdecken (blendet weich ein)
        g.fill(contentX, contentY, contentX + contentW, contentY + contentH, Ui.alpha(CARD, 0.97f * e));

        Tex t = en.full != null ? en.full : en.thumb;
        int[] from = thumbRect(detailIndex);

        // Zielrechteck (Bild passend in den freien Bereich)
        int aw = contentW;
        int ah = contentH - BTN_H - 16;
        int tw;
        int th;
        if (t != null) {
            float s = Math.min((float) aw / t.w(), (float) ah / t.h());
            tw = Math.max(1, Math.round(t.w() * s));
            th = Math.max(1, Math.round(t.h() * s));
        } else {
            th = ah;
            tw = Math.min(aw, ah * 16 / 9);
        }
        int tx = contentX + (aw - tw) / 2;
        int ty = contentY + (ah - th) / 2;

        int x = Math.round(Ui.lerp(from[0], tx, e));
        int y = Math.round(Ui.lerp(from[1], ty, e));
        int w = Math.round(Ui.lerp(from[2], tw, e));
        int h = Math.round(Ui.lerp(from[3], th, e));
        int radius = Math.round(Ui.lerp(RADIUS, 10, e));

        g.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
        if (t != null) {
            Ui.rrect(g, x, y, w, h, radius, SURFACE);
            float[] cover = coverFractions(t, 16f / 9f);
            blitRegion(g, t, x, y, w, h,
                    Ui.lerp(cover[0], 0f, e), Ui.lerp(cover[1], 0f, e),
                    Ui.lerp(cover[2], 1f, e), Ui.lerp(cover[3], 1f, e));
            Ui.cornerMask(g, x, y, w, h, radius, CARD);
        } else if (en.thumbFailed || en.fullFailed) {
            Ui.rrect(g, x, y, w, h, radius, SURFACE_HI);
            String s = "Bild nicht lesbar";
            text(g, s, x + (w - this.font.width(s)) / 2, y + h / 2 - 4, SUB);
        } else {
            drawSkeleton(g, x, y, w, h, now, 3);
        }
        g.disableScissor();

        // Kopieren-Button
        copyX = contentX + (contentW - BTN_W) / 2;
        copyY = contentY + contentH - BTN_H;
        boolean over = e > 0.9f && Ui.inside(mx, my, copyX, copyY, BTN_W, BTN_H);
        copyHover = Ui.approach(copyHover, over ? 1f : 0f, dt, 16f);

        int target;
        String label;
        switch (copyState) {
            case 1 -> {
                target = SURFACE_HI;
                label = "Kopiere ...";
            }
            case 2 -> {
                target = GREEN;
                label = "Kopiert!";
            }
            case 3 -> {
                target = RED;
                label = "Kopieren nicht moeglich";
            }
            default -> {
                target = Ui.mix(ACCENT, ACCENT_HI, copyHover);
                label = "Bild kopieren";
            }
        }
        copyColor = Ui.mix(copyColor, target, 1f - (float) Math.exp(-dt * 12f));
        if (e > 0.02f) {
            Ui.rrect(g, copyX, copyY, BTN_W, BTN_H, 8, Ui.alpha(copyColor, e));
            text(g, label, copyX + (BTN_W - this.font.width(label)) / 2, copyY + (BTN_H - 8) / 2,
                    Ui.alpha(0xFFFFFFFF, e));
        }
    }

    // ================================================================ Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && !closing && visible > 0.8f && listLoaded) {
            double mx = event.x();
            double my = event.y();
            if (detailOpen) {
                if (detailT > 0.5f && Ui.inside(mx, my, backX, backY, 24, 24)) {
                    closeDetail();
                } else if (detailT > 0.9f && Ui.inside(mx, my, copyX, copyY, BTN_W, BTN_H)) {
                    startCopy();
                }
                return true;
            }
            if (detailT <= 0f) {
                int idx = indexAt(mx, my);
                if (idx >= 0) {
                    openDetail(idx);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!detailOpen && detailT <= 0f && maxScroll > 0f) {
            scrollTarget = Math.max(0f, Math.min(maxScroll, scrollTarget - (float) scrollY * (rowH * 0.45f)));
            scrollbarAlpha = 1f;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (detailOpen) {
                closeDetail();
            } else {
                onClose();
            }
            return true;
        }
        if (detailOpen) {
            if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_LEFT) {
                closeDetail();
                return true;
            }
        } else if (detailT <= 0f) {
            if (key == GLFW.GLFW_KEY_DOWN) {
                scrollTarget = Math.min(maxScroll, scrollTarget + rowH * 0.5f);
                scrollbarAlpha = 1f;
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP) {
                scrollTarget = Math.max(0f, scrollTarget - rowH * 0.5f);
                scrollbarAlpha = 1f;
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
