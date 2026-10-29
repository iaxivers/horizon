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

    /**
     * A mod's menu a player has open, shown by Horizon and run by Java: on opening, the menu
     * type the player's game draws and its title (else null); the shown slots' items as SNBT
     * (null empty) and the carried one. All null: the menu closed.
     */
    public static native void menuContent(String uuid, String menu, String title, String[] items, String carried);

    /**
     * Horizon's block at x y z of a dimension ("minecraft:oak_log[axis=y]"), or null where no
     * chunk is loaded or outside a call that lent the world.
     */
    public static native String blockState(String dimension, int x, int y, int z);

    /** A mod set a block of a mirrored level: Horizon makes it, as /setblock does. */
    public static native void setBlock(String dimension, int x, int y, int z, String state);

    /** A mod dropped an item (a block's drops): Horizon spawns the item entity. */
    public static native void dropItem(String dimension, double x, double y, double z,
                                       double vx, double vy, double vz, String item, int count);

    /**
     * A mod's level event (LevelEvent: 2001 a block's break particles and sound): {@code state}
     * is the block state the data names, for the events whose data is one, else null.
     */
    public static native void levelEvent(String dimension, int type, int x, int y, int z, int data, String state);

    /**
     * A mod changed a player's inventory slot (vanilla's Inventory order): the stack as SNBT
     * (ItemStack.CODEC), or null for empty.
     */
    public static native void setSlot(String uuid, int slot, String item);

    /** A system message to every player on the server, and its log. */
    public static native void broadcast(String text);

    /**
     * An instance of {@code type} made without running a constructor (JNI AllocObject): the
     * game objects Horizon's state is mirrored into. Every field starts at its default.
     */
    public static native Object allocate(Class<?> type);
}
