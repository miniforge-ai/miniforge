<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve critical environment-release failures

## Scope and dependencies

Main-based workflow boundary change after shared cleanup #2079, dependent on
cleanup sequencing #2082 before opening/merge. Environment
release must preserve fatal and interrupted causes instead of swallowing them
as ordinary warnings. Keep nil-safe and best-effort ordinary failure behavior.
Separate environment records and persistence notifications so the touched runner
namespace has at most three inferred strata, without flattening its call graph.
The extracted revision lookup and persistence-publication boundaries likewise
preserve critical causes while retaining ordinary best-effort behavior. The
persistence log uses the developer-message catalog, and acquisition test ports
use named factories through the executor interface rather than duplicated maps.

Partial acquisition rollback is a distinct follow-up; this PR does not claim to
repair acquisition or change durable publication contracts.

## Verification and deployment

Add direct public release tests for nil, ordinary, fatal, and interrupted failures.
Retain record and persistence regression coverage, run consuming projects serially,
rebuild and test the isolated CLI. Apply the adversarial standards pass and normal
size limits, signed hooks, exact-head review and all CI gates. No wire changes;
deploy through the normal package after verification.

The initial three-consumer workflow run passes (1m27s). The fresh CLI is
39,226,265 bytes; isolated regression tests pass ten tests/30 assertions.
The standards scan is clean. Split normal commits keep helpers, test fixtures,
and runner wiring independently reviewable; no size exception is required.
