<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-harness): canonical codec, hash-chained journal, synthetic tasks

## Overview

First of the PRs that land the deliberation development lab from snapshot
`a345990`. The snapshot put all of the lab's logic into a base
(`bases/deliberation-lab`). Here the reusable parts move into a new component,
`deliberation-harness`, and the base keeps only the CLI and the model adapter
(later PR). This PR adds the foundations: canonical EDN digests, the
hash-chained experiment journal, and the synthetic task package with its
deterministic scoring.

## Motivation

N15 §0.5 (#2328) lets a development comparison run, provided it never counts
as gate evidence. The snapshot's lab records every experiment in an immutable,
hash-chained journal so each recorded board can be replayed. The Polylith rule
(dewey 210) keeps domain logic in components and bases thin, so the journal,
codec and fixtures belong in a component.

## Changes in Detail

- `codec`
  - Canonical EDN written directly: map entries ordered by their key's
    then value's canonical text, set elements by their own text. No sorted
    collection is built, so ordering never has to agree with Clojure
    equality and no entry is merged or dropped. A record, host collection or
    array is tagged with its class and a float is written as the exact double it widens to, so
    neither shares a digest with a different value. Printer settings are pinned,
    so a caller's `*print-length*` cannot truncate a record.
  - SHA-256 digest.
  - `read-text`: parses exactly one plain EDN form and returns an anomaly
    for unreadable text, trailing forms or a custom reader tag. A tag
    becomes a `deftype` marker that plain EDN cannot construct. It neither
    throws nor evaluates a tag.
  - `read-source`: reads a file or resource as UTF-8; a read or parse
    failure is an anomaly naming the source.
  - `plain?`: true when a value reads back unchanged after encoding.
- `journal`
  - `open!` refuses a directory that already exists.
  - `append!` refuses a payload that is not `plain?` (it could not be
    replayed), then writes each record to a new file through `file-durability`
    and forces it. The position only advances after a successful write.
  - Record names are the sequence zero-padded to at least six digits,
    formatted with `Locale/ROOT` and ordered numerically, so sequence
    1,000,000 and later still read.
  - `read-journal` verifies sequence, predecessor hash and content hash, and
    returns an anomaly naming the first record that breaks the chain or
    cannot be read. An unlistable directory is an anomaly, not an empty
    journal. A file in the journal directory that is not a canonically named
    record is an anomaly, never silently skipped.
- `fixtures`
  - The two synthetic tasks now live in
    `resources/config/deliberation-harness/tasks.edn`.
  - `public-task` exposes only `:id :version :synthetic? :brief`.
  - `tasks` returns an anomaly when the resource is missing or unreadable.
  - `score` returns an anomaly for a task with no reference, experiments
    that are not a map, or `inspected` that is not a collection.
  - `score` and `fixture-defects`.
- `messages`: system catalog for the component's anomaly text.
- `interface`: `digest`, `read-edn`, `open-journal!`, `append!`,
  `read-journal`, `tasks`, `score`.
- Root `deps.edn` gets the component's `src`, `resources` and `test` paths.

Deviations from the snapshot:

- The snapshot threw from the codec, the journal and fixture validation.
  Here every failure returns an anomaly (dewey 005).
- Fixtures moved from code to an EDN resource (config as data, dewey 007).
- `fixture-defects` returns its findings instead of using `assert`.

## Testing Plan

- `codec_test`: order-independent encoding, digest stability, refused
  custom tags, unreadable text, trailing forms, an unforgeable tag marker,
  encoding under hostile printer bindings, no entry ever dropped, equal
  keys counted once, nil keys, `plain?`, and a tag inside anomaly-shaped
  text.
- `journal_test` (marked `:integration`; it writes a temporary directory,
  because durable files are the journal's contract): round trip, refusal
  to reuse a directory, tamper detection, an unlistable directory, an
  unreadable record, numeric ordering past six digits, an unreplayable
  payload refused without moving the position, a malformed record naming
  its file.
- `fixtures_test`: every reference passes and every distractor fails; success
  needs every observation; the public view hides the sealed answer.

Ran locally on plain Clojure 1.12 (Clojars is unreachable from the authoring
sandbox, so a local stand-in replaced malli): 32 tests, 78 assertions,
0 failures. clj-kondo 2026.10.26 (the CI version) over `bases components
development/src`: 0 errors. `poly test` and the Polylith check ran only in CI.

## Deployment Plan

None. Nothing calls the component yet.

## Related Issues/PRs

- Contract: #2328 (N15 §0.5). Independent of the deliberation-workspace stack
  (#2329–#2332).
- Next: the experiment cell runner and report, then the `deliberation-lab`
  base and project.

## Checklist

- [x] No throws on data paths
- [x] Tasks as data
- [x] Every behaviour tested
