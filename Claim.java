package dev.keepclaims;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A territory: one or more connected chunks owned by one player that share one upkeep
 * clock and one trust list. owner == null means a server claim (free, never expires).
 */
public final class Claim {
    public UUID owner;
    public String ownerName;
    public long created;
    public long paidUntil;
    public final Set<UUID> trusted = new LinkedHashSet<>();
    public final Set<ChunkKey> chunks = new LinkedHashSet<>();

    public Claim(UUID owner, String ownerName, long created, long paidUntil) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.created = created;
        this.paidUntil = paidUntil;
    }

    public boolean isServer() {
        return owner == null;
    }

    public int size() {
        return chunks.size();
    }
}
