<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: redact bundles before sealing them (N6.SD.4)

Historical record. Merged as PR #1844.

## Overview

Evidence bundles are redacted between the sensitive-data scan and the
content hash, so a secret is removed from the bundle rather than merely
flagged in its metadata.

## Motivation

N6.SD.4 requires redaction **on detection**. Flagging alone does not satisfy
N3 §8.1, which is a MUST NOT on the content rather than a labelling
requirement. The scanner recorded that a bundle held a secret and then
stored the secret anyway — `[REDACTED]` appeared nowhere in the component.

## Changes in Detail

**Placement.** After the scan, so the compliance finding survives: that a
secret *was* present is the auditable fact, and redacting first would erase
the evidence of it. Before the hash, because N6 §7.2 seals only after the
substitution — hashing first would bind the bundle to a form that no longer
exists.

**Shared secret set.** N6.SD.3 requires the bundle to scan *independently*
of the stream, not to hold a narrower definition of "secret". The scanner's
three patterns name specific types worth reporting; an `:embedded-secret`
finding covers the rest of the N3 §8 set. It is a fallback rather than an
extra label, so one secret is not reported twice under two names.

**A documented asymmetry.** `bundle-text` is bounded by `*print-length*` and
`*print-level*`, so detection sees a truncated view while redaction walks the
whole structure. Findings are best-effort metadata; the redaction is the
security property.

## Testing Plan

Verified by mutation rather than by passing:

| mutation | failures |
|---|---|
| remove the redaction | 2 |
| hash before redacting instead of after | 1 |

Plus a test at depth 30 asserting the scan reports nothing and the secret is
removed anyway. 128 tests / 396 assertions.

## Related Issues/PRs

- PR #1842 — the stream half; supplies the shared pattern set
- PR #1843 — prerequisite `collector.clj` split

## Checklist

- [x] Redaction ordered after the scan and before the hash
- [x] Stream and bundle share one definition of "secret"
- [x] Detection/redaction asymmetry documented and pinned
- [x] Both orderings mutation-tested
