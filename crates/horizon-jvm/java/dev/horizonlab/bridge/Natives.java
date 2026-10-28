package dev.horizonlab.bridge;

/** Functions Horizon implements in Rust and registers when the JVM starts (horizon-jvm). */
public final class Natives {
    private Natives() {}

    /** A line in Horizon's log, as {@code [java] text}. */
    public static native void log(String text);
}
