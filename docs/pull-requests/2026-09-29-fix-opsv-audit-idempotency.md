<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Idempotent actuation evidence delivery

## Scope

Give actuation decisions, outcomes, and transaction dispositions content-bound identities within their preallocated
workflow and evidence bundle.
Evidence retries reuse acknowledged occurrences rather than append duplicate audit records.
Other phase events retain their existing occurrence identities.

## Standards adversarial pass

The replay namespace owns identity and acknowledgment lookup; delivery retains validation and evidence accumulation.
Use the public bounded artifact digest and public event and evidence interfaces.
Keep each namespace within three strata and reuse existing anomalies.
Serialize retry publication on the stream and retain acknowledgment checks after delivery.

## Verification

Exercise repeated outcomes, partial phase-event delivery, and failed evidence accumulation with local test ports.
Verify that restored evidence references suppress already acknowledged occurrences without provider calls.
Run phase consumers, packaged tests, and scoped standards before merge.
