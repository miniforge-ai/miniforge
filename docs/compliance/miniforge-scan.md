<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

---
title: Miniforge Compliance Scan
description: Scan the miniforge codebase for policy violations across all enabled rules. Produce a delta report and
remediation work spec.
type: compliance-scan
rules: :always-apply
repo-path: "."
---

Run the compliance scanner against the miniforge repository. Output:

- `.miniforge/compliance-report.edn` — machine-readable violation list
- `docs/compliance/YYYY-MM-DD-compliance-delta.md` — remediation work spec
