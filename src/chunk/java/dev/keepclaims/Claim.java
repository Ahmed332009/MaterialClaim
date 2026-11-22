package dev.keepclaims;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A territory: one or more connected chunks owned by one player that share one upkeep
 * clock, one trust list and one set of settings.
 * owner == null means a server claim (free, never expires).
 */
public final class Claim {
    public UUID owner;
    public String ownerName;
    public long created;
    public long paidUntil;
    public final TrustList trusted = new TrustList();
    public final Set<ChunkKey> chunks = new LinkedHashSet<>();
    public final TerritorySettings settings = new TerritorySettings();

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

    /** Name shown to players: the custom territory name if set, else the owner name. */
    public String displayName() {
        return settings.displayName(ownerName);
    }

    /** True when this player may build here (owner, builder or manager). */
    public boolean mayBuild(UUID id) {
        if (isServer()) {
            return false;
        }
        if (id.equals(owner)) {
            return true;
        }
        return trusted.levelOf(id).weight >= TrustList.Level.BUILDER.weight;
    }

    /** True when this player may change free settings and pay upkeep. */
    public boolean isManager(UUID id) {
        if (isServer()) {
            return false;
        }
        if (id.equals(owner)) {
            return true;
        }
        return trusted.levelOf(id).weight >= TrustList.Level.MANAGER.weight;
    }
}
