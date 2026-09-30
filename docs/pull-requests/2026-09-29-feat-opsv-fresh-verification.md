<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Verify synthesized OPSV candidates with fresh measurements

## Overview

Close the application-level N7 verification gap: VERIFY invokes an adapter against
the synthesized candidate instead of reusing the selected baseline ramp step.

## Contract and behavior

- Add a separate VerificationAdapter port. Each invocation carries a fresh UUID,
  the exact candidate and Experiment Pack, and their computed content hashes.
- Require correlated observations, bounded confidence, environment fingerprint and
  metric artifact references. Reject old invocation IDs, changed hashes and
  different environments before evaluating acceptance criteria.
- Retain the candidate measurement receipt separately from the policy with its
  attached verification summary. The two policy hashes have different meanings.
- Use measured confidence and artifact references, not baseline values or caller
  input references. A successful baseline can produce a failed verification result.
- Preserve returned anomalies, fatal errors and interruption. Ordinary adapter
  exceptions become unavailable results at a named boundary.
- Extend the callback factory with a verification callback. Legacy two-callback
  adapters fail closed at VERIFY; callback construction rejects non-functions.
- Give the simulated adapter a separate verification scenario. It cannot silently
  fall back to baseline measurements when that scenario is missing.

## Standards adversarial pass

Keep adapter construction, correlation validation and criterion evaluation in
separate namespaces. Public interface functions delegate; the canonical receipt
constructor is reused by adapters and test fixtures. Diagnostics use the catalog.
No provider dependency or authority issuance is introduced by the new port.

## Validation

Run both phase consumers, simulated adapter tests, shared workflow integration,
packaged verification tests and the scoped standards scanner. Regressions cover
baseline success with candidate failure, mismatched provenance, malformed results,
missing adapters, invalid callbacks, fatal errors and interruption.

## Limitations

This is an application boundary and simulated acceptance implementation, not a
real load executor. Runtime adapters must enforce governance, guardrails and aborts
before effectful verification. Production load, apply, host command wiring and
full conformance remain separate work. No live experiment is run by this change.
