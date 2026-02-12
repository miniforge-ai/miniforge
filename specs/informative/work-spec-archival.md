# Work Spec Archival Strategy

**Status:** Informative
**Date:** 2026-02-08

## Context

When miniforge executes a work spec (`work/*.spec.edn`), the completed spec
needs to be archived somewhere. For miniforge's own repository, this is
straightforward. For arbitrary user repositories, miniforge cannot assume
directory conventions.

## Design

### Miniforge's own repository (default)

Completed work specs move from `work/` to `specs/archived/`:

```bash
git mv work/completed-feature.spec.edn specs/archived/
```

This provides human-browsable discoverability — `ls specs/archived/` shows
what was built. The `work/` directory stays clean with only active/pending
specs.

### Arbitrary repositories (miniforge as product)

When miniforge operates on a user's repository, the consumed work spec
should be captured as an **N6 evidence artifact** within the workflow run's
evidence bundle. The spec is provenance data — it's the input that produced
the workflow's output.

This means:

1. The work spec is stored in the evidence bundle as artifact type
   `:artifact/work-spec` alongside other provenance artifacts
2. No directory conventions are imposed on the target repository
3. The spec is discoverable via `miniforge evidence list` or the TUI
4. The evidence bundle links the spec to its workflow run, phases, and
   produced artifacts

### Future: opt-in file-based archival

Repositories that want the `specs/archived/` convenience pattern can opt in
via `.miniforge/config.edn`:

```clojure
{:archival {:work-specs {:destination "specs/archived/"
                         :strategy :move}}}  ;; or :copy
```

This is additive — the evidence bundle always captures the spec regardless
of whether file-based archival is also configured.

## Trade-offs

| Approach | Discoverability | Convention-free | Provenance chain |
|----------|----------------|-----------------|-----------------|
| `specs/archived/` only | `ls` browsable | No (imposes dirs) | Weak |
| Evidence bundle only | Needs CLI/TUI | Yes | Strong |
| Both (opt-in) | Best of both | Yes (opt-in) | Strong |

## Relationship to normative specs

- **N6 (Evidence)**: The work spec artifact type should be added to
  §3.1.1 when this is implemented
- **N5 (CLI/TUI)**: `miniforge evidence list` should surface archived
  work specs
- **N1 (Architecture)**: No changes needed — work specs are already
  understood as workflow inputs
