package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the mirror's server tick spends its time (HORIZON_JAVA_PROFILE=1): each phase's
 * nanoseconds summed, logged every 200 ticks as milliseconds a tick, the slowest first. The
 * game's side times its calls into Java the same way (game/java_profile.rs). Off, a phase costs
 * one check of a static.
 */
public final class HorizonProfile {
    private HorizonProfile() {}

    static final boolean ON = System.getenv("HORIZON_JAVA_PROFILE") != null;
    private static final Map<String, long[]> PHASES = new LinkedHashMap<>();
    private static int ticks;

    /** A phase's start (0 when off). */
    public static long start() {
        return ON ? System.nanoTime() : 0L;
    }

    /** A phase ended: its time since {@code started} added to {@code phase}; the next one's start. */
    public static long add(String phase, long started) {
        if (!ON) return 0L;
        long now = System.nanoTime();
        synchronized (PHASES) {
            PHASES.computeIfAbsent(phase, p -> new long[2])[0] += now - started;
            PHASES.get(phase)[1]++;
        }
        return now;
    }

    /** The end of a server tick: every 200, the report. */
    static void tick() {
        if (!ON || ++ticks < 200) return;
        StringBuilder line = new StringBuilder("Java profile (mirror tick):");
        synchronized (PHASES) {
            double total = 0;
            // Parts of a phase ("worldgen terrain: surface") are inside it: not added again.
            for (var e : PHASES.entrySet()) if (!e.getKey().contains(": ")) total += e.getValue()[0];
            line.append(String.format(" %.2f ms a tick;", total / 1e6 / ticks));
            PHASES.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(14)
                    .forEach(e -> line.append(e.getKey().startsWith("worldgen")
                            // Worldgen runs a chunk at a time: its time a chunk too.
                            ? String.format(" %s %.2f ms (%.1f ms each)", e.getKey(), e.getValue()[0] / 1e6 / ticks,
                                    e.getValue()[0] / 1e6 / Math.max(1, e.getValue()[1]))
                            : String.format(" %s %.2f ms", e.getKey(), e.getValue()[0] / 1e6 / ticks)));
            PHASES.clear();
        }
        ticks = 0;
        Natives.log(line.toString());
    }
}
