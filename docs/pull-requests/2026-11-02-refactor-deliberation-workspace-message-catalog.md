<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(deliberation-workspace): move anomaly messages into a system catalog

## Overview

`components/deliberation-workspace` built all 19 of its rejection anomalies
with raw English literals. Localization (dewey 050) puts anomaly message
text in the component's system catalog. This PR moves each literal, unchanged,
to `resources/config/deliberation-workspace/messages/system.edn` and looks
it up through `messages/system-t`.

## Motivation

The PRs that land the deliberation-workspace snapshot (N14 0.3.0) add new
rejections to `validation` and `guards`. Without a catalog, they would either
add more raw literals or mix catalogued and raw text in one namespace. This
prerequisite gives them a catalog to add keys to.

## Changes in Detail

- New `messages` namespace with `system-t`, the same shape as
  `gate.messages`.
- `validation` (15 literals) and `guards` (4 literals) now use catalog keys
  under `:anomaly/`. The text is byte-identical, so no message changes.
- The component's `deps.edn` gains the `resources` path and the `messages`
  dependency. Root `deps.edn` gains the component's `resources` path.
- New `messages_test`: every key passed to `system-t` has catalog text, and
  the catalog holds no unreferenced entry.

The coverage test reads the component's source files from the classpath to
collect keys. The testing standards (dewey 400) discourage filesystem reads in
tests. This follows `gate/messages_test`, which does the same.
No in-memory source lists the keys a call site passes.

Not changed: the `IllegalArgumentException` texts in `object` and `commit`.
They are programmer-error guards, which dewey 050 exempts.

## Testing Plan

Ran locally on plain Clojure 1.12. Clojars is unreachable from the authoring
sandbox, so a local stand-in replaced malli. All 12 deliberation-workspace
suites ran: 126 tests, 403 assertions, 0 failures. `poly test`, clj-kondo and
the Polylith check ran only in CI.

## Deployment Plan

None. Experimental component with no production caller.

## Related Issues/PRs

Precedes the deliberation-workspace domain PR in the N14 0.3.0 stack.

## Checklist

- [x] Text unchanged
- [x] Coverage test both ways (missing key, unused key)
