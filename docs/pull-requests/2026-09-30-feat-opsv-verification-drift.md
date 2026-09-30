<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Record verification environment drift

## Scope

Emit N3 `opsv.drift/detected` when a fresh, correlated verification receipt
reports an environment fingerprint different from the experiment baseline.
Keep verification failed. Suggest a rerun without scheduling one or granting
authority. Invalid and stale receipts do not establish drift.

## Design and standards

Reuse the event constructor, bounded artifact portability check, and shared
delivery/evidence boundary. Preserve the original conflict if publication fails,
with the publication failure attached as structured data. Keep diagnostics
localized and isolate exception conversion. All namespaces have at most three
dependency strata. Tests exercise actual lifecycle entry/exit with local stores
and simulated adapter ports, without provider or load mutations.

## Verification and limitations

Test workflow/bundle correlation, failed phase status, assembly acknowledgment,
stale receipt rejection, and event-publication refusal. Run both phase consumers,
packaged regressions, lint/Polylith, and the standards scan before merge.
This is the verification-time environment producer, not a continuous deployed
policy or metrics monitor. Library transformations without a stream retain the
structured drift failure but cannot publish an event.
