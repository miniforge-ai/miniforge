<!--
  Title: Detection quality — implement :mode :negative
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: content-scan honors :mode :negative (Finding 7)

Branch: `audit/detection-quality`

## Summary

First step of governance-audit Finding 7 (detection quality). The content-scan
detector ignored `:rule/detection :mode`, so the 6 rules declaring
`:mode :negative` (`header-copyright`, `require-probes`,
`require-resource-limits`, `require-vpc`, `require-encryption`,
`require-resource-tags`) ran **inverted** — they flagged the *presence* of the
required pattern and passed on its *absence*. `header-copyright` fired on files
that had the copyright and passed files that lacked it.

`detect-content-scan` now honors `:mode`: `:positive` (default) flags a match;
`:negative` flags the absence of any match. Applicability (file-globs, phases)
scopes which artifacts a negative rule can flag.

## Deliverable

`docs/detection-quality-audit-2026-07-06.md` — the adversarial FP/FN analysis of
all 25 content-scan regexes and the custom/judge rules, with a recommended
follow-up order. This PR fixes the one outright correctness defect (`:mode`);
the FP/FN quality issues (secrets regex, untagged-image miss, `#(...)` miss) are
catalogued there for follow-up PRs.

## Test plan

- New `content-scan-negative-mode-test`: negative mode fires on absence, passes
  on presence; positive mode unaffected.
- policy-pack detection + gate + standard-packs suites: 73 tests, 555
  assertions, 0 failures.
- `bb poly:check` clean.

## Related

- Governance audit Finding 7.
