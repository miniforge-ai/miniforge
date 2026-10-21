<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve critical environment-release failures

## Scope and dependencies

Main-based workflow boundary change after merged shared cleanup #2079 and
cleanup sequencing #2082, aligned with activation-local progress ports #2083. Environment
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

Catalog section loading is covered through the public persistence path; the
rebuilt CLI (39,226,284 bytes) passes eleven isolated tests/42 assertions.

Tracing real executor release paths also found returned error results were
silent. The boundary now warns for those results and returns the original value;
the regression fixture uses the executor's real success/error constructors.
All three consumers pass again (1m27s); the rebuilt CLI is 39,226,360 bytes and
the isolated regression suite passes twelve tests/45 assertions.

After aligning with main, all three consumer suites pass again (1m27s).
The rebuilt CLI is 39,228,667 bytes.

The public-path adversarial pass found acquisition, restoration and persistence
handlers could swallow an interruption. All now apply the same critical-cause
policy before ordinary warning behavior. The new public-path regressions fail
against the prior packages and pass against the corrected CLI. Final consuming
project suites pass (1m29s); CLI39,228,684 passes fifteen isolated tests with
51 assertions. The whole PR remains within its normal budget (473 reportable).
