package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mods' client state kept per player. One headless client runs every player's client in turn
 * (HorizonClient.as), so what a mod keeps in a static field or a singleton is shared by them
 * all: the host typed "iron" in REI's search box and the guest's showed it. The fields listed
 * here are swapped as the client changes player: each player's values are put away when another
 * player's client runs, and come back with theirs. A player new to the client starts from the
 * field's empty value (null, false, 0), which these mods fill in lazily (REI makes a fresh
 * search box, overlay and item list).
 */
final class HorizonClientState {
    private HorizonClientState() {}

    /** A field of client state: static, or of the instance {@code owner} returns. */
    private record Slot(Field field, Method owner) {
        Object target() throws ReflectiveOperationException {
            return owner == null ? null : owner.invoke(null);
        }
    }

    /**
     * The fields, as {@code class#field} (static) or {@code class#getInstance()#field} (of the
     * instance a static method returns). A class that isn't there (its mod isn't) is skipped.
     */
    private static final String[] FIELDS = {
        // REI: its search box, overlay (with its list and favourites), the screens it remembers.
        "me.shedaniel.rei.impl.client.REIRuntimeImpl#isWithinRecipeViewingScreen",
        "me.shedaniel.rei.impl.client.REIRuntimeImpl#getInstance()#overlay",
        "me.shedaniel.rei.impl.client.REIRuntimeImpl#getInstance()#searchField",
        "me.shedaniel.rei.impl.client.REIRuntimeImpl#getInstance()#previousContainerScreen",
        "me.shedaniel.rei.impl.client.REIRuntimeImpl#getInstance()#previousScreen",
        "me.shedaniel.rei.impl.client.REIRuntimeImpl#getInstance()#lastDisplayScreen",
        "me.shedaniel.rei.impl.client.gui.ScreenOverlayImpl#entryListWidget",
        "me.shedaniel.rei.impl.client.gui.ScreenOverlayImpl#favoritesListWidget",
    };

    private static List<Slot> slots;
    /** Each player's put-away values, by slot. */
    private static final Map<UUID, Object[]> SAVED = new ConcurrentHashMap<>();
    /** The player whose values are in the fields now (null: nobody's yet). */
    private static UUID loaded;

    private static synchronized List<Slot> slots() {
        if (slots != null) return slots;
        List<Slot> made = new ArrayList<>();
        ClassLoader loader = HorizonClientState.class.getClassLoader();
        for (String spec : FIELDS) {
            String[] parts = spec.split("#");
            try {
                Class<?> owner = Class.forName(parts[0], false, loader);
                Method instance = null;
                if (parts.length == 3) {
                    instance = owner.getMethod(parts[1].replace("()", ""));
                }
                Field field = owner.getDeclaredField(parts[parts.length - 1]);
                if (Modifier.isFinal(field.getModifiers())) continue;
                field.setAccessible(true);
                made.add(new Slot(field, instance));
            } catch (ClassNotFoundException e) {
                // The mod isn't here.
            } catch (ReflectiveOperationException | RuntimeException e) {
                Natives.log("Horizon's client: per-player state " + spec + " can't be kept: " + e);
            }
        }
        slots = made;
        return made;
    }

    /** The fields hold {@code player}'s values (put away whose were there). */
    static synchronized void load(UUID player) {
        if (player == null || player.equals(loaded)) return;
        List<Slot> all = slots();
        if (all.isEmpty()) {
            loaded = player;
            return;
        }
        try {
            if (loaded != null) {
                Object[] values = new Object[all.size()];
                for (int i = 0; i < all.size(); i++) {
                    Slot slot = all.get(i);
                    values[i] = slot.field().get(slot.target());
                }
                SAVED.put(loaded, values);
            }
            Object[] values = SAVED.get(player);
            for (int i = 0; i < all.size(); i++) {
                Slot slot = all.get(i);
                Object value = values != null ? values[i] : empty(slot.field().getType());
                slot.field().set(slot.target(), value);
            }
            loaded = player;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's client: per-player state couldn't be swapped: " + e);
        }
    }

    /** A player left: their values go (and the fields', when theirs are in them). */
    static synchronized void forget(UUID player) {
        SAVED.remove(player);
        if (player.equals(loaded)) {
            for (Slot slot : slots()) {
                try {
                    slot.field().set(slot.target(), empty(slot.field().getType()));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Left as it is.
                }
            }
            loaded = null;
        }
    }

    private static Object empty(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}
