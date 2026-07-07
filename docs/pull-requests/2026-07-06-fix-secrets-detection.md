<!--
  Title: no-hardcoded-secrets — known-prefix + broader coverage
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: no-hardcoded-secrets known-prefix + broader coverage (Finding 7)

Branch: `fix/secrets-detection`

## Summary

Finding 7 follow-up. The single `no-hardcoded-secrets` regex was an FN sieve
(missed provider key shapes with no keyword, unquoted values) and named in the
audit. Replace it with a `:patterns` set:

- broadened keyword-quoted assignments (adds passphrase, client-secret,
  access/auth-token, private-key, credential);
- high-confidence provider shapes caught regardless of a keyword — AWS `AKIA…`,
  GitHub `gh[pousr]_…`, OpenAI `sk-…`, Slack `xox…`, Google `AIza…`, and
  `-----BEGIN … PRIVATE KEY-----`;
- a conservative unquoted case (16+ token-ish chars) so YAML/env secrets are
  caught but config words like `password: required` are not.

## Test plan

- New `no-hardcoded-secrets-detection-test`: 8 secret fixtures fire (incl.
  keyword-less provider keys); 6 non-secrets (env refs, config words, comments,
  plain code) do not.
- `standard-packs` suite: 5 tests, 363 assertions, 0 failures.

## Related

- Governance audit Finding 7; catalogued in `docs/detection-quality-audit-2026-07-06.md`.
