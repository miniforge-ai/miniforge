<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(policy-pack): report nested-block frontmatter instead of silently compiling defaults

## Overview

The MDC frontmatter grammar is flat. A rule author who writes a nested YAML
block gets no error, no warning, and a compiled rule built from frontmatter they
did not write. This PR makes the compiler say so.

## Motivation

`parse-frontmatter` trims each line before deciding what it is, so an indented
`mode: positive` is indistinguishable from a top-level one. Given:

```yaml
detection:
  mode: positive
  pattern: '^#!.*\b(bash|sh|zsh)\b'
enforcement:
  action: hard-halt
```

the children land as separate top-level keys `mode`, `pattern`, `action`, and
`detection` / `enforcement` are left holding `[]`. `group-dotted-keys` then finds
no `detection.*` or `enforcement.*` to group, so `build-detection-config`
returns `{:type :custom}` and the enforcement action falls back to the
`alwaysApply` default. The rule compiles with `success? true`, `failed-count 0`
and `warnings []`.

The net effect: **a rule declaring `hard-halt` becomes a non-blocking `:audit`
gate with no detection, and nothing reports it.** The rule is still injected as
knowledge, so it looks present and enforced while acting on nothing.

This was not hypothetical. `workflows/bb-over-shell` (dewey 740) was written in
nested style and was inert for its whole life at the currently pinned standards
SHA — two blocks, both lost. It is dotted style on standards `main`, which is
the only reason the discrepancy surfaced at all: the rule became live on a pin
bump, not on a rule change.

## Changes in Detail

### `mdc_compiler/frontmatter.clj`

`nested-block-keys` — the keys written as a nested mapping, in file order. A key
qualifies when it has no inline value and its next line that is neither blank
nor comment-only is an indented `child:`. Three things deliberately do not
qualify:

- `- item` children — the one multi-line form the grammar does support, so
  `globs:` and friends are never reported, including `globs: # note` followed by
  a list.
- a blank-valued key with no children at all.
- a quoted value that merely begins with `#`.

A trailing `# comment` on the parent counts as **no value**, and comment-only
lines between parent and child are skipped at any indentation. Both forms are
real: the `## Core Structure` template in `meta/rule-format` writes frontmatter
comments, so `detection: # shebang checks` is authoring the format as
documented. Without this, the one mis-authoring most likely to carry an
explanatory comment is the one the detector misses.

`parse-kv-line` disagrees with the detector here — it captures `# comment` as
the value rather than treating it as a comment. That is a pre-existing grammar
quirk and is left alone: a parent whose value is a comment string resolves no
`key.*` entries either way, so parser and detector land on the same fallback.
Changing `parse-kv-line` would alter any real value containing a `#`.

It lives beside the grammar it guards rather than in the caller: the loss it
detects is a property of `process-frontmatter-line`, and a reader changing that
function needs this in view.

### `mdc_compiler.clj`

- `parse-mdc` additionally returns `:frontmatter-raw`. The parse is lossy in the
  one direction that matters — a nested block leaves no trace in the parsed map
  — so the source text is the only place the condition is still visible.
- `mdc->rule` emits one warning per nested key, naming the file, the key, and
  the values the rule **actually** compiled with (`detection :custom`,
  `enforcement :audit`). Reporting the outcome rather than just the mistake is
  the point: the author's intent is in the file, the silent result is not.
- `compile-standards-pack` folds per-rule `:warnings` into the pack result. That
  collection is the only thing `bb standards:pack` prints, so a warning that
  does not reach it does not exist.

### Warn, not fail

A failed compile drops the rule from the pack entirely, taking its
`:rule/knowledge-content` with it — trading a silent downgrade for a silent
disappearance. The rule is kept and the loss is reported. There is a test
asserting `failed-count 0` on a nested-block rule so this choice cannot be
reversed by accident.

The weakness of a warning is that nothing fails on it: `compile_standards.clj`
prints warnings and exits 0. Promoting these to a gate failure is a follow-on,
and needs a CI compile check in the standards repo (which has none today) to be
worth anything.

### Localization

The two warnings already in `compile-standards-pack` were raw string literals,
which rule 050 forbids. All three now go through
`config/policy-pack/messages/system.edn` — system locale, since they reach the
`bb standards:pack` output and the pack's `:warnings`, never a product surface.

## Testing Plan

| Check | Result |
|---|---|
| `mdc-compiler-test` namespace | 29 tests, 180 assertions, 0 failures |
| Full `policy-pack` test suite | 297 tests, 2710 assertions, 0 failures |
| `bb lint:clj` | 0 errors, 0 warnings |
| `bb lint:stratum` | clean (no rewrites) |
| `bb poly:check` | OK |
| `bb fmt:md` | clean |

New tests:

- `nested-block-keys-test` — nested blocks in file order; `- item` lists and
  dotted keys report nothing; blank-valued key with no children reports nothing;
  a blank line between parent and child does not hide the nesting; empty and nil
  input. Comment cases: a trailing comment on the parent (space- and
  tab-separated), comment-only lines between parent and child at both
  indentations, a commented parent with a list child (must stay a non-report), a
  quoted value beginning with `#`, and a commented parent with no children. All
  three comment-positive cases were checked against the pre-fix detector and
  return `[]` there, so the tests are not vacuous.
- `mdc->rule-nested-block-frontmatter-warning-test` — the silent-downgrade case
  end to end: `hard-halt` + nested style still compiles, lands on
  `:custom` / `:audit`, and produces two warnings naming the file, the key and
  the compiled values. The dotted-style equivalent is blocking and carries no
  `:warnings` key at all.
- `compile-standards-pack-nested-block-warning-test` — the warning reaches the
  pack result, and `failed-count` stays 0.

End-to-end against real trees, compiling with
`ai.miniforge.policy-pack.interface/compile-standards-pack`:

| Standards tree | Warnings |
|---|---|
| Current pin `72f451d` | 2 — `bb-over-shell.mdc`, keys `detection` and `enforcement` |
| Standards `main` `ee05f44` | none |

Both still report `success? true`, 59/59 compiled, 0 failed.

## Deployment Plan

Independent of the submodule pin and of
miniforge-ai/miniforge-standards#130. Merging it before the re-pin is the useful
order: it is the check that would have caught the `bb-over-shell` discrepancy as
a warning at the current pin rather than as a blocked gate after the bump.

## Related Issues/PRs

- miniforge-ai/miniforge-standards#130 — adds `excludeGlobs`, needed before the
  submodule can be re-pinned. Same root cause (a declaration the frontmatter
  could not carry), different half.
- miniforge-ai/miniforge#2020 — compliance-scanner remediation strategy. No file
  overlap.

## Breaking Changes

None. `parse-mdc` gains a key; `mdc->rule` gains `:warnings` only when a nested
block is present. No existing rule's compiled output changes.

## Checklist

- [x] Detector reports nested mappings and nothing else (lists, dotted keys, bare keys)
- [x] Warning names the file, the key, and what the rule actually compiled to
- [x] Per-rule warnings reach the pack result that `bb standards:pack` prints
- [x] Rule is kept, not dropped — asserted by test
- [x] Pre-existing raw-literal warnings moved into the system catalog (rule 050)
- [x] Verified against both the current pin and standards `main`
- [x] Full policy-pack suite green
