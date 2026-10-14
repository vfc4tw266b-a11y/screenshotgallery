package com.example.screenshotgallery;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Laedt und verkleinert Bilder im Hintergrund, erzeugt Texturen im Render-Thread. */
final class ImageLoader {

    private record Result(ScreenshotEntry entry, boolean full, NativeImage image, int gen) {}

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "screenshotgallery-loader");
        t.setDaemon(true);
        return t;
    });
    private static final Queue<Result> DONE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static int counter = 0;

    private ImageLoader() {}

    /** Fragt an, dass ein Bild (verkleinert auf maxW x maxH) geladen wird. */
    static void request(ScreenshotEntry e, boolean full, int maxW, int maxH) {
        final int gen = GENERATION.get();
        if (full) {
            e.fullRequested = true;
        } else {
            e.thumbRequested = true;
        }
        POOL.execute(() -> {
            try {
                if (gen != GENERATION.get()) {
                    resetFlag(e, full);
                    return;
                }
                // Nicht mehr gebraucht (weggescrollt / Ansicht geschlossen)? Dann gar nicht erst laden.
                if (!full && System.nanoTime() - e.wantedNs > 2_000_000_000L) {
                    e.thumbRequested = false;
                    return;
                }
                if (full && !e.fullWanted) {
                    e.fullRequested = false;
                    return;
                }
                BufferedImage src = ImageIO.read(e.path.toFile());
                if (src == null) {
                    throw new IOException("Bildformat nicht lesbar");
                }
                BufferedImage scaled = scaleToFit(src, maxW, maxH);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
                ImageIO.write(scaled, "png", bos);
                NativeImage img = NativeImage.read(new ByteArrayInputStream(bos.toByteArray()));
                if (gen != GENERATION.get()) {
                    img.close();
                    resetFlag(e, full);
                    return;
                }
                DONE.add(new Result(e, full, img, gen));
            } catch (Throwable t) {
                if (full) {
                    e.fullFailed = true;
                } else {
                    e.thumbFailed = true;
                }
            }
        });
    }

    private static void resetFlag(ScreenshotEntry e, boolean full) {
        if (full) {
            e.fullRequested = false;
        } else {
            e.thumbRequested = false;
        }
    }

    /** Muss im Render-Thread laufen: wandelt fertig geladene Bilder in Texturen um. */
    static void pump(TextureManager tm, int max) {
        for (int n = 0; n < max; n++) {
            Result r = DONE.poll();
            if (r == null) {
                return;
            }
            ScreenshotEntry e = r.entry();
            if (r.gen() != GENERATION.get() || (r.full() && !e.fullWanted)) {
                r.image().close();
                resetFlag(e, r.full());
                continue;
            }
            int w = r.image().getWidth();
            int h = r.image().getHeight();
            Identifier id = Identifier.fromNamespaceAndPath(
                    "screenshotgallery", (r.full() ? "full/" : "thumb/") + (counter++));
            DynamicTexture tex = new DynamicTexture(() -> id.toString(), r.image());
            tm.register(id, tex);
            Tex t = new Tex(id, w, h);
            if (r.full()) {
                if (e.full != null) {
                    release(tm, e.full);
                }
                e.full = t;
                e.fullReadyNs = System.nanoTime();
            } else {
                if (e.thumb != null) {
                    release(tm, e.thumb);
                }
                e.thumb = t;
                e.thumbReadyNs = System.nanoTime();
            }
        }
    }

    static void release(TextureManager tm, Tex t) {
        tm.release(t.id());
    }

    /** Beim Schliessen der Galerie: alles Ausstehende verwerfen. */
    static void reset() {
        GENERATION.incrementAndGet();
        Result r;
        while ((r = DONE.poll()) != null) {
            r.image().close();
        }
    }

    // ------------------------------------------------------------ Bild skalieren

    private static BufferedImage scaleToFit(BufferedImage src, int maxW, int maxH) {
        int w = src.getWidth();
        int h = src.getHeight();
        double s = Math.min(1.0, Math.min((double) maxW / w, (double) maxH / h));
        int tw = Math.max(1, (int) Math.round(w * s));
        int th = Math.max(1, (int) Math.round(h * s));
        BufferedImage cur = src;
        if (tw == w && th == h) {
            return toRgb(src);
        }
        int cw = w;
        int ch = h;
        // In Halbierungs-Schritten verkleinern -> deutlich schaerfer als in einem Rutsch
        while (cw / 2 >= tw && ch / 2 >= th) {
            cw /= 2;
            ch /= 2;
            cur = resize(cur, cw, ch);
        }
        return resize(cur, tw, th);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        return resize(src, src.getWidth(), src.getHeight());
    }

    // ------------------------------------------------------------ Zwischenablage

    /** Kopiert das Originalbild in die Zwischenablage (eigener Thread). */
    static void copyToClipboard(Path path, Consumer<Boolean> done) {
        Thread t = new Thread(() -> {
            boolean ok = false;
            try {
                BufferedImage src = ImageIO.read(path.toFile());
                if (src != null) {
                    BufferedImage rgb = toRgb(src);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(rgb), null);
                    ok = true;
                }
            } catch (Throwable ignored) {
            }
            done.accept(ok);
        }, "screenshotgallery-clipboard");
        t.setDaemon(true);
        t.start();
    }

    private static final class ImageSelection implements Transferable {
        private final java.awt.Image image;

        ImageSelection(java.awt.Image image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }
}
