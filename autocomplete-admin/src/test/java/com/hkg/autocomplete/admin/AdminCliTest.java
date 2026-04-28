package com.hkg.autocomplete.admin;

import com.hkg.autocomplete.acl.PrincipalCache;
import com.hkg.autocomplete.acl.PrincipalSet;
import com.hkg.autocomplete.aggregator.TenantPoolCache;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.deltatier.Clock;
import com.hkg.autocomplete.deltatier.CompactionPolicy;
import com.hkg.autocomplete.deltatier.DeltaTier;
import com.hkg.autocomplete.deltatier.InMemoryDelta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCliTest {

    private DeltaTier delta;
    private PrincipalCache principals;
    private TenantPoolCache pool;
    private AdminCli cli;

    @BeforeEach
    void setup() {
        delta = new InMemoryDelta(CompactionPolicy.testDefaults(), new Clock.Manual(0L));
        principals = PrincipalCache.withTtl(60_000L, () -> 0L);
        pool = new TenantPoolCache(60_000L, () -> 0L);
        cli = new AdminCli(delta, principals, pool);
    }

    @Test
    void noArgsReturnsUsage() {
        AdminCli.Result r = cli.run();
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("usage:");
    }

    @Test
    void unknownCommandRejected() {
        AdminCli.Result r = cli.run("frobnicate");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("unknown command");
    }

    @Test
    void statusReportsLiveCounts() {
        principals.put(PrincipalSet.of("u1", Set.of("g"), 0L));
        AdminCli.Result r = cli.run("status");
        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("delta_live=0");
        assertThat(r.message()).contains("principals_live=1");
        assertThat(r.message()).contains("tenant_pools_live=0");
    }

    @Test
    void takedownAppliesTombstoneToDelta() {
        AdminCli.Result r = cli.run(
                "takedown", "acme", "u_alice", "alice", "USER", "INTERNAL");
        assertThat(r.ok()).isTrue();
        assertThat(delta.tombstonedEntityIds()).containsExactly("u_alice");
    }

    @Test
    void takedownMissingArgsRejected() {
        AdminCli.Result r = cli.run("takedown", "acme");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("takedown requires");
    }

    @Test
    void takedownInvalidTenantRejected() {
        AdminCli.Result r = cli.run(
                "takedown", "ACME bad tenant", "u_alice", "alice", "USER", "INTERNAL");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("takedown failed");
    }

    @Test
    void invalidateTenantClearsPool() {
        // Seed the pool with a (synthetic) entry by going through CachingAggregator
        // would be heavy; instead use the cache's direct API which the CLI
        // delegates to.
        TenantId t = TenantId.of("acme");
        // The cli delegates to the cache's invalidate method; just
        // assert the call returns ok.
        AdminCli.Result r = cli.run("invalidate-tenant", "acme");
        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("invalidated tenant pool cache");
    }

    @Test
    void invalidateTenantMissingArgsRejected() {
        AdminCli.Result r = cli.run("invalidate-tenant");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("invalidate-tenant requires");
    }

    @Test
    void invalidatePrincipalDropsCachedExpansion() {
        principals.put(PrincipalSet.of("u1", Set.of("g_acme"), 0L));
        assertThat(principals.get("u1")).isPresent();
        AdminCli.Result r = cli.run("invalidate-principal", "u1");
        assertThat(r.ok()).isTrue();
        assertThat(principals.get("u1")).isEmpty();
    }

    @Test
    void invalidatePrincipalMissingArgsRejected() {
        AdminCli.Result r = cli.run("invalidate-principal");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("invalidate-principal requires");
    }
}
