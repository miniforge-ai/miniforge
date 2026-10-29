<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Dashboard repo writers keep a config.edn that does not parse

## Overview and motivation

The dashboard's add-repo and discovery read `~/.miniforge/config.edn` through
`load-fleet-config`, which turns a parse error into `{:fleet {:repos []}}`, then
wrote that back. One click on a config with a typo replaced the user's whole
config with a repo list.

## Layer and dependencies

Adapter. Stacks on the shared config writer PR (`config.interface.file`).
Merge after the pr-sync repo-writer PR: until that lands, the TUI writers still
lock `config.edn` itself, while this one locks `config.edn.lock`.

## Changes

1. `state/trains.clj`: `add-configured-repo!` and `discover-configured-repos!`
   go through `update-fleet-repos!`, built on `config-file/update-config!`. It
   sets `[:fleet :repos]`, keeps every other key, and on a file that is not a
   single EDN map returns a failure naming the file, with the anomaly under
   `:anomaly`, and writes nothing.
2. The private `with-config-lock!`, `save-fleet-config!` and lock messages are
   removed.

## Verification plan

Regression test per writer: a truncated map in a temp config file; the writer
returns `:success? false` with the path in `:error` and an `:invalid-input`
anomaly, and the file is unchanged. Fails against main's code. All 109
trains tests pass.

## Scope and deployment

Normal deployment; no format change. `load-fleet-config` (reads) still falls
back to an empty fleet. Known, pre-existing: `json-response` cannot encode the
`java.time.Instant` in `:anomaly/at`, so a failed add/discover returns an error
from the JSON encoder rather than this message (true of every trains failure).
