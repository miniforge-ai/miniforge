# Software Factory Phase Set

This component owns the shipped phase implementations for the flagship
software-factory application.

The shared `phase` component now owns only the registry and generic loading
surface. The actual SDLC phase implementations live here.

## Registered Phase Namespaces

Loaded from `resources/config/phase/namespaces.edn`:

```clojure
[ai.miniforge.phase.explore
 ai.miniforge.phase.plan
 ai.miniforge.phase.implement
 ai.miniforge.phase.verify
 ai.miniforge.phase.review
 ai.miniforge.phase.release]
```

## What This Component Owns

- software-factory phase implementations
- shipped SDLC phase registration
- review, verify, release, and implementation behavior specific to the flagship
  app

## Relationship to the Shared Phase Component

The shared `phase` component owns:

- registry helpers
- generic status helpers
- resource-driven namespace loading

This component supplies the concrete flagship phase set used by the loaded
software-factory workflows.

## Related Components

- `components/workflow-software-factory/`
- `components/workflow-chain-software-factory/`
