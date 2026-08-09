<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: buzzword-bingo text preparation strata

## Overview

First slice of a prose scanner that counts marketing, corporate and
generated-prose tells in a document, so a caller can decide whether text is
worth keeping or should be written again. This PR lands the three strata the
counter will sit on: host-portable pattern primitives, term-to-pattern
compilation, and non-prose masking.

No counting yet. The lexicon, the scorer and the bingo card follow in later
PRs, as does the MCP server, the CLI and the Node build.

## Motivation

Generated prose has a register, and that register is now leaking into
everything: marketing adjectives that assert quality instead of stating
behaviour, corporate filler, and a set of words that mark a text as machine
written. This repository already bans that register by hand in
`standards/miniforge` and in the author's own instructions; nothing measures
it.

A measurement makes the rule enforceable in three places at once: a model can
check its own draft before delivering, a CI job can fail a document, and a
human can get a number instead of an argument.

The component is `.cljc` and host-independent so the same scanner can back the
JVM tooling here, a Babashka CLI, and an npm-published Node build that any
MCP-speaking vendor tool can call.

## Changes in Detail

### `components/buzzword-bingo/…/regex.cljc`

Confines every host difference in regular-expression handling to one
namespace.

- `find-all` returns `{:match/index :match/text}` for each match. Neither host
  offers this directly: `Matcher` has no ClojureScript equivalent and `re-seq`
  reports matched text without positions.
- `ascii-lower` folds case without the length changes full Unicode folding can
  introduce (U+0130 lowercases to two codepoints), because every reported line
  and column is an index into the folded string.
- `escape` covers only the punctuation both engines accept escaped; escaping
  more throws on the JVM.

Patterns are matched against pre-folded lowercase text and spell `[\s\S]`
rather than relying on DOTALL, since JavaScript has no inline `(?i)`, `(?s)`
or `(?m)` modifiers.

### `components/buzzword-bingo/…/phrase.cljc`

Compiles a lexicon term into a pattern that matches it in real prose.

- Word-boundary anchoring, applied only at edges where the term has a word
  character, so a term like `c++` is not asked for a boundary it lacks.
- Internal spaces match any whitespace run, so a phrase still matches when a
  paragraph wraps mid-phrase.
- Optional stemming of the final word only, since inflection belongs to the
  head of a phrase. Covers regular endings plus the two spelling changes
  English makes: dropped silent `e` (`leveraging`) and consonant + `y`
  becoming `ie` (`synergies`). Words under three characters keep their final
  letter.

### `components/buzzword-bingo/…/segment.cljc`

Blanks the regions of a document that are not authored prose, before anything
counts words in it.

- Fenced and inline code, markdown link targets, bare URLs, filesystem paths,
  HTML comments and tags.
- Blockquotes as well, unless `:score-quotes?` is passed. Quoting somebody
  else's marketing copy is not writing marketing copy.
- Masked characters become spaces and newlines survive, so the mask is the
  same length as the source. That property is what lets a later stratum report
  the line and column of a hit it found in the masked copy.

### `components/buzzword-bingo/…/interface.cljc`

Public surface for this slice: `prose-only`, exposed because a disputed score
is nearly always a masking question, and seeing what the scanner treated as
prose settles it.

### Workspace registration

`components/buzzword-bingo` added to the `:dev` and `:test` aliases.

## Testing Plan

- `regex-test` — case folding preserves length on a codepoint that would
  otherwise expand, escaped metacharacters stay literal, match offsets ascend
  in source order, and enumeration terminates on a zero-width pattern (the
  JavaScript branch loops forever without an explicit `lastIndex` bump).
- `phrase-test` — whole-word matching, phrases across a line break, case
  folding, each stemming rule, no over-reach onto words sharing a stem prefix,
  and a blank term rejected rather than compiled.
- `segment-test` — mask length and newline count preserved, each non-prose
  category blanked, `input/output` treated as prose rather than a path, and
  ordinary prose returned unchanged.

All run under Babashka and on the JVM through `poly test`; the test namespaces
are `.cljc` so the ClojureScript build in a later PR can run the same
assertions.

## Deployment Plan

Library code only. No entry point, no runtime behaviour change to any existing
project. Nothing consumes the component until the following PR.

## Related Issues/PRs

Part of a sequence. Each depends on the one before and branches from `main`
after it merges, rather than stacking:

1. **This PR** — regex, phrase, segment strata
2. Lexicon catalog, detection, scoring
3. Bingo card and session accumulation
4. Report renderers and message catalogs
5. CLI and `bb bingo` task
6. MCP server
7. Stop hook and skill
8. ClojureScript/Node build and npm packaging

## Checklist

- [x] `poly check` clean
- [x] `clj-kondo` clean, no warnings
- [x] `stratum-lint` clean, every file within the three-layer budget
- [x] Each commit under the 200-line commit budget; whole PR under 600
- [x] Apache header on every source file
- [x] Adversarial self-review of the diff
- [x] Standards gap analysis against `standards/miniforge`
