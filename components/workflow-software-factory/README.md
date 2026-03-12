# Software Factory Workflow Families

This component owns the flagship software-factory workflow definitions.

These workflows are intentionally outside the shared `workflow` component. They
are application-owned resources composed onto the classpath by projects such as
the CLI app.

## What This Component Owns

- SDLC workflow family definitions under `resources/workflows/`
- software-factory workflow selection mappings under
  `resources/config/workflow/selection-profiles.edn`
- software-factory recommendation prompt vocabulary under
  `resources/config/workflow/recommendation-prompt.edn`

## Shipped Workflow Families

| Workflow ID | Version | Purpose |
|-------------|---------|---------|
| `standard-sdlc` | 2.0.0 | Main interceptor-based SDLC flow |
| `quick-fix` | 2.0.0 | Fast path for small bug fixes |
| `nimble-sdlc` | 1.0.0 | Explore-first SDLC variant |
| `canonical-sdlc-v1` | 1.0.0 | Legacy comprehensive SDLC flow |
| `lean-sdlc-v1` | 1.0.0 | Legacy fast-path SDLC flow |

Unversioned aliases such as `canonical-sdlc.edn` are present for compatibility
with existing references inside the flagship app.

## Selection Profiles

The software-factory app currently maps logical workflow profiles as follows:

```clojure
{:comprehensive :canonical-sdlc-v1
 :fast :lean-sdlc-v1
 :default :lean-sdlc-v1}
```

Those mappings are app-owned. The shared workflow selector only understands
logical profiles such as `:fast` and `:comprehensive`.

## Relationship to the Shared Workflow Component

The shared `workflow` component owns:

- workflow loading
- registry and discovery
- validation
- generic execution
- generic publication and event-trigger seams

This component owns the flagship app's workflow data and prompt/config assets.

## Related Components

- `components/phase-software-factory/`
- `components/workflow-chain-software-factory/`
- `docs/architecture/workflow-component.md`
