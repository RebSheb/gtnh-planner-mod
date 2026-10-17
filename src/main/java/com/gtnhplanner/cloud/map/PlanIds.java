package com.gtnhplanner.cloud.map;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A shared plan's ids and the game's. The game's own things are written under their UUIDs, so a UUID string reads back
 * as itself. Any other id (one the website made, {@code "node-1"}) is the UUID {@link UUID#nameUUIDFromBytes} makes of
 * it, the same on every game, and is written back as the string it was, never as that UUID.
 */
public final class PlanIds {

    private PlanIds() {}

    /** The game's id for a plan id. */
    public static UUID uuid(final String id) {
        try {
            final UUID u = UUID.fromString(id);
            // Only the canonical spelling: "1-1-1-1-1" also parses, and would come back written differently.
            if (u.toString()
                .equals(id)) return u;
        } catch (final IllegalArgumentException ignored) {}
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The game's id for a thing a website card brings with it beyond its own (a shared machine's later recipe): made
     * from the card's id and a part name, the same on every game.
     */
    public static UUID part(final String id, final String part) {
        return UUID.nameUUIDFromBytes((id + "#" + part).getBytes(StandardCharsets.UTF_8));
    }
}
