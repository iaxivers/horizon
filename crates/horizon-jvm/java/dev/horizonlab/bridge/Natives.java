package dev.horizonlab.bridge;

/** Functions Horizon implements in Rust and registers when the JVM starts (horizon-jvm). */
public final class Natives {
    private Natives() {}

    /** A line in Horizon's log, as {@code [java] text}. */
    public static native void log(String text);

    /** A system message to one player, by {@code UUID.toString()}. */
    public static native void tell(String uuid, String text);

    /** Moves a player (by UUID) to a dimension ("minecraft:the_nether"), position and rotation. */
    public static native void teleport(String uuid, String dimension, double x, double y, double z, float yaw, float pitch);

    /**
     * A player's abilities (vanilla's Abilities) as computed in game mode {@code gameMode}:
     * {@code flags} invulnerable 1, flying 2, may fly 4, instabuild 8.
     */
    public static native void abilities(String uuid, int gameMode, int flags, float flyingSpeed, float walkingSpeed);

    /**
     * The name a player (by UUID) shows in the tab list, as a JSON text component; null for
     * the player's own name.
     */
    public static native void tabName(String uuid, String json);

    /**
     * A mod opened a menu for a player (ServerPlayer.openMenu): its MenuType's id
     * ("minecraft:crafting") and title as a JSON text component. Horizon opens its own.
     */
    public static native void openMenu(String uuid, String menu, String title);

    /** A system message to every player on the server, and its log. */
    public static native void broadcast(String text);

    /**
     * An instance of {@code type} made without running a constructor (JNI AllocObject): the
     * game objects Horizon's state is mirrored into. Every field starts at its default.
     */
    public static native Object allocate(Class<?> type);
}
