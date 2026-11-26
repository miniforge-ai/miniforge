<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stop compatibility checks when classpath resolution fails

## Overview

Based on main; no dependency on pending storage qualification. Abort the
GraalVM/Babashka task when its Clojure classpath resolver fails, preserving
the process failure instead of running compatibility checks on an empty path.

## Motivation

The Test job for PR #2555 received Maven Central HTTP 429 responses. The
compatibility task discarded the resolver exit status and stderr, then reported
missing workflow/operator namespaces. Those were not runtime incompatibilities.

## Changes in detail

Use the checked process boundary for classpath resolution. Keep the sanitized
environment, development alias and successful compatibility invocation unchanged.
This does not retry downloads, suppress test failures or weaken any CI gate.

## Testing plan

Cover failed resolution without invoking the compatibility runner and successful
resolution with the expected classpath and sanitized environment. Exercise the
real Babashka compatibility task with normal signed commit and review gates.

The regression and existing launcher packet passes 10 tests / 60 assertions on
Java 21 and native Babashka. A deliberate unchecked-resolver control produces
three expected failures and zero errors on each. The real compatibility task
passes 17 tests / 791 assertions. Three exact namespace examples pass in both
runtimes. Fixtures use actual completed process results, preserving exit code,
stderr, classpath spaces and the sanitizer's environment.

## Deployment plan

Developer tooling only. No provider, credential or infrastructure actions.

## Related issues/PRs

Discovered while diagnosing the dependency-download failure in PR #2555.

## Checklist

- [x] Regression tests, real task and owner standards/adversarial pass.
- [ ] Current-head CI, automatic/full review and all merge gates.
