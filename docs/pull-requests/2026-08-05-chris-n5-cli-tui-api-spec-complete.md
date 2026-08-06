<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N5 CLI/TUI/API spec completion (0.4.0 → 0.5.0-draft)

## Overview

Takes N5 (Interface Standard: CLI/TUI/API) from a partially-specified document
to a complete one. Adds the localization contract the spec never had, the
output and stability contracts a script needs to depend on the CLI, and
reconciles the override path with N4 as revised.

Adds Annex A, an informative record of where the implementation diverges.

## Motivation

**No localization contract.** `foundations/localization` (dewey 050) is
`alwaysApply: true` with `enforcement.action: hard-halt`: every emitted prose
string flows through a catalog. N5 defines the CLI, the TUI, and the API — the
largest producer of user-facing prose in the system — and said nothing about
it. The scaffolding exists in the tree (`bases/cli`, `components/tui-views`,
and `components/web-dashboard` each carry an `en-US.edn`), but the contract it
implements was unwritten, so nothing said which strings must route through it,
which must not, or what happens on a missing key.

**The CLI was not usable as a contract.** §8.1 gave four bullets: results to
stdout, logs to stderr, zero on success. Nothing said what a non-zero code
means, so a caller could not distinguish "the tool broke" from "the tool
worked and said no" — which for a policy-gated system is the distinction that
matters. Nothing said whether `--json` keys are stable, whether they vary by
locale, or how an error is structured. Nothing said how a command may change.

**The console contradicted itself about chat.** §5.2 listed "An AI chat
interface — No conversational interaction needed" among the things the console
is NOT. §3.2.8 and §3.2.9 both said `c` MUST open a chat pane. The same key was
also Cancel in §3.3 and in §3.2.2's footer.

**The override path contradicted N4.** §6.1.2's worked example offers `[o]
Override gate` for a CRITICAL violation. N4 §6.3.1 now forbids overriding
`:critical` and `:high` through that path — those need N8 multi-party
approval. §6.2 also defined a bespoke `:override/*` record competing with the
Waiver in N5's own delta spec.

**The spec mandated implementation namespaces.** §3.2.8 required computing
readiness "using `pr-train/explain-readiness`" and policy "using
`policy-pack/evaluate-external-pr`". Standard 020 is explicit: specs define
what MUST be built, not what happens to exist. Those are N9 §2.2 and N4 §5.1.7
contracts.

## Changes in Detail

### New normative sections

- **§9 Localization.** No authored prose at emit sites; two catalogs chosen by
  destination (user vs system), not by whether the string deserves
  translating; an explicit list of what is *not* prose — command names, flag
  names, key bindings, enum values, `--json` keys; locale resolution order with
  `en-US` fallback and a prohibition on rendering a raw key to a user.
- **§8.4 CLI output contract.** Stream separation (a progress spinner on stdout
  breaks every pipeline consuming it); a seven-entry exit-code taxonomy where
  policy refusal is 4 and command failure is 1; `--json` stability rules
  including locale invariance; a stable error `code` with a catalog-sourced
  `message`.
- **§8.5 Command stability.** What is MINOR versus MAJOR for a CLI surface, and
  deprecation on stderr so it never corrupts piped output.
- **§8.6 Terminal capability degradation.** `NO_COLOR`, `TERM=dumb`, non-TTY,
  no-Unicode, and sub-80-column handling. Color is never the sole carrier of
  meaning — which is why §3.2.1's status glyphs are normative rather than
  decorative.
- **§7.3–§7.4 Configuration precedence and validation.** Flag → env → file →
  default, resolved uniformly; invalid config fails with exit 3 naming the key;
  an *unknown* key warns rather than fails, so a config written for a newer
  version stays usable; secrets rejected inline.
- **§8.7–§8.8 Conformance requirement IDs and test obligations.**
  `N5.CLI.*`, `N5.TUI.*`, `N5.API.*`, `N5.CFG.*`, `N5.L10N.*`, `N5.OV.*`, plus
  eight named test obligations.

### Contract fixes

- §5.2 reworded to distinguish chat-*first* operation (which the console is
  not) from conversational handoff (which §3.2.8 and §3.2.9 define). Chat moved
  from `c` to `C`; `c` stays Cancel throughout.
- §2.2's namespace table gained `listener`, `agent`, and `gate` — §2.3.3
  already defined commands in all three. §8.1's required-namespace list
  resynced with §2.2 (it omitted `etl` and `pack`).
- §2.3.3's control commands moved out of the fleet namespace they were nested
  under; they span five namespaces and are grouped by the N8 contract.
- §6.1.2 no longer offers override for a CRITICAL violation, and states that
  override availability follows N4 §6.3.1 rather than being a UI choice.
- §6.2 replaced the bespoke `:override/*` record with the Waiver of
  N5-delta-supervisory-control-plane §3.1, and forbids rendering a waived gate
  as passing anywhere.
- §4.2.2 aligned with N3 §5.3's `/api/streams/:scope-type/:scope-id`, keeping
  the workflow path as an alias, and states that N3 owns the wire contract.
- §4.3's "SHOULD require authentication" withdrawn — it read as optional for a
  network-exposed console, which N3 §5.3.2 forbids.
- §3.2.8–§3.2.9 now reference N9 §2.2, N9 §1.6, and N4 §5.1.7 instead of
  naming implementation namespaces.
- §13 References gained N5's four delta specs, which the base spec did not
  acknowledge.

### Annex A (informative)

Standard 020 forbids extracting specs from code, so nothing here relaxes the
contract.

- **Specified, not implemented** — four of eleven namespaces (`pack`,
  `listener`, `agent`, `gate`) have no command surface; `pack` alone specifies
  eleven commands. No `NO_COLOR` handling anywhere in the tree. No exit-code
  taxonomy. No config precedence or schema validation. No deprecation
  mechanism.
- **Implemented, matching** — localization scaffolding exists and §9 writes
  down the contract it implements. Coverage is unaudited: §9.1 requires *every*
  emitted prose string to route through a catalog and nothing verifies that.
- **Structural** — `messages.clj` derives locale from the host language only;
  §9.4 puts `--locale` and `MINIFORGE_LOCALE` ahead of it and neither is
  consulted.

### SPEC_INDEX

N5 entry updated; index 0.11.0 → 0.12.0-draft with an amendment-log entry.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- Code blocks verified brace-balanced.
- Every internal `§N.N` reference resolved against defined headings; the
  checker covers intra-document references only, and the one apparent miss is a
  cross-spec reference to N9 §1.6, verified by hand.
- No duplicate section numbers; top-level sections ascending 1–13.
- Verified zero remaining `pr-train/*` or `policy-pack/evaluate-*` mandates and
  zero remaining `c` key chat bindings.
- Inbound `N5 §x.y` references enumerated before renumbering: they reach §6.2
  at the deepest, so inserting §9 and pushing the former §9–§12 to §10–§13
  breaks nothing.

§8.8's test obligations describe tests that do not exist yet — follow-on work.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

Tracked by Annex A:

1. Implement the `pack`, `listener`, `agent`, and `gate` CLI namespaces.
2. Add `NO_COLOR` / `TERM=dumb` / non-TTY / narrow-terminal degradation.
3. Adopt the §8.4.2 exit-code taxonomy, separating policy refusal from failure.
4. Add config precedence resolution and schema validation.
5. Consult `--locale` and `MINIFORGE_LOCALE` ahead of the host language.
6. Audit catalog coverage — verify no emitted prose originates at an emit site.

## Related Issues/PRs

- Amends: N5 0.4.0-draft (#407-era pack namespace extension)
- Follows: [#1641](https://github.com/miniforge-ai/miniforge/pull/1641) (N3),
  [#1658](https://github.com/miniforge-ai/miniforge/pull/1658) (N4) — same pattern
- Depends on: N4 §6.3.1 and N3 §5.3 as revised by those PRs
- Governed by: `standards/miniforge/foundations/specification-standards` (020),
  `standards/miniforge/foundations/localization` (050)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Internal contradictions identified and fixed
- [x] New sections use RFC 2119 keywords (020)
- [x] Annex A marked informative — carries no new requirements
- [x] No spec content extracted from implementation code (020 critical rule)
- [x] Implementation-namespace mandates removed in favour of spec references (020)
- [x] Localization contract stated (050)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] Internal section references resolve
- [x] Inbound references checked before renumbering
- [x] SPEC_INDEX updated (020: index is authoritative)
- [x] PR doc created (721)
