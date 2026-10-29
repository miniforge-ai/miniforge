<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Publish the executing phase's agent identity

## Overview

Supply the N3 phase-entry agent fact from the actual executing interceptor.
Repeated phase identifiers can have different agent overrides, so looking up the
first matching workflow declaration would report the wrong agent.

## Layer and dependencies

Workflow lifecycle application projection, based on main. Runner integration #2050
is merged; the extracted callbacks are now used by production execution.

## Changes and compatibility

- Pass the executing interceptor's agent identity to the phase-start constructor.
- Give native OPSV and done phases explicit `:agent/none` event declarations.
- Keep their runtime agent value nil; no fake agent is dispatched or registered.
- Prefer an explicitly configured agent over the native event declaration.
- Prefer the executing configuration's phase over a stale legacy top-level alias,
  keeping phase and agent facts aligned; retain the legacy alias when config is absent.
- Keep missing or unknown agent configuration missing; publication admission rejects it.
- Preserve the existing three-argument publisher and legacy event payload.
- Name the done phase's error callback and apply inferred strata to touched files.
- Extract heartbeat startup so the callback no longer nests conditionals.

## Verification

Cover repeated phase identifiers, native agentless declarations, configured-agent
precedence, missing-agent refusal, durable reopen, and legacy compatibility.
Run relevant product consumers serially and verify the rebuilt CLI package outside
the repository. Run normal hooks and the postcommit pinned standards audit.

All three product suites for workflow, phase, and phase-opsv passed (2m11s).
The isolated rebuilt CLI package passed 14 tests and 60 assertions, including
callback ordering and native configuration. Lint and inferred strata are clean.

After #2050 merged, added a runner-level entry test using a real journal and the
actual phase loop, callbacks, and publisher. Only external environment/checkpoint
effects and unrelated lifecycle emissions are stubbed. Shared journal fixtures
avoid duplicating ownership and reopen setup across the acceptance tests.
The refreshed three-product workflow suites passed (1m30s). The composed CLI
package passed 15 tests and 68 assertions. Coverage includes actual runner entry and the
executing configuration's phase/agent precedence over stale legacy fields.

## Scope boundary

This supplies authoritative phase-entry facts. It does not yet make callbacks
fail execution when publication fails, migrate other lifecycle facts, or change
the default host journal. Those remain separate N7/N3 integration work.
