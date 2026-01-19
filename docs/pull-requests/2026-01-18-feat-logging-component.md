# feat: Add logging component with structured EDN logging

## Overview

Creates the `logging` component for miniforge.ai, implementing structured EDN logging with context propagation. This is Phase 1 (Core Components) work that provides the logging infrastructure for all other components.

## Motivation

The miniforge.ai platform requires structured logging that:

- Emits EDN maps (not formatted strings) for machine processing
- Supports context propagation (workflow-id, task-id, agent-id flow through)
- Enables level filtering (:trace through :fatal)
- Provides timed execution tracking for performance metrics
- Is testable via collecting loggers

## Changes in Detail

### New Component: `components/logging/`

**deps.edn**
- No external dependencies (pure Clojure)

**core.clj** - Logger implementation
- `make-entry` - Creates log entry maps with required fields
- `merge-context` - Merges context into entries (entry values win)
- `level-enabled?` - Checks if level meets minimum threshold
- `format-edn` / `format-human` - Output formatters
- `Logger` protocol with `log*`, `with-context*`, `get-context`, `get-config`
- `EDNLogger` record implementing the protocol
- `collecting-output-fn` - For test assertions

**interface.clj** - Public API
- `create-logger` - Create logger with options (:min-level, :output, :context)
- `with-context` - Add context to all subsequent entries
- `collecting-logger` - Create test logger that captures entries
- Level functions: `trace`, `debug`, `info`, `warn`, `error`, `fatal`
- `timed` / `with-timing` - Execute and log with duration

**interface_test.clj** - Comprehensive tests
- 8 test functions, 36 assertions
- Logger creation and configuration
- Context propagation and merging
- All log levels
- Entry structure validation
- Min-level filtering
- Timed execution

### Modified Files

**deps.edn** (root)
- Added logging component to `:dev` alias extra-paths
- Added logging component to `:test` alias

## Testing Plan

- [x] `poly test :dev :all-bricks` passes (72 assertions total)
- [x] `clj-kondo` linting passes with no warnings
- [x] Logger creates entries with required fields
- [x] Context propagates through with-context
- [x] Level filtering works correctly
- [x] Timed execution captures duration

## Deployment Plan

Merge to main. The logging component has no external dependencies and becomes available for other components to use.

## Architecture Notes

### Polylith Structure

```
components/logging/
├── deps.edn                  # No dependencies
├── src/ai/miniforge/logging/
│   ├── interface.clj         # Public API
│   └── core.clj              # Implementation
└── test/ai/miniforge/logging/
    └── interface_test.clj    # Tests
```

### Dependency Position

```
logging -> []  (depends on nothing internal)
```

Other components will use `ai.miniforge.logging.interface` for logging.

### Usage Example

```clojure
(require '[ai.miniforge.logging.interface :as log])

;; Create a logger
(def logger (log/create-logger {:min-level :info :output :human}))

;; Add context for a workflow
(def ctx-logger (log/with-context logger
                  {:ctx/workflow-id (random-uuid)
                   :ctx/phase :implement}))

;; Log events
(log/info ctx-logger :agent :agent/task-started
          {:message "Starting implementation"
           :data {:agent-role :implementer}})

;; Timed execution
(log/timed ctx-logger :info :system :system/health-check
           #(check-health))
```

## Checklist

- [x] Component created via `poly create component name:logging`
- [x] Logger protocol defined
- [x] EDNLogger implementation
- [x] Context propagation
- [x] Level-specific functions (trace, debug, info, warn, error, fatal)
- [x] Timed execution support
- [x] Collecting logger for tests
- [x] Tests written and passing (36 assertions)
- [x] Linting clean (0 warnings)
- [x] 3-layer file structure followed
- [x] Rich comment blocks for REPL testing
- [x] PR documentation created
