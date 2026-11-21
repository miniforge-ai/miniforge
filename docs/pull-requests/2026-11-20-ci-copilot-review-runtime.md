<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# ci: prepare compatible runtimes for Copilot code review

## Overview

Give Copilot code review Java 21 and the repository's pinned Clojure,
Babashka and clj-kondo versions before review begins.

## Motivation

The full review of #2542 reported no findings but could not verify its
process cleanup on compatible runtimes. Independent passing tests do not
replace the required full-review gate.

## Changes in detail

Infrastructure only, based on main with no prerequisite. Use GitHub's
dedicated review setup workflow and required `copilot-setup-steps` job.
Read tool versions from the existing `.github/tool-versions.json`.
Pin setup actions to exact revisions and use a read-only checkout with
the pinned standards submodule and no persisted checkout credentials.
Reuse the existing Polylith, CLI build and Babashka classpath tasks to
prepare dependencies and validate the installed environment.

No new secrets, firewall changes, deployment actions, or merge exceptions.
The ordinary CI workflow and application code are unchanged.

## Testing plan

Validate workflow syntax and trace setup order, permissions and tool inputs.
Run the existing preparation tasks serially. The workflow also runs on
pull requests changing itself or its tool-version manifest, providing
hosted Linux validation before merge. Application unit tests are unchanged:
the new executable setup job is the configuration regression check.
Actionlint 1.7.12 reports no findings. All three preparation tasks passed
locally on Java 21.0.6 and Babashka 1.12.217. The hosted setup check
validates the exact manifest versions rather than relying on local tools.

## Deployment plan

Merge only after all normal gates pass. Verify that a later Copilot
review uses the setup and compatible runtimes. Then revisit PR #2542
without weakening its final full-review requirement.

See GitHub's [review environment documentation](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/use-code-review)
and [setup workflow contract](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/customize-the-agent-environment).

## Related issues/PRs

Unblocks review of #2542, which precedes readiness repair in #2536.

## Checklist

- Workflow syntax and local preparation tasks validated.
- Hosted setup, ordinary CI and full review required before merge.
- Confirm runtime setup in subsequent review logs before revisiting #2542.
