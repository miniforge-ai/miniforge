<!--
  Title: Detection-quality audit (governance Finding 7)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Detection-quality audit — governance Finding 7

Adversarial pass over the policy-pack detection surface (25 content-scan regexes,
93 `:custom` rules across the shipped packs), per the 2026-07-05 governance
audit's Finding 7. Per rule: the false-positive (FP) and false-negative (FN)
classes, and whether a regex is the right tool.

## Fixed in this PR (correctness, not FP/FN)

**`:mode :negative` was declared but unimplemented — 6 rules ran inverted.**
The schema defines `:negative` ("absence of a match is a violation"), the
mdc-compiler reads it, and 6 rules declare it (`header-copyright`,
`require-probes`, `require-resource-limits`, `require-vpc`,
`require-encryption`, `require-resource-tags`). But `detect-content-scan`
ignored `:mode` and always flagged presence. Runtime effect: `header-copyright`
fired on files that *had* the copyright and passed files that *lacked* it;
`require-probes` flagged manifests that *had* probes. `detect-content-scan` now
honors `:mode`, with tests. This is the one outright correctness defect in the
set; the rest below are FP/FN quality issues for follow-up.

## Content-scan FP/FN classes (follow-up)

- `foundations/no-hardcoded-secrets`
  `(?i)(password|secret|api[._-]?key|token)\s*[:=]\s*["'][^"']{8,}`
  - FN: base64 blobs, concatenated (`"AKIA"+rest`), heredocs, unquoted YAML
    (`password: hunter2`), tokens < 8 chars, other key names (`pwd`,
    `passphrase`, `credential`), known-prefix keys with no keyword.
  - FP: fixtures/placeholders (`api_key: "your-key-here"`), any 8-char literal
    after `token:`, docs.
  - Verdict: regex is the wrong primary tool for secrets. Add known-prefix
    patterns (`AKIA`, `ghp_`, `sk-`, `xox`, `-----BEGIN * KEY-----`) and/or a
    Shannon-entropy check; keep this as a cheap tripwire, not a detector.

- `kubernetes/no-latest-tag` `image:\s*\S+:latest`
  - FN: **untagged images** (`image: nginx`) — implicitly `:latest`, the most
    common case, entirely missed. Quoted images.
  - FP: `image: app:latest-stable` (matches the `:latest` prefix of a longer
    tag).

- `foundations/no-inline-anon-fns`
  `(?:map|keep|filter|mapcat|remove|reduce)\s+\(fn\s+\[`
  - FN: `#(...)` reader anonymous fns — the *more common* inline form — are
    missed; only `(fn [...])` is caught.
  - FP: `filter`/`map` as bindings or in strings.

- `terraform-aws/no-open-ingress` `cidr_blocks\s*=\s*\["0\.0\.0\.0/0"\]`
  - FN: `0.0.0.0/0` as one of several CIDRs (regex requires it be the sole
    element); IPv6 any (`::/0`); variable references.

- `terraform-aws/approved-instance-types` (negative-lookahead allowlist)
  - FN: `instance_type = var.x` (variable) bypasses the allowlist entirely.
  - Config smell: the approved list is baked into a regex; a new type needs a
    regex edit rather than a data change (config-as-data).

- `terraform-aws/no-public-s3` `acl\s*=\s*"public-read"` — FN: `public-read-write`.
- `standards/datever` `\b\d+\.\d+\.\d+\b(?!\.\d)` — high FP: matches any semver
  anywhere (deps, docstrings); only meaningful in files where DateVer is
  required, which the pattern cannot express — applicability scoping is
  load-bearing.
- `no-unwrap-in-lib` `\.unwrap\(\)` — FP: unwrap in tests/examples; FN:
  `.expect(...)` (also panics).

## Custom / judge-backed rules

The 93 `:custom` rules resolve to a registered detector fn or, absent one, to
the LLM judge. The judge path fails **closed** when its wiring is absent —
`compile-check-fn` emits a `missing-semantic-wiring` violation rather than
silently passing (verified in `compiler.clj`). That fail-closed posture is the
right default and should stay pinned; a dedicated per-rule pass should still
confirm each custom-fn's registration and signature (see the deployment-safety
`:check-fn`→`:custom-fn` fix in #1381 for the failure mode where a `:custom`
rule silently routed to the judge).

## Recommended follow-up order

1. `no-hardcoded-secrets` — known-prefix + entropy (highest security value).
2. `no-latest-tag` untagged-image FN; `no-inline-anon-fns` `#(...)` FN.
3. `no-open-ingress` multi-CIDR + IPv6; `approved-instance-types` as data.
4. A per-custom-rule registration/signature sweep.

## Engine limitation (discovered during the follow-ups)

`detect-content-scan` matches each line independently (`find-matches` splits on
`\n` and `re-find`s per line). Any pattern that must span lines silently fails:

- `k8s/require-resource-limits`'s `resources:\n\s+limits:` cannot match (the two
  keys are on different lines) — a standing false negative.
- Multi-line Terraform lists need the matched token on a single line (handled in
  `no-open-ingress` by matching the quoted CIDR value directly).

Follow-up: give `detect-content-scan` an opt-in whole-content match mode (or a
multi-line flag per rule) so genuinely multi-line requirements can be expressed.
