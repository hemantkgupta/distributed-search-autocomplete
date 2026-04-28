package com.hkg.autocomplete.admin;

import com.hkg.autocomplete.acl.PrincipalCache;
import com.hkg.autocomplete.aggregator.TenantPoolCache;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.deltatier.DeltaEntry;
import com.hkg.autocomplete.deltatier.DeltaTier;

import java.util.Arrays;
import java.util.Objects;

/**
 * Operator CLI dispatch surface for runtime maintenance commands.
 *
 * <p>The full blog calls out a handful of operator-grade actions that
 * are routine in production:
 * <ul>
 *   <li><b>status</b> — sizes of the principal cache and tenant pool
 *       cache; useful for sizing dashboards.</li>
 *   <li><b>takedown</b> — emit a tombstone via the delta tier so a
 *       compliance request takes effect within the freshness window
 *       even before the next index rebuild.</li>
 *   <li><b>invalidate-tenant</b> — drop tenant-scoped pool cache
 *       entries (used when a tenant's index is swapped or a takedown
 *       affects many entries at once).</li>
 *   <li><b>invalidate-principal</b> — drop one user's cached
 *       principal expansion (used on revocation events that cannot
 *       wait for the 5-minute TTL).</li>
 * </ul>
 *
 * <p>This implementation is intentionally minimal — it is the
 * dispatch surface the production tooling layer wraps with
 * authentication, audit logging, and a richer presentation layer.
 */
public final class AdminCli {

    private final DeltaTier delta;
    private final PrincipalCache principalCache;
    private final TenantPoolCache tenantPoolCache;

    public AdminCli(DeltaTier delta,
                    PrincipalCache principalCache,
                    TenantPoolCache tenantPoolCache) {
        this.delta = Objects.requireNonNull(delta, "delta");
        this.principalCache = Objects.requireNonNull(principalCache, "principalCache");
        this.tenantPoolCache = Objects.requireNonNull(tenantPoolCache, "tenantPoolCache");
    }

    public Result run(String... args) {
        if (args.length == 0) {
            return Result.error("usage: status | takedown | invalidate-tenant | invalidate-principal");
        }
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (cmd) {
            case "status" -> status();
            case "takedown" -> takedown(rest);
            case "invalidate-tenant" -> invalidateTenant(rest);
            case "invalidate-principal" -> invalidatePrincipal(rest);
            default -> Result.error("unknown command: " + cmd);
        };
    }

    private Result status() {
        return Result.ok(String.format(
                "delta_live=%d delta_needs_compaction=%s principals_live=%d tenant_pools_live=%d",
                delta.liveCount(),
                delta.needsCompaction(),
                principalCache.size(),
                tenantPoolCache.size()));
    }

    private Result takedown(String[] args) {
        if (args.length < 5) {
            return Result.error("takedown requires: <tenantId> <entityId> <canonical> <family> <visibility>");
        }
        try {
            TenantId tenant = TenantId.of(args[0]);
            String entityId = args[1];
            String canonical = args[2];
            EntityFamily family = EntityFamily.fromString(args[3]);
            Visibility visibility = Visibility.valueOf(args[4]);
            DeltaEntry tombstone = new DeltaEntry(
                    entityId, canonical, 0L, tenant, family, visibility,
                    System.currentTimeMillis(), /*tombstone=*/true);
            delta.apply(tombstone);
            // Aggressive: drop any cached pool that may have surfaced
            // the now-tombstoned entity. Coarse but correct.
            tenantPoolCache.invalidate(tenant);
            return Result.ok("tombstoned " + entityId + " in tenant " + tenant);
        } catch (RuntimeException re) {
            return Result.error("takedown failed: " + re.getMessage());
        }
    }

    private Result invalidateTenant(String[] args) {
        if (args.length < 1) {
            return Result.error("invalidate-tenant requires: <tenantId>");
        }
        try {
            TenantId t = TenantId.of(args[0]);
            tenantPoolCache.invalidate(t);
            return Result.ok("invalidated tenant pool cache for " + t);
        } catch (RuntimeException re) {
            return Result.error("invalidate-tenant failed: " + re.getMessage());
        }
    }

    private Result invalidatePrincipal(String[] args) {
        if (args.length < 1) {
            return Result.error("invalidate-principal requires: <userId>");
        }
        principalCache.invalidate(args[0]);
        return Result.ok("invalidated principal cache for user " + args[0]);
    }

    /** Result of one CLI invocation; success or error with a message. */
    public static final class Result {
        private final boolean ok;
        private final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public static Result ok(String message) { return new Result(true, message); }
        public static Result error(String message) { return new Result(false, message); }

        public boolean ok() { return ok; }
        public String message() { return message; }
    }
}
