package com.hkg.autocomplete.aggregator.sharding;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.fstprimary.FstShard;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves {@link ShardKey} → {@link FstShard}.
 *
 * <p>Production deployment is etcd-backed: the index-build pipeline
 * writes the active artifact path per {@code (tenant, family)} cell
 * into etcd; aggregators watch the keys and pick up new shards on the
 * next request. The interface here is the smallest abstraction the
 * {@code MultiFstShard} composite needs to drive multi-cell fanout.
 *
 * <p>A request with multiple families fans out to multiple cells.
 * {@link #shardsFor} is the bulk lookup helper.
 */
public interface ShardMap {

    Optional<FstShard> lookup(ShardKey key);

    /** @return the FstShard for every {@code (tenant, family)} cell
     *  the request implicates, in deterministic order. Missing cells
     *  are silently skipped — production logs a metric. */
    default List<FstShard> shardsFor(TenantId tenantId, Set<EntityFamily> families) {
        List<FstShard> out = new ArrayList<>(families.size());
        for (EntityFamily f : families) {
            lookup(ShardKey.of(tenantId, f)).ifPresent(out::add);
        }
        return out;
    }
}
