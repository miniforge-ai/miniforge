<!--
  Title: no-open-ingress — multi-CIDR + IPv6
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: no-open-ingress matches multi-CIDR + IPv6 (Finding 7)

Branch: `fix/ingress-detection`

## Summary

Finding-7 false-negative fix. `tf-aws/no-open-ingress` matched `0.0.0.0/0` only
as the *sole* list element, so `cidr_blocks = ["10.0.0.0/8", "0.0.0.0/0"]` and
IPv6 any (`ipv6_cidr_blocks = ["::/0"]`) slipped through. The pattern now matches
`0.0.0.0/0` or `::/0` anywhere in a `(ipv6_)cidr_blocks` list.

## Test plan

- New `no-open-ingress-detection-test`: sole element, multi-CIDR, and IPv6 fire;
  restricted CIDRs do not.
- `standard-packs`: 8 tests, 388 assertions, 0 failures.

## Related

- Governance audit Finding 7 (`docs/detection-quality-audit-2026-07-06.md`).
