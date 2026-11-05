<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(policy-pack): report nested-block frontmatter instead of silently compiling defaults

## Overview

The MDC frontmatter grammar is flat and silent about it. Three authoring shapes
produce a compiled rule built from frontmatter the author did not write, with
`success? true` and `warnings []`:

1. a nested YAML mapping (`detection:` over indented `mode:`)
2. a comment as a key's only value over a list (`globs: # note`)
3. a colon-bearing comment inside a list

This PR makes the compiler report all three, naming what the rule actually
became.

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

### `commented-list-keys` — the second discarded shape

`globs: # the shell ones` over a `- item` list loses the list entirely.
`parse-kv-line` sees a non-blank value, so it clears `current-key` and every
`- item` line after it is dropped. The rule compiles to
`:file-globs ["# the shell ones"]` — a glob that matches nothing, on a rule that
looks scoped. This one is worse than the nested-mapping case: that at least
leaves a visible `{:type :custom}`, while this produces a plausible rule with a
nonsense scope.

A **bare** `key:` over a list is the one multi-line form the grammar does
support and is never reported. Only the commented form loses data, and a test
asserts the two detectors never both claim the same line.

Indentation is not part of the list form. `process-frontmatter-line` trims every
line before testing for `"- "`, so a flush-left list is supported — the existing
`parse-mdc-test` covers it — and loses its items to a commented parent exactly
the same way. The list-item pattern therefore allows zero indentation. The
nested-mapping pattern still requires it, because an unindented `key: value` is
a legitimate top-level key rather than a nested child; both directions are
pinned by tests.

`parse-kv-line` itself is left alone. Correct YAML comment handling means
knowing whether the `#` is inside quotes, which is a grammar change, and any
real value containing a `#` would be at risk. Reporting the loss costs nothing
and risks nothing.

### `interrupted-list-keys` — the third discarded shape

A comment line containing a colon, inside a list, is destructive.
`process-frontmatter-line` trims the line, sees the colon, and routes it to
`parse-kv-line`, which stores it as a key named `# note` and clears
`current-key`. Every `- item` after it is dropped.

Position decides how much is lost, and the less obvious position is the worse
one:

```yaml
globs:
  - "*.sh"
  # note: more follow
  - "bb.edn"
```

compiles to `:file-globs ["*.sh"]`. The list is **truncated**, not emptied — the
rule keeps the patterns above the comment and silently loses the rest. A rule
that still has globs looks fine.

Only items **below** the comment are lost, which is what the condition tests: a
list item somewhere after the first colon comment, not merely both in the same
block. A colon comment trailing the last item discards nothing — the parser has
already collected them, and the comment only leaves an ignored junk key — so
that case is not reported.

Narrowly scoped otherwise too: a comment without a colon is harmless and not
reported; a colon-bearing comment with no list under the key discards nothing
and is not reported; one outside any list leaves a junk key but loses no data
and is not reported; and the next top-level key ends the block legitimately
rather than counting as an interruption. The commented-parent form belongs to
`commented-list-keys`, and a test asserts the two do not both claim the same
line.

This is where the detectors stop agreeing with the parser on purpose. For the
nested-mapping check, comment lines are skipped when looking for a parent's
first child — the parser does not skip a colon-bearing one, but the warning
fires either way, so the asymmetry costs nothing there. Here the parser's
behaviour *is* the finding, so it is matched exactly.

### `mdc_compiler.clj`

- `parse-mdc` additionally returns `:frontmatter-raw`. The parse is lossy in the
  one direction that matters — a nested block leaves no trace in the parsed map
  — so the source text is the only place the condition is still visible.
- `mdc->rule` emits one warning per finding, naming the file, the key, and for a
  nested block the values the rule **actually** compiled with
  (`detection :custom`, `enforcement :audit`). Reporting the outcome rather than
  just the mistake is the point: the author's intent is in the file, the silent
  result is not.
- **Both detectors run outside the `try`.** One of the shapes they report — an
  indented *dotted* child, `detection:` over an indented `detection.mode:` —
  does not fall back at all: `group-dotted-keys` throws `Key must be integer`
  trying to `assoc` onto the parent's `[]`. That names nothing an author can
  act on, so the findings have to survive into the failure result. They do, and
  a test pins it.
- `compile-standards-pack` folds per-rule `:warnings` from **every** result,
  failures included, into the pack result. That collection is the only thing
  `bb standards:pack` prints, so a warning that does not reach it does not
  exist — and the result that most needs one is the one that threw.

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
| `mdc-compiler-test` namespace | 34 tests, 229 assertions, 0 failures |
| Full `policy-pack` test suite | 302 tests, 2756 assertions, 0 failures |
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
- `commented-list-keys-test` — the commented form reports, indented and
  flush-left; the bare form does not, in either style; a commented key with a
  *mapping* child belongs to the other detector and not this one; a commented
  key followed by another top-level key is not a list; no children, empty and
  nil.
- `mdc->rule-commented-list-warning-test` — end to end: the rule compiles,
  `:file-globs` is `["# the shell ones"]`, and the warning names the key and
  says the list was discarded.
- `interrupted-list-keys-test` — the two lossy positions (whole list, and
  truncated); flush-left; an item after the *second* colon comment, since the
  window opens at the first; and seven non-reports: a colon comment trailing the
  last item (twice, with and without a blank line), a comment without a colon, a
  clean list, a colon comment with no list, one outside any list, and the
  commented-parent form that belongs to the other detector.
- `mdc->rule-interrupted-list-warning-test` — end to end: `:file-globs` is
  `["*.sh"]` and the warning names both the key and what survived, so the
  truncation is visible rather than implied.
- `mdc->rule-warnings-survive-compile-failure-test` — the indented-dotted shape
  returns `success? false` with `Key must be integer` **and** a warning naming
  `detection` and tying itself to that exception.
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
