# Deployment

Kubernetes manifests for a regional typeahead deployment. Apply against a
cluster with the kube-prometheus-stack operator installed (for
`ServiceMonitor` resolution) and an `ssd` storage class wired to your
cloud provider's fast-tier disk.

## Topology

```
                      ┌─────────────────────────────────┐
                      │  Edge (CDN — Cloudflare/Fastly) │
                      │  pool cache, no user_id in key  │
                      └───────────────┬─────────────────┘
                                      │ origin miss
                                      ▼
                      ┌─────────────────────────────────┐
                      │  aggregator (Deployment, x12)   │
                      │  parallel fanout + rerank +     │
                      │  tenant pool cache + diversify  │
                      └───────────────┬─────────────────┘
                                      │
                ┌─────────────────────┼─────────────────────┐
                ▼                     ▼                     ▼
   ┌────────────────────┐ ┌────────────────────┐ ┌────────────────────┐
   │  fst-shard (STS,   │ │  delta-tier (STS,  │ │  reranker (in-     │
   │  x8) — primary FST │ │  x4) — mutable +   │ │  process; ONNX     │
   │  per (tenant×fam)  │ │  WAL on PV         │ │  model bundle)     │
   └────────────────────┘ └────────────────────┘ └────────────────────┘
                ▲                     ▲
                │                     │
                └──────── etcd (alias swap, shard map watcher) ────────┘

   ┌────────────────────────────────────────────────────────────┐
   │  index-build (CronJob, every 6h)                           │
   │  scan corpus → sort → Lucene FSTBuilder → S3 → verify →    │
   │  blue/green alias flip via etcd                            │
   └────────────────────────────────────────────────────────────┘
```

## File order

Numbered prefixes give a deterministic apply order (`kubectl apply -k .`
honors them; `kubectl apply -f` walks the directory in lexicographic
order):

| Prefix | Purpose |
|---|---|
| `00-namespace.yaml` | Namespace `typeahead` |
| `10-config.yaml` | ConfigMap with shared runtime tunables |
| `20-fst-shard-statefulset.yaml` | FST primary index, stable Pod identity, PV-backed |
| `21-delta-tier-statefulset.yaml` | Delta tier with WAL on PV |
| `30-aggregator-deployment.yaml` | Stateless aggregator + HPA |
| `40-index-build-cronjob.yaml` | Periodic FST rebuild, 6h schedule |
| `80-service-monitor.yaml` | Prometheus ServiceMonitors per tier |
| `90-network-policy.yaml` | Default-deny + targeted allow rules |

## Apply

```sh
kubectl apply -f deploy/k8s/
```

Validate:

```sh
kubectl -n typeahead rollout status statefulset/fst-shard
kubectl -n typeahead rollout status statefulset/delta-tier
kubectl -n typeahead rollout status deployment/aggregator
```

## Operational gotchas

- **FST PV size** must accommodate the largest expected shard slice
  plus the inactive blue/green slot during rebuild. The default 80Gi
  fits ~50M entries per shard at typical entity sizes.
- **Aggregator drain** — the 30-second `terminationGracePeriodSeconds`
  is enough for in-flight requests to complete (typeahead p99 server-
  side budget is 100ms). Lengthening this past 60s is wasted; the
  gateway already retries.
- **HPA cooldown** — bursty traffic patterns (viral signups) can
  cause oscillation; set `behavior.scaleDown.stabilizationWindowSeconds`
  to 300s in production overlays to bound thrash.
- **CronJob concurrency** — `Forbid` prevents two builds racing for
  the etcd alias. If a build hangs near the 4h `activeDeadlineSeconds`,
  on-call kills the Pod and the next schedule resumes.
- **NetworkPolicy default-deny** — only the aggregator can reach FST
  shards and delta tier on their gRPC ports; the index-build job
  reaches S3 via egress to `0.0.0.0/0:443`. Adjust the CIDR if your
  cluster runs S3 traffic through a NAT-restricted egress IP set.

## Edge worker

The edge layer runs on Cloudflare Workers / Fastly Compute@Edge, not
Kubernetes — see [`raw-blog/search-autocomplete-full.md`](../../raw-blog/search-autocomplete-full.md)
§1 (Edge Gateway Service) for the deployment shape. The worker
forwards on cache miss to the regional ingress that fronts the
`aggregator` Service in this namespace.
