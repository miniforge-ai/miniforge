<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: TUI repo writers keep a config.edn that does not parse

## Overview and motivation

`add-repo!`, `remove-repo!` and `discover-repos!` (the TUI's `:repo add`,
`:repo remove` and discovery) loaded config.edn through `load-fleet-config`,
which turns a parse error into `{:fleet {:repos []}}`, and wrote that back over
the user's whole config.

## Layer and dependencies

Adapter. Stacks on the pr-sync stratum autofix PR, which stacks on the shared
config writer PR.

## Changes

1. The three writers go through `update-fleet-repos!` (Layer 1, so the callers
   keep their layer), built on `config-file/update-config!`. It sets
   `[:fleet :repos]`, keeps every other key, and on a file that is not a single
   EDN map returns a failure naming the file, with the anomaly under
   `:anomaly`, and writes nothing. `save-fleet-config!` uses the same writer.
2. The private `with-config-lock!` (a lock on config.edn itself) and its
   messages are removed; the shared writer locks `config.edn.lock`.

## Verification plan

Regression test for each of the three writers: a truncated map in a temp
config file; `:success? false`, the path in `:error`, an `:invalid-input`
anomaly, and the file unchanged. Fails against the base (12 failing
assertions). pr-sync and the TUI tests that call it pass.

## Scope and deployment

Normal deployment. `load-fleet-config` (reads) still falls back to an empty
fleet.
