package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

/**
 * Mods' chunk tickets (chunk loaders: ServerChunkCache.addTicketWithRadius, ServerLevel's
 * setChunkForced). Each mirrored level keeps vanilla's own TicketStorage in its saved data, as
 * vanilla's levels do ("chunks"), so tickets that persist come back with the world. Each tick
 * the timed ones age (DistanceManager's purge), and the chunks they keep go to Horizon when
 * they change, as forced chunks: a ticket of level L keeps every chunk within 31 - L of it at
 * level 31 (entity ticking), which gives the same levels around it as vanilla's single ticket.
 * The simulation level is the one used (what ticks); a ticket at 32 keeps its own chunk.
 */
public final class HorizonTickets {
    private HorizonTickets() {}

    private static final Map<ServerLevel, String> SENT = new IdentityHashMap<>();
    private static java.lang.reflect.Field tickets;

    /**
     * The level's ticket storage (in its saved data, made on first use). Saved tickets come back
     * deactivated; the server turns them on as it prepares its levels
     * (MinecraftServer.prepareLevels' activateAllDeactivatedTickets): here, on first use.
     */
    public static TicketStorage storage(ServerLevel level) {
        TicketStorage storage = HorizonSavedData.level(level).computeIfAbsent(TicketStorage.TYPE);
        if (ACTIVATED.add(storage)) storage.activateAllDeactivatedTickets();
        return storage;
    }

    private static final java.util.Set<TicketStorage> ACTIVATED =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Each server tick: timed tickets age, and changed chunk sets go to Horizon. */
    static void tick(List<ServerLevel> levels) {
        for (ServerLevel level : levels) {
            try {
                TicketStorage storage = storage(level);
                Long2ObjectOpenHashMap<List<Ticket>> all = map(storage);
                if (all == null) continue;
                age(storage, all);
                String chunks = chunks(storage, all);
                if (!chunks.equals(SENT.getOrDefault(level, ""))) {
                    SENT.put(level, chunks);
                    Natives.chunkTickets(level.dimension().identifier().toString(), chunks);
                    Natives.log("Java mods: chunk tickets keep " + (chunks.isEmpty() ? 0 : chunks.split(";").length)
                            + " chunk(s) of " + level.dimension().identifier());
                }
            } catch (Throwable t) {
                Natives.log("Java mods: chunk tickets of " + level.dimension().identifier() + " failed: " + t);
            }
        }
    }

    /** A new world: what was sent belongs to the last one. */
    static void reset() {
        SENT.clear();
        ACTIVATED.clear();
    }

    @SuppressWarnings("unchecked")
    private static Long2ObjectOpenHashMap<List<Ticket>> map(TicketStorage storage) throws ReflectiveOperationException {
        if (tickets == null) {
            java.lang.reflect.Field field = TicketStorage.class.getDeclaredField("tickets");
            field.setAccessible(true);
            tickets = field;
        }
        return (Long2ObjectOpenHashMap<List<Ticket>>) tickets.get(storage);
    }

    /** TicketStorage.purgeStaleTickets' timeouts (a mirror has no chunk map to ask about unloading). */
    private static void age(TicketStorage storage, Long2ObjectOpenHashMap<List<Ticket>> all) {
        List<long[]> expired = new ArrayList<>();
        List<Ticket> which = new ArrayList<>();
        for (var entry : all.long2ObjectEntrySet()) {
            for (Ticket ticket : entry.getValue()) {
                if (!ticket.getType().hasTimeout()) continue;
                ticket.decreaseTicksLeft();
                if (ticket.isTimedOut()) {
                    expired.add(new long[] {entry.getLongKey()});
                    which.add(ticket);
                }
            }
        }
        for (int i = 0; i < expired.size(); i++) storage.removeTicket(expired.get(i)[0], which.get(i));
    }

    /** The chunks kept, as "x,z;x,z", sorted (so an unchanged set compares equal). */
    private static String chunks(TicketStorage storage, Long2ObjectOpenHashMap<List<Ticket>> all) {
        TreeSet<Long> kept = new TreeSet<>();
        for (long key : all.keySet()) {
            int level = storage.getTicketLevelAt(key, true);
            if (level > 32) continue;
            int reach = Math.max(0, 31 - level);
            ChunkPos at = ChunkPos.unpack(key);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) kept.add(ChunkPos.pack(at.x() + dx, at.z() + dz));
            }
        }
        StringBuilder out = new StringBuilder();
        for (long key : kept) {
            ChunkPos pos = ChunkPos.unpack(key);
            if (!out.isEmpty()) out.append(';');
            out.append(pos.x()).append(',').append(pos.z());
        }
        return out.toString();
    }
}
