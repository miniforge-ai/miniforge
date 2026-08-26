<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: close three leaks in keys and metadata

## Overview

Redaction (N3 §8.1) shipped in #1842 covering values. This closes the
positions it did not reach: a secret held in a map key, in a keyword's
namespace, or in metadata.

## Motivation

PR #1842 reasoned that "map keys are not redacted: a key names a field,
and losing the name would hide that the field existed at all." That holds for a
key that *names* something and fails for a key that *is* data — a map keyed
by session token carries the secret in the key and nowhere else.

Verified against `main` before writing the fix, publishing through the real
stream and isolating each position:

| position | leaked on main |
|---|---|
| free text | no |
| secret-named key | no |
| key name | **yes** |
| key namespace | **yes** |
| metadata | **yes** |

## Changes in Detail

**Keys.** `match/redact-key` applies the value-shape rules to a key, and
only those — the key-*name* rule still does not apply, since replacing the
whole of `:password` would destroy the field it names. Shape is what
separates a field name from data: no field name looks like an AWS access
key. Both a keyword's namespace and its name are covered.

A redacted keyword or symbol returns as a string. `(keyword "[REDACTED]")`
prints as `:[REDACTED]`, which `edn/read-string` rejects, and N3 §4.3 makes
every event durable — an unreadable key corrupts the log.

**Metadata** is walked like any other value. `pr-str` drops it, so a
serializing sink never sees it, but the in-memory log and every in-process
subscriber hold the object itself.

**Collisions.** Two secrets redact to the same marker, so a map keyed by
both collapsed to one entry, silently dropping a value. `free-key` appends
a counter, growing each collection kind in its own way so the type survives.

**Config loading** moved to `config/load-config-resource`. That namespace
owns the resource boundary, so its throw is a boundary throw rather than an
`ex-info` in component code (dewey 005), and its message is localized
(dewey 050).

## Testing Plan

- Container matrix, 16x16 nestings, asserting the secret is absent and the
  marker present. Checks every reachable string — keys and metadata
  included — rather than `pr-str`, which prints a `PersistentQueue` as
  `#object[...]` and drops metadata, blind in exactly the places two of
  these defects were.
- Integration test through `publish!` covering all five positions, since
  that is the boundary §8.1 governs.
- EDN round-trip test: every redacted event still reads back.
- `boundary-cases-test` pins where the guarantee ends (see below).
- Each fix verified by mutation, not by passing:

| mutation | failures |
|---|---|
| remove key redaction | 51 |
| remove metadata walking | 50 |
| compare keys without metadata | 2 |
| no namespace redaction | 33 |
| `free-key` stringifies everything | 1 |
| `=` instead of `identical?` | 63 |

## Deployment Plan

Ships with main. No migration: redaction is applied at construction, so
existing durable events are unaffected and new ones are covered from the
first publish after deploy.

## Performance Impact

A small event goes from 9us to 20us — the value patterns now run over keys
as well, at roughly 1.2us per key. A 200-key map is 239us.

Combining the value patterns into one alternation was measured and rejected:
~45% *slower*, because Java applies a literal-prefix optimisation per
pattern and loses it once they are combined.

## Security Considerations

The whole PR is one. Worth naming where the guarantee ends: a `java.util`
collection, an array, and an atom are not walked. That follows from the
design — N3 §4.3 makes events durable, so a conformant event holds only what
survives `pr-str`/`edn/read-string`, which none of those do. Documented and
pinned rather than fixed: enumerating more container types is what produced
four of the defects here.

## Related Issues/PRs

- #1842 — redaction at event construction (the values half)
- #1843 — `collector.clj` split, prerequisite for #1844
- #1844 — bundle-side redaction (N6.SD.4)

## Checklist

- [x] Leak positions verified on `main` before fixing
- [x] Every fix mutation-tested
- [x] Coverage boundary documented and pinned
- [x] `poly check`, kondo, stratum lint clean
- [x] Review comments addressed
