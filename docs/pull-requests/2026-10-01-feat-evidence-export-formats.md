<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Lossless evidence export rendering

## Scope

Base: main. Supply pure EDN, JSON and HTML encoders for the N5 export formats.
The consumer validation and filesystem integration remain in #1960.
Rendering alone neither seals nor validates evidence and performs no writes.

JSON uses the existing bounded Transit JSON wire contract. Transit readers retain
keyword and UUID identity, map keys, list/vector kinds and nanosecond timestamps;
the timestamp extension tag is `miniforge/instant`. Plain JSON parsers can inspect
the tagged document but need Transit decoding to reconstruct typed evidence.
Do not coerce arbitrary keys into JSON object keys or round timestamps and numbers.
HTML shows escaped canonical EDN with a localized heading and no executable content.

## Standards adversarial pass

Expose existing artifact serialization through its public interface; do not copy
codec handlers across components. Keep the format registry as data with named
encoders, separate from validation and filesystem effects. Preserve interruption
and fatal errors at the JSON encoding boundary.

## Verification

Tests cover JSON parsing, typed round trips, nanosecond identity, distinct keys,
deferred input refusal and HTML escaping. Run serial component and CLI tests,
rebuild the CLI, then run packaged regressions and normal commit hooks.
Require a clean standards scan, current-head review and all CI before merge.
All artifact consumers and the CLI JVM renderer test pass. The CLI rebuild succeeds;
packaged JSON and renderer regressions pass 4 tests with 18 assertions.
