package dev.horizonlab.platform;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.services.ServicesKeySet;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.Services;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.server.players.UserNameToIdResolver;

/**
 * The mirrored server's services (MinecraftServer.services): Horizon signs players in and keeps
 * the user cache, so names resolve to the players Horizon told the mirror of (then to offline
 * ids), and nothing reaches Mojang's servers from here: the session service and profile
 * lookups answer "nothing", as a server offline would.
 */
final class HorizonServices {
    private HorizonServices() {}

    /** Players by lower-case name, and by id, as Horizon gave them. */
    private static final Map<String, NameAndId> BY_NAME = new ConcurrentHashMap<>();
    private static final Map<UUID, NameAndId> BY_ID = new ConcurrentHashMap<>();
    private static Services services;

    static void known(UUID id, String name) {
        NameAndId entry = new NameAndId(id, name);
        BY_NAME.put(name.toLowerCase(java.util.Locale.ROOT), entry);
        BY_ID.put(id, entry);
    }

    static synchronized Services get() {
        if (services == null) {
            services = new Services(inert(com.mojang.authlib.minecraft.SessionService.class), ServicesKeySet.EMPTY,
                    inert(GameProfileRepository.class), new Names(), new Profiles());
        }
        return services;
    }

    /** An implementation that does nothing and finds nothing (empty, false, zero, null). */
    @SuppressWarnings("unchecked")
    private static <T> T inert(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Class<?> r = method.getReturnType();
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("toString")) return "Horizon's " + type.getSimpleName() + " (offline)";
            if (r == Optional.class) return Optional.empty();
            if (r == boolean.class) return false;
            if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
            if (r == float.class || r == double.class) return 0.0;
            if (r == List.class || r == Collection.class) return List.of();
            if (r == Set.class) return Set.of();
            if (r == Map.class) return Map.of();
            if (r == java.util.concurrent.CompletableFuture.class) return java.util.concurrent.CompletableFuture.completedFuture(null);
            return null;
        });
    }

    private static final class Names implements UserNameToIdResolver {
        @Override public void add(NameAndId entry) {
            BY_NAME.put(entry.name().toLowerCase(java.util.Locale.ROOT), entry);
            BY_ID.put(entry.id(), entry);
        }
        @Override public Optional<NameAndId> get(String name) {
            NameAndId known = BY_NAME.get(name.toLowerCase(java.util.Locale.ROOT));
            return Optional.of(known != null ? known : new NameAndId(UUIDUtil.createOfflinePlayerUUID(name), name));
        }
        @Override public Optional<NameAndId> get(UUID id) {
            return Optional.ofNullable(BY_ID.get(id));
        }
        @Override public void resolveOfflineUsers(boolean offline) {}
        @Override public void save() {}
    }

    private static final class Profiles implements ProfileResolver {
        @Override public Optional<GameProfile> fetchByName(String name) {
            NameAndId known = BY_NAME.get(name.toLowerCase(java.util.Locale.ROOT));
            return Optional.ofNullable(known).map(k -> new GameProfile(k.id(), k.name()));
        }
        @Override public Optional<GameProfile> fetchById(UUID id) {
            return Optional.ofNullable(BY_ID.get(id)).map(k -> new GameProfile(k.id(), k.name()));
        }
    }
}
