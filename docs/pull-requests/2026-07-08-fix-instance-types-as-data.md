<!--
  Title: Approved instance types as data
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: approved instance types are data, not a regex

Branch: `fix/instance-types-as-data`

## Summary

`tf-aws/approved-instance-types` encoded its allowlist inside a negative-lookahead
regex (`instance_type = "(?!t3\.|t4g\.|m5\.|...)"`) — policy baked into a pattern,
opaque and unmaintainable. The approved families are now DATA on the rule
(`:rule/detection :detector-config :approved-families`), read by a built-in
`:custom` detector. Changing the approved list is a data edit, not a regex edit.

## Mechanism

- `detection/run-resolved-custom` threads the rule into `context` under
  `:policy-pack/rule`, so a `:custom` detector can read its own
  `:detector-config` and enforcement message — parity with the by-type
  detectors, which already receive the rule.
- New `:detector-config` key on `RuleDetection` (a data map of policy params).
- `policy-pack.builtin-detectors/check-approved-instance-types` reads the
  approved families from that data and flags `instance_type` literals whose
  family is not approved. It registers at load; the interface loads it so the
  fail-closed resolver (#1402) sees it whenever a pack compiles.

The variable-reference case (`instance_type = var.x`) is still not evaluated —
that limitation is inherent to static scanning and was equally true of the regex.

## Test plan

- `builtin-detectors-test`: rule binds `:custom`; approved passes, unapproved
  fires; changing `:approved-families` flips the outcome (proves it is data);
  violation carries the enforcement message.
- policy-pack + gate suites: 104 tests, 749 assertions, 0 failures.
- `bb test:graalvm` passes (load-time registration under babashka).
- `bb poly:check` clean.
