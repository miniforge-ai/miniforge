<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# feat: bounded canonical evidence EDN

## Overview and motivation

Foundation prerequisite for #1960: preserve nanosecond instants, reject trailing
forms, and cap file reads before allocating the complete input. No schema or
authenticity claims are made by parsing; consumers must validate the result.

## Changes

Canonical content-hash serialization is paired with a single-form EDN reader.
A separate file boundary reads at most 16 MiB plus one byte and rejects malformed
UTF-8. Public interface functions are thin pass-throughs; CLI wiring stays in #1960.

## Standards adversarial pass

Keep pure codec and file effects separate. Reuse canonical serialization, name
the size limit, preserve interruption, and reject trailing data using an unforgeable
EOF sentinel. Test malformed input and exact limits, not only successful parsing.

## Testing plan

Run all evidence-bundle consumers, packaged Babashka regressions, normal hooks,
the standards scanner, current-head review, and CI including Build.

## Deployment and related work

Merge independently to main, then integrate into #1960 before its merge.
No provider operations or migration are performed.

## Checklist

- [ ] JVM and packaged codec regressions pass
- [ ] Standards, review, and CI settle
