<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: retain owned cleanup diagnostics

## Overview

Preserve actionable native-resource cleanup failures alongside the original
operation failure. Based on main after #2560; #2559 and #2555 consume this fix.

## Motivation

A handshake I/O failure can become the primary exception while unconfirmed
termination is suppressed. Generic exception translation then loses the retained
scratch path. Separately, a failed close must not mask a fatal operation or
interruption during directory-pin ownership transfer.

## Changes in detail

Retain explicit unconfirmed-termination diagnostics before generic translation,
with the ordinary primary failure preserved as the cause. Fatal errors and
interruption retain their existing priority. Reuse the shared cleanup policy
for resource transfer without changing successful ownership semantics.

## Testing plan

Reproduce I/O failure followed by unconfirmed termination through the real
exception-to-anomaly boundary. Use actual disposable native processes and private
scratch. Verify primary/fatal/interruption priority and independent cleanup
failures, both Java 21 products, source Babashka and a fresh package, with fault
controls and safe examples. All local JVM work remains serialized.

The final native packet passes 34 tests / 144 assertions in each Java 21
product, source Babashka and a fresh package. The exact project integration
alias passes the existing 7-test / 60-assertion cleanup packet. Eight changed
namespace examples pass on Java 21 and Babashka, including the new 3-test /
24-assertion provenance and 3-test / 7-assertion ownership-priority packets.
Four new fault controls produce 6 / 4 / 2 / 6 expected failures and zero errors
in both runtimes; five existing cleanup controls remain sensitive with
4 / 5 / 1 / 1 / 2 failures and zero errors.

## Deployment plan

No live providers, infrastructure actions or new effect authority. Unconfirmed
termination remains a refusal with retained private scratch, not an escape.

## Related issues/PRs

PR #2559 review comment 4664809703; #2555 retained root lifetime; merged #2560.

## Checklist

- [x] Implementation, regressions and owner standards/adversarial pass.
- [ ] Current-head CI, automatic and full reviews, resolved threads and merge gates.
