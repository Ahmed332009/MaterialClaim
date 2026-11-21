package dev.keepclaims;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class Claim {
    public final ChunkKey key;
    public UUID owner;
    public String ownerName;
    public final long created;
    public long paidUntil;
    public final Set<UUID> trusted = new LinkedHashSet<>();

    public Claim(ChunkKey key, UUID owner, String ownerName, long created, long paidUntil) {
        this.key = key;
        this.owner = owner;
        this.ownerName = ownerName;
        this.created = created;
        this.paidUntil = paidUntil;
    }

    public boolean isServer() {
        return owner == null;
    }
}
