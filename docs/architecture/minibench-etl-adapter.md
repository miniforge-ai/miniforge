<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Minibench product adapter: ETL first slice

Status: implemented on 2026-07-18

## Result

The Miniforge-owned ETL adapter projects a real `pipeline-run` into a validated
`workbench_snapshot/v1`. Minibench remains tenant-agnostic: it consumes the
snapshot and the product registry, never a Miniforge domain namespace.

The adapter supports the current file-based experiment workflow:

1. Run a baseline from one pipeline/environment pair.
2. Persist its snapshot.
3. Change one non-secret configured leaf.
4. Run the candidate with `--baseline <prior-snapshot.json>`.
5. Reject the candidate snapshot if the resolved configuration changed at zero
   or more than one factor path.

This is intentionally dynamic. Miniforge accepts open-ended pipeline and
environment maps, so there is no global closed factor enum. `N` is calculated
for each concrete resolved run.

## Defensible ETL factor count

The inventory recursively counts scalar leaves in the pipeline/environment
pair. Empty collections are addressable leaves, sequential members use stable
indexes, and sets remain atomic. Credential-bearing paths are excluded from N
and counted separately as redacted controls. Factor paths and values use
canonical EDN before hashing or JSON persistence.

The three ETL packs currently shipped by Miniforge produce:

| Pack | Non-secret factors (N) | Redacted credential leaves | Stages |
|---|---:|---:|---:|
| GitHub data | 75 | 5 | 6 |
| GitLab data | 103 | 8 | 9 |
| Risk data | 133 | 2 | 8 |

These counts are executable assertions in the adapter test suite. Adding,
removing, or restructuring shipped configuration must update the assertion and
this table in the same change.

The earlier implementation inventory of 88 factor *templates* remains useful
for connector coverage, but it is not N. Templates such as a header map or
stage vector can realize an arbitrary number of leaves. The per-run inventory
is the number Minibench experiments can defend.

## ETL output state variables

The product registry is `miniforge-etl-state-vars@2026.07.18.1` and defines:

- `miniforge.etl.run_completed`
- `miniforge.etl.stages_completed`
- `miniforge.etl.data_quality_pass_rate`

Every snapshot also stamps workload source hashes, the resolved-config hash,
factor count, redacted count, product policy hash/version, and evaluator
hash/version. The full non-secret factor inventory is retained in metadata so
the next file-based run can prove its one-factor diff.

## Commands

Compute a workload digest once and use the same value for every variant and
replicate. It identifies the input workload, not the pipeline or environment
configuration being varied.

```bash
mf etl run <pack-or-pipeline> --env <env> \
  --out runs/baseline-result.json \
  --workbench-out runs/baseline.json \
  --experiment-id miniforge.etl.example \
  --label baseline \
  --source-hash sha256:<64-lowercase-hex>

mf etl run <changed-pack-or-pipeline> --env <changed-env> \
  --out runs/candidate-result.json \
  --workbench-out runs/candidate.json \
  --experiment-id miniforge.etl.example \
  --label candidate \
  --source-hash sha256:<same-64-lowercase-hex> \
  --baseline runs/baseline.json

mf etl registry --out runs/miniforge-etl-registry.json
minibench compare runs/variants runs/miniforge-etl-registry.json
```

Each replicate needs a distinct run id. LLM-backed orchestration should start
with three replicates per label; deterministic ETL can start with one and add
replicates when connectors or remote inputs introduce noise.

## Waterfall, Agile, and Convergence status

Those names describe the orchestration benchmark families, not the ETL factor
count:

| Benchmark family | Current executable Miniforge configuration |
|---|---|
| Waterfall | `canonical-sdlc` v2.0.0: the explicit eight-entry sequential pipeline |
| Agile | `quick-fix` v2.0.0 is the current fast-path approximation; no first-class `agile` profile is shipped |
| Convergence | Review-to-implement redirects plus the review convergence caps in phase defaults; no standalone `convergence` workflow profile is shipped |

The raw workflow definitions contain 29 configured leaves for
`canonical-sdlc` and 13 for `quick-fix`. Those are not yet defensible
resolved-run Ns because orchestration still loads workflow, phase, prompt,
model, runner, and user defaults through independent resources. The
architecture note explicitly describes pre-run multi-candidate convergence as
future behavior.

The orchestration adapter should therefore follow this ETL slice only after a
single canonical resolved-run value is assembled and frozen at the run
boundary. At that point the same inventory/diff primitive can count Waterfall,
Agile, and Convergence without inventing a closed global schema.

## Verification

The implementation is covered by:

- adapter contract, registry, redaction, evaluation, and one-factor tests;
- executable 75/103/133 shipped-pack count assertions;
- ETL file-boundary tests, including JSON baseline reloading;
- an end-to-end local file run for baseline and candidate;
- registry-aware Minibench comparison of the real adapter snapshots;
- Polylith workspace checking and targeted clj-kondo linting.
