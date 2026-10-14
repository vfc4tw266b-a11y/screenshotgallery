package com.example.screenshotgallery;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Ein Screenshot auf der Festplatte plus Lade-Zustand der Vorschau. */
final class ScreenshotEntry {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy  HH:mm");

    final Path path;
    final long modified;
    final String title;
    final String dateText;
    final String relText;

    // Texturen (werden nur im Render-Thread gesetzt)
    Tex thumb;
    Tex full;
    long thumbReadyNs;
    long fullReadyNs;
    float hover;

    // Lade-Zustand (wird auch von Hintergrund-Threads gelesen)
    volatile boolean thumbRequested;
    volatile boolean fullRequested;
    volatile boolean thumbFailed;
    volatile boolean fullFailed;
    volatile boolean fullWanted;
    volatile long wantedNs;

    ScreenshotEntry(Path path, long modified) {
        this.path = path;
        this.modified = modified;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        this.title = dot > 0 ? name.substring(0, dot) : name;

        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(modified), ZoneId.systemDefault());
        this.dateText = dt.format(FORMAT);
        long days = ChronoUnit.DAYS.between(dt.toLocalDate(), LocalDate.now());
        this.relText = days <= 0 ? "Heute" : days == 1 ? "Gestern" : "vor " + days + " Tagen";
    }

    /** Liest den Screenshot-Ordner ein (neueste zuerst). Laeuft im Hintergrund. */
    static List<ScreenshotEntry> scan() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
        List<ScreenshotEntry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) {
                    long m = 0;
                    try {
                        m = Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException ignored) {
                    }
                    out.add(new ScreenshotEntry(p, m));
                }
            });
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparingLong((ScreenshotEntry e) -> e.modified).reversed());
        return out;
    }
}
