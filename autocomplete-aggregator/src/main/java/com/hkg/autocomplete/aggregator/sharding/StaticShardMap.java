package com.hkg.autocomplete.aggregator.sharding;

import com.hkg.autocomplete.fstprimary.FstShard;

import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Build-once {@link ShardMap} populated at deployment time.
 *
 * <p>Production-canonical implementation is an etcd-watcher whose
 * cache is rebuilt whenever the build pipeline promotes a new shard.
 * This static variant covers tests and small single-process
 * deployments.
 */
public final class StaticShardMap implements ShardMap {

    private final Map<ShardKey, FstShard> map;

    private StaticShardMap(Map<ShardKey, FstShard> map) {
        this.map = Map.copyOf(map);
    }

    @Override
    public Optional<FstShard> lookup(ShardKey key) {
        return Optional.ofNullable(map.get(Objects.requireNonNull(key, "key")));
    }

    public int cellCount() {
        return map.size();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<ShardKey, FstShard> m = new HashMap<>();

        public Builder put(ShardKey key, FstShard shard) {
            m.put(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(shard, "shard"));
            return this;
        }

        public StaticShardMap build() {
            return new StaticShardMap(m);
        }
    }
}
