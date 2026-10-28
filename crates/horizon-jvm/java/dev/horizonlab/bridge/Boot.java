package dev.horizonlab.bridge;

/**
 * The first Java code Horizon runs. Phase 1 of the bridge: proves both directions work,
 * Rust calling Java ({@link #hello}) and Java calling Rust ({@link Natives#log}).
 */
public final class Boot {
    private Boot() {}

    /**
     * Called once, right after the JVM starts.
     *
     * @param jars the jars found in mods-java/, as absolute paths
     * @return the Java version, for Horizon's own log line
     */
    public static String hello(String[] jars) {
        String version = Runtime.version().toString();
        Natives.log("Hello from Java " + version + " inside Horizon");
        Natives.log(jars.length + (jars.length == 1 ? " jar" : " jars") + " in mods-java/");
        for (String jar : jars) {
            Natives.log("  " + jar);
        }
        return version;
    }
}
