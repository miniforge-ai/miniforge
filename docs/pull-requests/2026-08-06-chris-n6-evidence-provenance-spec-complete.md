<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N6 evidence & provenance spec completion (0.7.2 → 0.8.0-draft)

## Overview

Supplies the sealing mechanism behind the immutability N6 already claimed, the
event-linkage and gate-evidence schemas other specs already depend on, and
conformance requirement IDs. Adds Annex A recording implementation divergence.

## Motivation

**Immutability was asserted, never mechanised.** §1.1 principle 3 says bundles
MUST NOT be modified after creation. §7.3 says "prevent tampering — immutable
storage, content hashing". §9.1 requires implementations to *validate*
immutability. None of it said how: no hash over the bundle, no canonical
serialization, no verification procedure, no rule about what to do when
verification fails. An audit record whose integrity cannot be checked is not
audit evidence. The implementation had meanwhile invented an
`:evidence/content-hash` field that the spec never mentioned.

**N4 §5.5 places four obligations on N6 that the bundle did not carry.** A gate
result MUST be reproducible from its evidence: the gate binding and resolved
rule set, every violation including waived ones, each pack's content hash, and
any waiver with its justification. The bundle schema recorded none of these. No
gate result in the system is currently reproducible from its evidence.

**Two redaction markers for one concept.** N3 §8.2 specifies `"[REDACTED]"`.
N6 §7.2 specified `[REDACTED:<type>]`. An auditor grepping for redactions finds
only some of them. §7.2 also permitted "redact **or** flag", where N3 §8.1 is a
MUST NOT on the value existing at all.

**The compliance key list disagreed with itself.** §2.1's bundle structure and
§7.1's required set diverged in both directions: §7.1 required
`:compliance/created-at` and `:compliance/retention-policy`, which §2.1 did not
list; §2.1 had `:compliance/auditor-notes`, which §7.1 did not.

**§5.1 required linking to the event stream** "via event sequence ranges" with
no schema for the link — and since N3 now sequences per scope across six
scopes, a bare range is ambiguous.

**§8 restated N5's interface contracts.** "Implementations MUST provide CLI
command `miniforge evidence show`" duplicates N5 §2.3.5; the TUI list
duplicates N5 §3.2.3. Standard 020: reference, don't duplicate.

## Changes in Detail

### New normative sections

- **§2.14 Bundle sealing and integrity.** Seal at creation after scanning and
  redaction; SHA-256 over a canonical serialization excluding the hash and
  signature; `validate-bundle` recomputes and compares; a mismatch is reported
  as tampered, never repaired or re-sealed; corrections are issued as a new
  bundle referencing the prior one, never an in-place edit. The canonical-form
  requirement is the part that makes the hash meaningful — without it two
  readers hash the same bundle differently.
- **§2.12 Event stream linkage.** `:evidence/event-links` carrying N3 scope
  type, scope id, and an inclusive sequence range, with one link per scope when
  work spans scopes, and a prohibition on expiring events inside a retained
  bundle's range.
- **§2.13 Gate execution evidence.** Discharges N4 §5.5. Records binding,
  per-pack exact resolved version and content hash, all violations including
  waived, and waivers. States the three rules implementations get wrong: a
  version range is not a resolved version; waived violations stay in the list;
  a waiver without a reason is invalid, not merely incomplete.
- **§7.4 Retention.** Bundles inherit N3 §4.3.1's `:audit` floor. A bundle's
  retention is a floor on its cited events and referenced artifacts — a bundle
  whose artifacts have been collected fails §9.1 and cannot satisfy §7.3's
  chain of custody.
- **§9.4–§9.5 Conformance requirement IDs and test obligations.** `N6.EB.*`,
  `N6.PR.*`, `N6.EL.*`, `N6.GE.*`, `N6.SD.*`, `N6.PS.*`, plus seven test
  obligations.

### Contract fixes

- §7.2 now inherits N3 §8 whole — excluded values, `"[REDACTED]"` marker,
  truncation — instead of defining a variant. "Redact or flag" replaced by
  redact-then-flag. Added: a bundle MUST NOT seal until scanning and redaction
  complete, since sealing first would either break the seal or preserve the
  secret inside a tamper-evident record.
- §2.1 and §7.1 reconciled into one key list, with a note that a §7.1 key
  missing from §2.1 is a defect in this spec rather than a choice.
- §8.1–§8.2 replaced by a statement of what any presenting surface MUST be able
  to show, referencing N5 for the surfaces themselves. Adds seal status to that
  list: a bundle whose hash does not verify MUST be shown as tampered.
- SOCII → SOC 2 (§1.1, §7.3).

### Annex A (informative)

- **Partially implemented** — `collector.clj:619` sets `:evidence/content-hash`,
  a field the spec had never defined. Missing: canonical serialization,
  `:evidence/sealed-at`, and any recompute-and-compare in `validate-bundle`.
- **Specified, not implemented** — `scanner.clj` detects email, SSN, and AWS
  keys but performs no redaction; `[REDACTED]` appears nowhere in the
  component, so detect-and-flag leaves the secret in the bundle. No gate
  evidence, no event links, no retention floor.
- **Structural** — nothing prevents a sealed bundle from being modified in
  place.

### SPEC_INDEX

N6 entry updated; index 0.12.0 → 0.13.0-draft with an amendment-log entry.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- Code blocks verified brace-balanced.
- No duplicate section numbers; top-level sections ascending 1–13.
- Internal `§N.N` references resolved against defined headings; the five
  apparent misses are cross-spec references to N3, N4, and N5, verified by hand.
- Verified the withdrawn `[REDACTED:<type>]` marker survives only in the two
  places that document its withdrawal.

Self-review caught one defect mid-edit: I first numbered the new subsections
§2.4–§2.6, which collided with the existing Semantic Validation, Policy Check,
and Outcome Evidence sections. Renumbered to §2.12–§2.14 and relocated to the
end of §2.

§9.5's test obligations describe tests that do not exist yet — follow-on work.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

Tracked by Annex A. The redaction gap is the one worth doing first: the scanner
already detects secrets and then stores them.

1. Redact on detection in `evidence-bundle/scanner.clj`, and widen the pattern
   set to private keys, connection strings, and payment card numbers.
2. Implement sealing: canonical serialization, `:evidence/sealed-at`, and
   recompute-and-compare in `validate-bundle`.
3. Record gate execution evidence so gate results become reproducible per
   N4 §5.5.
4. Populate `:evidence/event-links`.
5. Enforce a retention floor for bundles and their artifacts.

## Related Issues/PRs

- Follows: [#1641](https://github.com/miniforge-ai/miniforge/pull/1641) (N3),
  [#1658](https://github.com/miniforge-ai/miniforge/pull/1658) (N4),
  [#1668](https://github.com/miniforge-ai/miniforge/pull/1668) (N5)
- Depends on: N3 §8 (redaction), N3 §4.3 (retention classes), N3 §2.3 (scopes),
  N4 §5.5 (gate evidence obligations), N4 §6.3.1 (waiver)
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Internal contradictions identified and fixed
- [x] Cross-spec obligations from N3/N4/N5 discharged
- [x] New sections use RFC 2119 keywords (020)
- [x] Annex A marked informative — carries no new requirements
- [x] No spec content extracted from implementation code (020 critical rule)
- [x] N5's interface contracts referenced, not duplicated (020)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] Code blocks brace-balanced; no duplicate section numbers
- [x] Internal section references resolve
- [x] SPEC_INDEX updated (020: index is authoritative)
- [x] PR doc created (721)
