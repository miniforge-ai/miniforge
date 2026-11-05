<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: admit only the verified portable JSON coordinate

## Overview

Replace the blanket JSON dependency ban with an exact-coordinate compatibility
policy. Only `org.clojure/data.json` version `2.4.0` is approved for native use.

## Motivation

The Prometheus boundary needs lossless decimal timestamps. Native Babashka does
not expose Cheshire's decimal setting. The portable release passed real packaged
execution; newer releases failed because they generate an unsupported interface.

## Changes in Detail

- Preserve unsupported dependency rejection and native execution gates.
- Reject unverified JSON versions and coordinate variants, including alias overrides.
- Separate filesystem discovery to keep the compatibility namespace within three strata.
- Reuse the acknowledged gate fixture to repair main's warning-test regression.

## Testing Plan

Check accepted and rejected coordinates, nested dependency sites, and repository
manifests. Run the native compatibility suite and normal signed hooks.
The paired client PR retains actual lossless-decimal packaged execution coverage.

The old substring policy failed 16 regression assertions without errors.
Besides rejecting the portable release, it missed namespaced-map dependency forms.
The initial structured policy passed 3 tests and 176 assertions. It checks brick,
project and root manifests, including alias overrides, without loading dependencies.
Java JDBC rejection and all existing native execution tests remain in place.

Fresh review found classifier and task-manifest coverage gaps. Regressions reproduced
five assertion failures and one reader error. The corrected policy passes 5 tests
and 186 assertions, including native task dependency sites and disabled reader evaluation.
Both `deps.edn` and `bb.edn` manifests are scanned. Classifier artifacts remain rejected
even when their declared version matches the approved artifact.

A follow-up review identified unrelated task data being mistaken for declarations.
Two failing regressions now pass after restricting inspection to manifest, alias
and task dependency fields. Example task code and execution arguments remain data;
they neither install dependencies nor trigger false policy failures.

The documented alias inventory also includes default dependencies and classpath
overrides. Five failing assertions now pass after admitting verified defaults
and rejecting every JSON classpath replacement, including nil and coordinate-shaped values.
Scopes follow the [Clojure manifest reference](https://clojure.org/reference/deps_edn)
and [Babashka task options](https://book.babashka.org/#_task_local_options).
Root declarations use `:deps`; task-local declarations use `:extra-deps`.

Review also found the extracted discovery helper retained a Unix-only separator
assumption. A Windows-path regression failed before normalizing both separator
forms. The compatibility gate now retains Unix and Windows discovery assertions.

## Deployment Plan

This changes test policy only. It installs no production dependency and grants
no runtime authority. No live server or cluster access is involved.

## Related Issues/PRs

Prerequisite for #2314. The user explicitly approved the scoped policy correction.

## Checklist

- [x] Exact-coordinate policy and adversarial regressions
- [ ] Native execution and normal signed-hook gates
- [ ] Fresh Copilot review and all CI gates
