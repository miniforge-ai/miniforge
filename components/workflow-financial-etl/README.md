# Financial ETL Workflow Resources

This component owns app-specific workflow resources for the financial ETL
product.

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

ETL is a real product layer, not just a kernel demo. Its workflow-specific
configuration should therefore live outside the shared `workflow` component,
just like software-factory configuration does.

## Follow-up

When the ETL product grows, additional workflow families, handlers, and related
app assets should continue to live beside this component rather than inside the
shared workflow catalog.
