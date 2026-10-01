<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Extract the control authorization boundary

## Scope

Extract the existing role/action/target decision into a pure, two-stratum
namespace and delegate the live controller to it. Preserve the public interface
and existing grants/denials. Localize its diagnostics and deny missing action
types without a name-on-nil exception.

Base: main. This is the authorization prerequisite extracted from #1969 so its
control evidence and dashboard lifecycle changes remain reviewable. It does not
change requested-event metadata or implement approval enforcement.

## Standards adversarial pass

Keep role configuration in the controller; isolate pure decisions from execution.
Use named grant/denial constructors and one shared diagnostic context. Retain
public component interfaces. Verify inferred strata rather than headings alone.

## Verification

Run all four event-stream consumers serially, rebuild the CLI, run packaged
authorization regressions, and run normal hooks plus a root standards scan.
All four event-stream consumers pass serially. Rebuilt packaged authorization and
control regressions pass 12 tests and 49 assertions; Kondo is clean.

## Merge criteria

Clean current-head review, resolved comments, all CI including Build, no conflicts,
and the standard commit/PR size limits. No external deployment.
