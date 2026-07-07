<!--
  Title: no-open-ingress — multi-CIDR + IPv6
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: no-open-ingress matches multi-CIDR + IPv6 (Finding 7)

Branch: `fix/ingress-detection`

## Summary

Finding-7 false-negative fix. `tf-aws/no-open-ingress` matched `0.0.0.0/0` only
as the *sole* `cidr_blocks` element. It now matches the quoted open-CIDR value
(`0.0.0.0/0` or IPv6 any `::/0`) directly, so it catches multi-CIDR lists, IPv6,
and — because `content-scan` matches per-line — the common multi-line list form
where the CIDR sits on its own line. The enforcement message now names `::/0`.

## Engine note

`detect-content-scan` matches each line independently (`find-matches` splits on
lines). Matching the quoted value works with that; a context-anchored pattern
(`cidr_blocks = [ … 0.0.0.0/0`) would have been a false negative for multi-line
lists. The same per-line limitation affects other multi-line patterns (e.g.
`k8s/require-resource-limits`'s `resources:\n  limits:`) and is noted in
`docs/detection-quality-audit-2026-07-06.md` as a detector-engine follow-up.

## Test plan

- `no-open-ingress-detection-test`: single-line, multi-line, and IPv6 fire;
  restricted CIDRs and unquoted prose mentions do not.
- `standard-packs`: 8 tests, 0 failures.

## Related

- Governance audit Finding 7.
