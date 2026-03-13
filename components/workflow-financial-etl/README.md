# Data Foundry — ETL Workflow Resources

This component owns app-specific workflow resources for **Data Foundry**,
the ETL product built on MiniForge Core. The financial ETL workflow is one
workflow family managed by Data Foundry.

The current scope is the shipped ETL workflow family, the ETL
state-profile configuration, and ETL-owned runtime helpers used to run the
analytical product on top of the shared runtime.

## What This Component Owns

- ETL workflow definitions under `resources/workflows/`
- ETL workflow state-profile resources under
  `resources/config/workflow/state-profiles/`
- ETL-specific execution helpers under `src/`
- ETL-specific lifecycle event helpers under `src/`

## Why This Exists

Data Foundry is a real product layer, not just a kernel demo. Its
workflow-specific configuration should therefore live outside the shared
`workflow` component, just like Miniforge (software-factory) configuration does.

## Follow-up

When Data Foundry grows, additional workflow families, handlers, and related
app assets should continue to live beside this component rather than inside the
shared workflow catalog.
