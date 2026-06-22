# Route config.resource diagnostics through a system-locale catalog

## Overview

`ai.miniforge.config.resource/load-config-resource` is the shared, fail-fast
classpath-EDN loader ("one home for the load-this-component's-config-resource
pattern"). Its four fail-fast diagnostics were raw English string literals in
source. This routes them through a message catalog, so the canonical loader
emits operator-facing (system-locale) messages that are auditable and
overridable as data — the system-locale counterpart of a component's
user-locale `en-US.edn`.

Behavior-preserving: the thrown `ex-info` keeps the same `ex-data`
(`:config/resource`, `:config/missing-keys`) and the same message text; only
the message source moves from inline strings to the catalog.

## Motivation

The system-locale catalog convention already exists (`pr-lifecycle` and
`phase-software-factory` ship `messages/system.edn`). The canonical config
loader did not use it, so every caller — `tool-registry`, and any future caller
such as the `lsp-mcp-bridge` base — got hard-coded English. Putting the strings
in a catalog gives one place to audit or translate them and keeps the loader,
not each caller, as the single home for the pattern.

## Changes

- New `components/config/resources/config/config/messages/system.edn`
  (`:config/system` section) holding the four loader diagnostics.
- `components/config/src/ai/miniforge/config/resource.clj`: requires
  `messages.interface`, creates a private system-locale translator, and routes
  the missing / malformed / not-a-map / missing-keys `ex-info` messages through
  it. `read-config-resource-or` (fail-open) is unchanged.
- `components/config/deps.edn`: adds `ai.miniforge/messages` (`messages` has no
  intra-workspace deps, so no cycle; it is bb-safe).

## Verification

- JVM load smoke: `load-config-resource` returns the parsed map for a present
  resource; a missing resource throws with the catalog-routed message
  `Missing config resource on classpath: config/nope.edn` and `:config/resource`
  in `ex-data`.
- `config` resource tests: 6 tests, 14 assertions, 0 failures (they assert on
  `ex-data`, which is unchanged).
- `bb poly:check`: OK (the pre-existing Warning 205 about the test-fixture
  `.edn`/`.txt` files is unrelated).
