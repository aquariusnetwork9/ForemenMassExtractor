package com.shallowplague.foreman.hud;

import java.util.List;

/**
 * Read-only-from-the-HUD snapshot of the last pre-mining chunk scan. {@code MassExtractor} runs the
 * scan once when it's activated (on the client/main thread) and writes the ranked results here; the
 * {@link ForemanScanHud} element just reads them. All fields are {@code volatile} since the writer
 * (module tick) and reader (HUD render) are different threads.
 */
public final class ScanData {
    /** One ranked row: a display name (e.g. "Deepslate", "Iron Ore") and how many were found. */
    public record Count(String name, int count) {}

    public static volatile List<Count> topBlocks = List.of(); // up to 3 most abundant non-ore blocks
    public static volatile List<Count> topOres   = List.of(); // up to 5 most abundant ores
    public static volatile String summary = "";               // e.g. "9 chunks · y-59..-50"
    public static volatile boolean valid = false;             // a scan has run this/last activation

    private ScanData() {}

    /** Forget the last scan (called when the module re-activates, before the new scan runs). */
    public static void clear() {
        topBlocks = List.of();
        topOres = List.of();
        summary = "";
        valid = false;
    }

    /** Counts run into the 100k+ for deepslate; cap the displayed number to 6 digits as requested. */
    public static String fmt(int n) {
        return n > 999999 ? "999999+" : Integer.toString(n);
    }
}
