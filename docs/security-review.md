# Security Review — Distributed Search Autocomplete

This document is the pre-launch security review for the typeahead
service. It enumerates the threat model, the controls that mitigate
each threat, and the operator workflow plus the pre-launch checklist
that confirms the controls are wired before any traffic is admitted.

The implementation choices referenced here are described in detail in
[`raw-blog/search-autocomplete-full.md`](../raw-blog/search-autocomplete-full.md);
this document focuses on the security-relevant subset.

---

## Threat Model

### T1 — Cross-tenant data leakage

**Risk.** A query from tenant A returns a candidate that belongs to
tenant B.

**Likelihood × impact.** Low likelihood / catastrophic impact — a
single leaked candidate from a competitor's corpus is a P0 incident.

**Controls.**
- **Pre-filter at retrieval.** Tenant is the primary sharding axis
  (`ShardKey = (tenantId, EntityFamily)`). The aggregator's
  `MultiFstShard` only fanss out to shards belonging to the request's
  tenant; cross-tenant shards are never queried. See
  `autocomplete-aggregator/sharding/MultiFstShard.java`.
- **Bitmap pre-filter.** The `PartitionIndex.intersection()` requires
  every candidate's ordinal to be a member of the
  `tenant=<tenantId>` bitmap. Missing-key short-circuits to empty
  (defensive default) so a missing tenant bitmap cannot accidentally
  mean "every entity is eligible".
- **Cache key segregation.** Both the edge cache (`EdgeCacheKey`)
  and the aggregator-level `TenantPoolCache` include `tenantId` in
  the cache key; cross-tenant cache hits are physically impossible.
- **Tenant-scoped invalidation.** `TenantPoolCache.invalidate(tenant)`
  drops every entry for a tenant — used when a tenant's index is
  swapped or when a takedown affects many entries.

### T2 — Unauthorized visibility (within-tenant ACL bypass)

**Risk.** A user sees a candidate they are not authorized to see —
either by `Visibility` class (PUBLIC / INTERNAL / RESTRICTED) or by
viewer-relationship privacy.

**Controls.**
- **Pre-filter on hard partitions.** The aggregator's pre-filter
  intersects against `visibility ≤ viewer` bitmaps, derived from the
  request's `viewerVisibility`. `Visibility.visibleAt()` enforces the
  PUBLIC ⊂ INTERNAL ⊂ RESTRICTED ordering.
- **Post-filter on per-user permissions.** `PrincipalSet.satisfiesAny`
  checks the candidate's required principals against the viewer's
  cached expansion. Empty required → unrestricted (correct default
  for entities with no per-user grants).
- **Viewer-relationship post-filter (Meta pattern).** For
  relationship-sensitive surfaces, `RelationshipFilter` consults the
  social-graph `RelationshipResolver` and drops candidates whose
  visibility forbids the viewer-candidate edge. BLOCKED never sees;
  NONE only sees PUBLIC.
- **Principal cache TTL.** The `TtlPrincipalCache` is bounded at the
  production-default 5 minutes. Revoked grants take effect within
  that window, or immediately via the `invalidate-principal` admin
  command for revocation-critical events.

### T3 — Position-bias feedback loop

**Risk.** The reranker, trained naively on click logs, learns to
rank by what was previously ranked highly — calcifying the model
over iterations and producing a self-reinforcing loop where the same
candidates keep winning regardless of true relevance.

**Controls.**
- **Counterfactual LTR.** The training pipeline (`autocomplete-training`)
  weights samples by inverse propensity:
  `weight = 1 / propensity(position)`. A click at rank 5 contributes
  more training signal than a click at rank 0 because rank 5 is
  shown less often.
- **Empirical propensity fitting.** `PropensityFitter` derives the
  position-bias curve from a small fraction (~0.1%) of randomized-
  top-K traffic. Production refreshes this weekly; falls back to the
  analytic `1/log2(p+2)` model when fit data is sparse.
- **Team-draft interleaving.** New rankers are validated via
  `TeamDraftInterleaver` + `InterleavingEvaluator` before promotion;
  10–100× more sensitive than parallel A/B and detects 0.1% NDCG
  regressions before they roll out to all traffic.

### T4 — Training data leakage

**Risk.** PII (per-user click history, email, IP, full query text) is
exfiltrated through the training pipeline — either via direct disk
read of the impression / click logs or indirectly via a model that
memorizes specific user-candidate pairs.

**Controls.**
- **Feature vector audit.** `FeatureVector` accepts only numeric
  doubles and rejects NaN/Infinity. The reranker never accepts raw
  PII; features are pre-aggregated by the feature server (Caffeine
  + Redis tier) which is the controlled write boundary.
- **Impression / click schema.** `Impression` and `Click` carry
  trace IDs and entity IDs but never raw query text bodies, IPs, or
  cookies. The query field is the normalized prefix only — already
  stripped of identity.
- **Counterfactual sampler scope.** `CounterfactualSampler` emits
  `(userId, prefix, entityId, position, label, weight)` tuples;
  there is no path for free-form text or contact information to
  reach the trainer.
- **Pre-training drop-list.** Production deploys a "no-train" filter
  upstream of the trainer that drops impressions for accounts on the
  takedown list, GDPR right-to-be-forgotten requests, or any account
  that the legal team has flagged. Implementation lives in the
  Spark/Flink layer, not in this repo.

### T5 — Edge cache poisoning via `Set-Cookie`

**Risk.** A deploy that introduces a session cookie on the typeahead
response disables CDN caching globally. Cache hit rate collapses from
~90% to 0% within minutes; reranker QPS at the origin spikes 5–10×;
p99 latency exceeds the 100 ms SLA.

**Controls.**
- **CI assertion.** A pre-merge test strips `Set-Cookie` from the
  typeahead response path and fails if any handler re-introduces it.
  Lives in the gateway repo, not here.
- **Synthetic cache-hit-rate alarm.** Prometheus alert fires within
  one minute of a deploy if the
  `edge_cache_hit_rate` (a Counter ratio computed at scrape time)
  drops more than 30% from its trailing baseline.
- **Cache-key audit (this repo).** `EdgeCacheKey` constructor
  intentionally has no `userId` parameter; the structural
  impossibility of including user-scoped state in the key is
  reinforced by the unit test `cacheKeyHasNoUserId` in
  `EdgeWorkerTest`.

### T6 — Sandbox escape from feature-extraction code

**Risk.** A learned model or feature transformer executes in the
reranker JVM and escapes the JVM sandbox, gaining access to host
file system or other tenants' state.

**Controls.**
- **ONNX Runtime as the model boundary.** The reranker loads
  pre-compiled ONNX bundles, not arbitrary Java/JVM bytecode. ONNX
  Runtime executes the tensor graph in a confined operator set; no
  arbitrary code path is granted.
- **No user-controlled feature transforms.** The feature pipeline
  accepts numeric doubles only; the system has no path for a model
  trainer to inject a Java method or script step at serving time.

### T7 — Denial-of-service via prefix amplification

**Risk.** An attacker submits very short prefixes ("a", "ab") at
high rate, knowing they match many candidates and trigger expensive
fanout + rerank cycles.

**Controls.**
- **Rate limiting at the edge.** Per-user rate limit enforced at the
  edge worker (`EdgeWorker`) before any origin call. Production
  deployments use Cloudflare's per-IP / per-token quota.
- **Pool size cap.** `AggregatorRequest.maxPoolSize` is bounded at
  the edge to a sensible cap (default 100 / 200 in tests). The
  reranker never sees more than this number of candidates per query.
- **Per-shard deadlines.** `DefaultAggregator` runs each retrieval
  shard with a per-shard deadline; a shard that goes hot under load
  is skipped on subsequent queries (incomplete-coverage flag set;
  caching layer refuses to cache the partial result).
- **Tenant pool cache.** `TenantPoolCache` absorbs hot-tenant
  bursts at the aggregator; the reranker sees only one query per
  cache window per (tenant, locale, prefix, family-bundle) cell.

### T8 — Index corruption / verifier bypass

**Risk.** A botched index build promotes a corrupt FST that misses
common completions or surfaces wrong entity metadata.

**Controls.**
- **Sampled top-1 verification.** `Verifier.sampledTopOne` checks
  the new FST against a held-out validation set; any mismatch above
  the configured `maxMissRatio` (production default 0.001 → 0.1%)
  rejects the build.
- **Atomic blue/green swap.** `BlueGreenIndexBuildJob` only flips
  the etcd alias on a passed verifier. Verifier rejection preserves
  the previous artifact; the shard never serves the bad build.
- **Build-failure alert.** Failed builds emit a metric counter; a
  Prometheus alert pages on-call after one failure (vs. waiting for
  the next scheduled run).

### T9 — Stuck delta tier

**Risk.** The delta tier compaction signal fires but the index-build
job hangs; delta entries pile up beyond the soft cap and approach the
hard cap. New writes start being rejected; freshness SLA breaks.

**Controls.**
- **Hard cap rejects writes.** `InMemoryDelta.apply()` raises
  `IllegalStateException` when the hard cap (5x soft cap) is hit.
  Operator paging signal.
- **WAL durability.** Even when writes are rejected, the WAL
  (`JournalingWalSink`) retains the last-accepted state; recovery
  after operator intervention does not lose ingested data.
- **Compaction-deadline alert.** Prometheus alert fires when delta
  age exceeds 45 minutes (default soft cap is 30 min); on-call
  intervention before the hard cap is reached.

---

## Operator Workflow

The runtime maintenance commands lives in `autocomplete-admin`
(`AdminCli`). Authentication, audit logging, and the richer
presentation layer wrap this dispatch surface in production.

| Command | When to run | Effect |
|---|---|---|
| `status` | Routine health-check | Reports delta live count, principal cache size, tenant pool cache size |
| `takedown <tenant> <entityId> <text> <family> <visibility>` | Compliance request, legal takedown, urgent revocation | Emits a tombstone via the delta tier; invalidates that tenant's pool cache |
| `invalidate-tenant <tenantId>` | After tenant index swap, mass takedown | Drops every pool-cache entry for the tenant |
| `invalidate-principal <userId>` | User termination, role change that can't wait for 5-min TTL | Drops the user's cached principal expansion |

Each command is a single-line invocation backed by the structured
`Result.ok()` / `Result.error()` types — no human-readable parsing
ambiguity at the audit layer.

---

## Pre-launch Checklist

Each item is a binary go/no-go before the service accepts production
traffic.

### Architecture

- [ ] FST shards deployed as StatefulSet (not Deployment) so Pod
      identity is stable across restarts.
- [ ] Delta tier deployed as StatefulSet with PV-backed WAL.
- [ ] Aggregator HPA configured for both CPU and request-rate scaling.
- [ ] Index-build CronJob scheduled with `concurrencyPolicy: Forbid`
      and a 4h `activeDeadlineSeconds`.
- [ ] etcd cluster sized at minimum three nodes (odd number for
      consensus quorum).

### ACL & privacy

- [ ] Tenant boundary verified via integration test that queries
      tenant A and confirms no tenant B candidates returned.
- [ ] Visibility ladder verified at all three viewer levels (PUBLIC /
      INTERNAL / RESTRICTED).
- [ ] Viewer-relationship post-filter wired for relationship-sensitive
      surfaces; otherwise explicitly disabled.
- [ ] Principal cache TTL set to 5 minutes (production default).
- [ ] `invalidate-principal` admin command tested on a sample user.
- [ ] Takedown end-to-end tested: tombstone applied → query no longer
      returns the entity within one query cycle.

### Caching

- [ ] Edge cache key audit: confirm `userId` does not appear in the
      key shape (run `EdgeWorkerTest.cacheKeyHasNoUserId`).
- [ ] CI assertion blocks `Set-Cookie` on the typeahead response path.
- [ ] Synthetic cache-hit-rate alarm wired in Prometheus (drop > 30%
      from baseline triggers page).
- [ ] TenantPoolCache TTL configured to 10 seconds (production default).
- [ ] `incompleteCoverage` flag respected by `CachingAggregator` —
      partial results are not cached.

### Training pipeline

- [ ] Impression and click logging wired with trace IDs joinable in
      Flink within 5 minutes (i.e. impressions don't expire from the
      Flink state before clicks arrive).
- [ ] Counterfactual LTR sampling implemented; raw click logs are
      not used for training.
- [ ] Position-bias propensity model fit weekly via randomized-top-K
      holdout (~0.1% of traffic).
- [ ] Team-draft interleaving harness deployed; new rankers validated
      via interleaving for 24 hours minimum before promotion.
- [ ] Pre-training PII drop-list wired upstream of the trainer.

### Observability

- [ ] Prometheus ServiceMonitors deployed for all three tiers.
- [ ] Dashboards present for: aggregator p99 latency, edge cache
      hit rate, tenant pool cache hit rate, principal cache live count,
      delta live count, delta needs-compaction signal, FST shard
      lookup latency.
- [ ] Alerts wired for: aggregator p99 > 100ms, FST shard timeout
      rate > 1%, delta needs-compaction not cleared in 45min, build
      failure, edge cache hit rate drop > 30%, tenant pool cache
      hit rate drop > 30%.

### Network

- [ ] Default-deny NetworkPolicy applied; tested by attempting an
      unauthorized cross-Pod request and confirming it is dropped.
- [ ] Egress restricted to S3 + corpus DBs + etcd; no general
      internet egress from FST shards or aggregator Pods.
- [ ] mTLS enabled between aggregator and FST shards (via service
      mesh sidecar, not modelled in this repo's manifests).

### Index integrity

- [ ] `Verifier.sampledTopOne` configured with a 200-entry held-out
      validation set per shard; `maxMissRatio` set to 0.001.
- [ ] Build-failure alert pages on-call on first failure (no waiting
      for the next scheduled run).
- [ ] Blue/green drain window set to 60 seconds.

---

## Out-of-scope concerns

The following are real production concerns explicitly out of scope
for this repo:

- **Authentication & authorization at ingress.** The CLI and the
  aggregator both trust upstream layers (gateway, auth service) to
  attest the requesting `userId` and `tenantId`. The repo does not
  implement JWT verification, OAuth flows, or session management.
- **Encryption at rest for FST blobs.** Production runs S3 SSE-KMS
  on the index bucket; the repo's deploy manifests assume this is
  configured at the bucket level.
- **DDoS mitigation upstream of the edge.** Volumetric attacks are
  the CDN's responsibility; the repo's edge worker only handles
  per-user / per-tenant rate limits at the application layer.
- **Audit log persistence.** Admin commands return structured
  `Result` types; the production tooling layer is expected to
  persist these to a secure audit store (e.g. WORM-S3) with
  cryptographic signing. The repo does not implement that pipeline.

---

## Sign-off

This document must be reviewed and approved by:

- Service owner (lead SRE)
- Security architect
- Privacy / data-governance representative
- On-call lead for the launch week

Each reviewer signs off on the controls in their domain. The
`status` admin command output should match the expected baseline
captured during pre-launch validation; deviations are blockers.
