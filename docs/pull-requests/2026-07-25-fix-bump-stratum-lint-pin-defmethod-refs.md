# fix: bump stratum-lint pin for the defmethod refs-union fix

## Overview

Bumps `tasks/stratum.clj`'s pinned `stratum-lint` sha from `80699e37`
(current on `main`) to `14965e1`, which includes
[stratum-lint#14](https://github.com/miniforge-ai/stratum-lint/pull/14):
`infer-levels` built its dependency graph with `into {}` keyed by def
name — a repeated key (every `defmethod` on one multimethod shares the
multimethod's own name) kept only the *last* entry's refs, silently
discarding every earlier `defmethod`'s references.

## Motivation

Found during Wave 1 batch 3's `release-executor` component PR:
`process-file-action`'s `:default` method (last in file, calls nothing)
overwrote `:create`/`:modify`/`:delete`'s refs (which call several
layers deep into `path-traversal-anomaly`) in the graph. `--fix`
computed the multimethod as a leaf and placed it *before* the function
it actually calls — a forward reference breaking both `clj-kondo` and
Clojure compilation. `release-executor`'s Wave 1 PR excluded
`components/release-executor/.../files.clj` from its `--fix` pass
pending this fix, rather than committing broken output.

## Changes in Detail

- `tasks/stratum.clj`: `stratum-lint-deps`'s pinned sha,
  `80699e37` → `14965e1`.

## Testing Plan

Confirmed the sha resolves via `bb -Sdeps`. Pre-commit hook (which this
file wires into) passes clean on this change.

## Deployment Plan

Merges to `main` immediately. Follow-on: `release-executor`'s Wave 1 PR
can now run `--fix` over `files.clj` too, once this pin lands.

## Related Issues/PRs

- Fix consumed: [stratum-lint#14](https://github.com/miniforge-ai/stratum-lint/pull/14)
- Unblocks: `files.clj` in `release-executor`'s Wave 1 PR
- Part of: `work/stratum-lint-baseline-2026-07-24.md`, Wave 1 batch 3

## Checklist

- [x] Sha resolves via `bb -Sdeps`
- [x] Pre-commit hook passes clean
