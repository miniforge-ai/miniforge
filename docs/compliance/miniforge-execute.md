---
title: Miniforge Compliance Execute
description: Scan, classify, plan, and auto-fix policy violations. Opens one PR per rule for auto-fixable violations.
type: compliance-execute
rules: :always-apply
repo-path: "."
---

Run the full compliance remediation pipeline against the miniforge repository.

Phases: scan → classify → plan → execute

Output:

- `.miniforge/compliance-report.edn` — machine-readable violation list
- `docs/compliance/YYYY-MM-DD-compliance-delta.md` — remediation work spec
- One GitHub PR per rule for auto-fixable violations
