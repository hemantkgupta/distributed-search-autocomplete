# Distributed Search Autocomplete

A Java 17 multi-module reference implementation of a global-scale search autocomplete (typeahead) service. Companion code for the [`search-autocomplete`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/search-autocomplete.md) and [`search-autocomplete-full`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/search-autocomplete-full.md) blog posts in the CSE wiki.

The full blog describes the architecture in twelve services. This repo organizes the implementation into Gradle modules with the same boundaries — each service is a module, each module is independently buildable and testable.

## Status

**All 5 phases complete (Checkpoints 1–28). 258 tests passing.** Full multi-module build green across all 17 modules. End-to-end pipeline working: corpus → FST + delta tier → query understanding → fanout → merge with tombstone shadow → pre-filter → reranker → counterfactual training loop → post-filter → diversification → tenant pool cache → edge worker pool cache → top-K to client.

Planned in 5 phases (CP1–CP28), mirroring `distributed-web-crawler`:

**Phase 1 — Foundation (single-JVM correctness):**
* **CP1** — `autocomplete-common`: `Prefix`, `EntityFamily`, `TenantId`, `Visibility`, `Candidate`, `Suggestion`, `RetrievalSource`
* **CP2** — `autocomplete-fst-primary`: Lucene `WFSTCompletionLookup`-backed shard with weighted top-N retrieval
* **CP3** — `autocomplete-delta-tier`: mutable trie delta with size/age bounds + WAL stub
* **CP4** — `autocomplete-query-understanding`: ICU normalization (UAX-15), simple language detect, locale-aware tokenization
* **CP5** — `autocomplete-fuzzy`: Levenshtein-automaton intersected with the lexicon FST, k=2 cap, language-conditional
* **CP6** — `autocomplete-aggregator`: parallel fanout to FST + delta + fuzzy, top-N merge, deadline-bounded
* **CP7** — `autocomplete-acl`: Roaring-bitmap pre-filter for hard partitions; principal-expansion stub
* **CP8** — `autocomplete-reranker`: feature-pipeline + simple linear reranker (LambdaMART placeholder)
* **CP9** — `autocomplete-node`: end-to-end integration; in-process composition; integration test against an embedded corpus

**Phase 2 — At-scale primitives:**
* **CP10** — `autocomplete-infix`: Lucene `AnalyzingInfixSuggester` for token-prefix queries
* **CP11** — `autocomplete-diversification`: type-cap policy + greedy MMR
* **CP12** — `autocomplete-acl` deepened: viewer-relationship post-filter (Meta pattern)
* **CP13** — `autocomplete-reranker` deepened: counterfactual LTR with logged-impressions API
* **CP14** — `autocomplete-index-build`: corpus extract → sort → Lucene FST builder → S3 stub → blue/green alias
* **CP15** — `autocomplete-delta-tier` durability: append-only WAL + replay
* **CP16** — `autocomplete-edge`: edge-worker simulator with pool cache + personalize-on-pool

**Phase 3 — Distribution:**
* **CP17** — Shard map + etcd-anchor SPI; tenant × entity-family routing
* **CP18** — Multi-shard fanout + per-shard deadlines + incomplete-coverage handling
* **CP19** — Caffeine principal-expansion cache with TTL
* **CP20** — Per-tenant pool cache at the aggregator (hot-tenant absorption)

**Phase 4 — Operability:**
* **CP21** — `autocomplete-training`: Kafka-shape impression+click event APIs; team-draft interleaving harness
* **CP22** — `autocomplete-admin`: CLI for index status, takedown, model-promote
* **CP23** — `autocomplete-simulator`: deterministic-replay testing harness
* **CP24** — `autocomplete-bench`: load-generation harness with p50/p99 measurement
* **CP25** — Position-bias propensity computation in training pipeline

**Phase 5 — Production deployment:**
* **CP26** — `deploy/`: Kubernetes manifests for FST shards, delta tier, aggregator, edge worker
* **CP27** — Observability — `Metrics` SPI + Prometheus exporter
* **CP28** — Security review — threat model, ACL audit, training-data leakage controls

## Build

Requires JDK 17+.

```sh
./gradlew build
./gradlew :autocomplete-common:test
```

## Module Structure

```
distributed-search-autocomplete/
├── autocomplete-common/             # Shared types, no dependencies
├── autocomplete-fst-primary/        # Lucene WFSTCompletionLookup shard
├── autocomplete-delta-tier/         # Mutable trie + WAL + compaction trigger
├── autocomplete-infix/              # Lucene AnalyzingInfixSuggester
├── autocomplete-fuzzy/              # Levenshtein-automaton ∩ FST
├── autocomplete-query-understanding/# ICU normalization, segmentation, lang detect
├── autocomplete-acl/                # Roaring-bitmap pre-filter + post-filter
├── autocomplete-reranker/           # Feature pipeline + LambdaMART (ONNX) inference
├── autocomplete-diversification/    # Type-cap + MMR
├── autocomplete-aggregator/         # Parallel fanout + merge + deadline
├── autocomplete-edge/               # Edge worker simulator (pool cache + personalize)
├── autocomplete-index-build/        # Corpus extract + Lucene FST builder + blue/green
├── autocomplete-training/           # Log mining + propensity + interleaving
├── autocomplete-node/               # End-to-end composition + integration tests
├── autocomplete-simulator/          # Deterministic-replay harness
├── autocomplete-bench/              # Load-generation harness
└── autocomplete-admin/              # Operator CLI
```

## Architectural Anchors

The implementation follows the engineering decisions captured in the wiki:

- **FST primary, not trie** — [[concepts/finite-state-transducer]] · [[tradeoffs/trie-vs-fst-vs-inverted-index]]
- **Trie is the delta tier** — [[patterns/fst-plus-delta-tier]] · [[concepts/delta-tier-overlay]]
- **Two-stage ranking** — [[patterns/two-stage-ranking]] · [[concepts/learning-to-rank]]
- **Hybrid ACL** — [[tradeoffs/pre-filter-vs-post-filter-acl]]
- **Edge cache the candidate pool** — [[tradeoffs/edge-cache-vs-personalization]]
- **Levenshtein-automaton fuzzy** — [[concepts/levenshtein-automaton]] (k=2 cap)
- **Counterfactual LTR** — [[concepts/query-log-mining]] · [[concepts/interleaving-evaluation]]

## License

Internal reference implementation; not for external distribution.
