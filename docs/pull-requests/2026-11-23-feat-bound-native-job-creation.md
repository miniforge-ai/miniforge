<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: bound native Job creation

## Overview

Use the deadline-bounded stdin transport from PR #2556 for create-only Kubernetes
Job admission. Preserve the exact server receipt and cancellation fences.

## Motivation

The existing HTTP timeout does not bound a local process stalled while reading
stdin. Encoding, native submission and receipt admission need one monotonic
deadline, without adopting or deleting an uncertain Job by reusable name.

## Changes in detail

Keep transport separate from receipt policy. Preserve the HTTP request flag,
create-only command and historical UID/resourceVersion. Reject failed input,
expired work and cancellation without retaining provider text. No retries.
Shared factories mirror complete native command replies and the actual Running
readiness result instead of duplicating partial fixture maps.

## Testing plan

Intercept every native creation and observation in unit/admission tests. Use
disposable local executable fixtures for actual stdin and stalled-process proof.
Check both JVM products, source Babashka and a fresh package. Add independent
negative controls and evaluate only safe, non-provider REPL examples.

Validation completed:

- 39 tests / 218 assertions in each of both explicit Java 21 product contexts,
  source Babashka, and a fresh class-stripped CLI package. Java checks exclude
  local compiled-class directories to validate cold source loading.
- Real UTF-8 input and four-MiB payloads reach a disposable executable intact.
  A non-reader is killed within the local deadline; a zero-exit valid receipt
  without accepted stdin is refused. Missing executables retain the sanitized
  I/O diagnostic without exposing their path.
- The actual project integration alias includes the shared executor fixture
  directory. Its classpath regression test keeps those fixtures off production
  builds; the native packet also runs with `-M:test:integration`, without a
  manually supplied test path.
- Five independent negative controls fail with zero errors on Java 21 and
  Babashka: post-receipt admission (2), native payload integrity (2), historical
  Job identity and downstream ownership (6), input completion (1), and native
  I/O classification (1).
- Nine exact safe REPL examples pass on Java 21 and Babashka.

Encoding consumes the original deadline; expiry and cancellation are checked
again after receipt parsing. Synchronous encoding/parsing is not preempted, and
an elapsed budget cannot admit the result. No provider was invoked.

## Deployment plan

No live provider actions. Stopping an owned local process cannot recall a request
already dispatched to the server. This does not implement immutable containment
or replace the remaining full-stop work.

## Related issues/PRs

Merged PR #2536 supplies create-only receipts, PR #2554 supplies Pod ownership
admission, and PR #2556 supplies deadline-bounded stdin transport.

## Checklist

- [x] Implementation, native proof and owner standards/adversarial review.
- [ ] Current-head CI, automatic and full reviews, all threads resolved.
