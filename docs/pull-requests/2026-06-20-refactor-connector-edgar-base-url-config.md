<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor(connector-edgar): make SEC base URLs configurable

## Overview

The EDGAR connector held its two SEC endpoints as bare top-level `def`s:
`efts-base` (`https://efts.sec.gov/LATEST/search-index`) and `archives-base`
(`https://www.sec.gov/Archives/edgar/data`). This change reads both from the
passed-in connector config, with the prior literals kept as in-code defaults.
Behavior is unchanged when no override is supplied.

## Motivation

Config-as-data (Dewey 007): endpoint URLs are configuration, not constants.
Sibling connectors already read their base URL from the config map — for
example, connector-github reads `(get config :github/base-url "https://api.github.com")`
in `do-connect`. EDGAR did not, so an operator could not repoint it at a stub
or mirror for testing or for an alternate host. This brings EDGAR to parity
with the sibling pattern.

## Changes

- `components/connector-edgar/src/ai/miniforge/connector_edgar/impl.clj`
  - Removed the `efts-base` and `archives-base` top-level `def`s.
  - `aggregate-buy-sell-ratio` now resolves both URLs from config:
    `(get config :edgar/efts-base-url "https://efts.sec.gov/LATEST/search-index")`
    and `(get config :edgar/archives-base-url "https://www.sec.gov/Archives/edgar/data")`.
  - The resolved URLs are threaded as the leading argument into
    `search-filings`, `fetch-filing-transactions`, and `fetch-filing-xml`.
  - The default strings are byte-for-byte the previous literals, so an empty
    config produces the same URLs as before.

## Verification

- connector-edgar tests in isolation: 12 tests, 36 assertions, 0 failures, 0 errors.
- Namespace load smoke: `impl` loads; resolving both keys against an empty
  config returns the unchanged default URLs.
- `bb poly:check`: OK.
