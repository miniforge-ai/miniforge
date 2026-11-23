<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(event-stream): split sinks.clj into seven namespaces (rule 210)

## Overview

Mechanical, no behaviour change. A rule 210 split of
`components/event-stream/src/ai/miniforge/event_stream/sinks.clj` (885 lines, 7 layers) into
seven namespaces, each within the 3-layer budget.

## Motivation

`sinks.clj` spans 7 call-graph layers against a budget of 3, so any edit to it fails SL003 in the
pre-commit hook. The file sink needs a change to report write failures (a follow-up PR), and that
change should pass stratum-lint without warn mode.

## Changes in Detail

Where each definition now lives:

1. `sinks` (kept): where the event store lives on disk and how an event file is encoded.
   `default-events-dir`, `live-dir`, `archived-dir`, `operator-dir`, `live-workflow-dir`,
   `archived-workflow-dir`, `operator-event-file-path`, `event->transit-json` and its private
   `instant-write-handler`. Three layers.
2. `sinks.naming` (new): the directory segment for a workflow id and the file name for an event.
   `safe-path-segment`, `workflow-id-segment`, `now-sortable-str`, `snowflake-uuid?`,
   `event-filename`, `new-event-file-path`, `event-file-ms`. Three layers.
3. `sinks.file` (new): the file sink. `workflow-dir`, `event-file-path`, `file-sink`. Two layers.
4. `sinks.cleanup` (new): stored-event retention. It owns `cleanup-stale-events!`, the `retention-hold`
   multimethod and its `:default` method. Their private helpers and defaults are
   (`default-event-ttl-ms`, `warned-runs`, `manifest-filename`, `walk-tree`, `delete-all!`,
   `child-dirs`, `archive-expired?`, `legacy-workflow-expired?`, `no-events?`,
   `idle-unmanaged-run?`, `run-verdict`, `archive-completed-run!`). Two layers.
5. `sinks.fleet` (new): `fleet-sink` and its three private defaults. Two layers.
6. `sinks.console` (new): `stdout-sink`, `stderr-sink`. One layer.
7. `sinks.factory` (new): `multi-sink`, `create-sink`, `create-sinks-from-config`, with the old
   `sinks` namespace docstring. Three layers.

Layout stays in `sinks` because `archive.clj` and `operator_requests.clj` already span four layers on main.
Editing either file fails SL003 in the pre-commit hook. Everything they call through `sinks/` therefore stays a real
definition in
`sinks`, and neither file changes. The file sink calls those definitions and the factory calls
the file sink, so the factory cannot stay in `sinks` without a require cycle. `reader.clj` and
`archive_test.clj` also call only layout functions and are unchanged.

There are no forwarding defs. Tests redefine three vars, and each redefinition targets the var
the code calls:

1. `sinks/default-events-dir`: still in `sinks`; `sinks.file` and `sinks.cleanup` call it as
   `sinks/default-events-dir`, through the var.
2. `cleanup/cleanup-stale-events!`: `file-sink` calls it as `cleanup/cleanup-stale-events!`.
3. `file/event-file-path`: in the same namespace as `file-sink`.

All 43 top-level forms move unchanged except for four kinds of edit:

1. Namespace qualification of calls to definitions now in a sibling namespace.
2. Public visibility for `workflow-id-segment` and `new-event-file-path`, which `sinks` and
   `sinks.file` now call.
3. New stratum numbers: `operator-event-file-path` 3 to 1, `workflow-dir` 3 to 0,
   `event-file-path` 3 to 0, `file-sink` 4 to 1.
   `create-sink` changes from 5 to 1; `create-sinks-from-config` changes from 6 to 2.
   The rest keep their numbers.
4. The namespace forms and docstrings. The in-file section comments (`;; Cleanup`,
   `;; Fleet Sink` and so on) are gone; the namespace names carry the same grouping.

Requirers changed only in their aliases. They are `core.clj`, `interface.clj`, `layout_reader.clj`,
`sinks_test.clj`, `anomaly/sinks_anomaly_test.clj`, `live_retention_test.clj` and `retention_hold_test.clj`.
This includes prose naming moved vars in the `core.clj` example and two namespace docstrings.
Two continuation lines in `sinks_test.clj` were realigned after an alias changed length.
`interface.clj` exports the same vars as before.

`sinks.file` uses two layers, so the follow-up can add one helper below `file-sink` and stay
within budget.

## Testing Plan

1. Form comparison against main. Every top-level form of main's `sinks.clj` and of the seven new
   files was read with `*read-eval*` false and printed with metadata. After normalizing `#()`
   gensyms, the split namespaces' qualifiers and `:stratum` metadata, 41 of 43 forms are identical.
   The other 2 differ only in `defn-` to `defn` (item 2 above). The new namespace
   forms require and import nothing main's did not, apart from each other. For each requirer,
   every form in order is identical to main's once the new aliases are mapped back to `sinks`.
   A one-token change to a new file or to a requirer makes the comparison fail.
2. clj-kondo on every new and changed file: no errors or warnings.
3. stratum-lint on the seven namespaces: no findings, and `--fix` changes nothing.
4. Every event-stream test namespace runs under the shared JVM lock with a temporary `MINIFORGE_HOME`.
   External tests using moved interface vars run there too: `retention-hold`, `workflow-dir`,
   `cleanup-stale-events!` and `event-file-ms`.

## Deployment Plan

Ships with the next release; no configuration or data change.

## Related Issues/PRs

1. Precedent: the release sandbox split (#2300) and the artifact-session split (#2260).
2. Unblocks the file sink write-failure change, stacked on this PR.

## Checklist

- [x] Every definition moved unchanged (normalized comparison)
- [x] Each namespace within 3 layers
- [x] Event-stream test namespaces pass
- [ ] CI green, Copilot comments resolved
