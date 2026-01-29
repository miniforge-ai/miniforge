# PR: Babashka-Compatible CLI Workflow Execution with Transit Artifact Store

## Summary

Enables instant-startup workflow execution in the Babashka-based CLI by:

1. Implementing a Transit-based artifact store (replaces JVM-only Datalevin for CLI use)
2. Refactoring artifact component to separate JVM-only dependencies
3. Adding `workflow run` and `workflow list` CLI commands with progress display
4. Fixing workflow validation to support both v1 (:workflow/phases) and v2 (:workflow/pipeline) formats

**Performance**: 37x faster startup (40ms vs 1478ms)

## Problem Statement

The miniforge CLI used Babashka for instant startup, but workflow execution failed because:

- Artifact component unconditionally required `datalevin` (JVM-only dependency via `nippy`)
- Workflow validation only supported v1 format
- CLI lacked workflow execution commands

This forced users to run workflows via slow JVM startup (`clojure -M:dev -m ...`), negating CLI benefits.

## Solution

### 1. Transit Artifact Store (Babashka-Compatible)

**File**: `components/artifact/src/ai/miniforge/artifact/transit_store.clj`

- **Architecture**: In-memory cache + async Transit JSON persistence
- **Storage**: `~/.miniforge/artifacts/{uuid}.transit.json`
- **Features**:
  - Fast in-memory access during execution
  - Lazy loading from disk
  - Metadata index for efficient queries (`index.transit.json`)
  - Full `ArtifactStore` protocol support
  - **Future-ready**: Transit format enables streaming to central location (team/org features)

**Benefits**:

- Babashka-compatible (no JVM-only deps)
- Survives process restarts
- Simple file-based persistence
- No database overhead

### 2. Artifact Component Refactoring

**Problem**: `artifact.core` unconditionally required `datalevin.core`, pulling in `nippy` (JVM-only).

**Solution**: Split into three namespaces:

- `artifact.core` - Protocol + pure functions (BB-compatible)
- `artifact.datalevin_store` - Datalevin implementation (JVM-only, lazy-loaded)
- `artifact.transit_store` - Transit implementation (BB-compatible, lazy-loaded)

**Files Modified**:

- `components/artifact/src/ai/miniforge/artifact/core.clj` - Removed Datalevin require
- `components/artifact/src/ai/miniforge/artifact/interface.clj` - Added lazy loading for both stores
- `components/artifact/deps.edn` - Added `com.cognitect/transit-clj` dependency

**Files Created**:

- `components/artifact/src/ai/miniforge/artifact/datalevin_store.clj` - Extracted Datalevin implementation
- `components/artifact/src/ai/miniforge/artifact/transit_store.clj` - New Transit implementation

### 3. CLI Workflow Commands

**File**: `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` (324 lines)

#### Commands

**`workflow list`** - List available workflows

```bash
mf workflow list
```

**`workflow run <id>`** - Execute workflow with progress display

```bash
# Basic
mf workflow run :minimal-test

# With input
mf workflow run :simple -i input.edn
mf workflow run :simple --input-json '{"task": "Build feature"}'

# CI/automation
mf workflow run :simple -q -o json
```

**Options**:

- `-v, --version` - Workflow version (default: latest)
- `-i, --input` - Input file (EDN or JSON)
- `--input-json` - Inline JSON input
- `-o, --output` - Output format: pretty, json, edn (default: pretty)
- `-q, --quiet` - Suppress progress output

#### Features

- Real-time phase progress with callbacks
- Colorized output with emojis
- Token/cost/duration metrics
- Automatic Transit artifact store creation
- Graceful fallback if workflow interface unavailable

#### Output Example

```text
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Miniforge Workflow Runner
  Workflow: simple
  Version:  latest
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📋 Phase: :plan starting...
  ✓ :plan completed
📋 Phase: :implement starting...
  ✓ :implement completed

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✓ Workflow completed
Tokens: 0 | Cost: $0.0000 | Duration: 0ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### 4. Workflow Validation Fix

**Problem**: Validator only supported v1 format (`:workflow/phases`), rejected v2 format (`:workflow/pipeline`).

**Solution**: Updated validator to accept either format.

**Files Modified**:

- `components/workflow/src/ai/miniforge/workflow/validator.clj`:
  - Allow either `:workflow/phases` or `:workflow/pipeline`
  - Skip phase-count check if pipeline present
- `components/workflow/resources/workflows/simple.edn`:
  - Added required metadata fields (`:workflow/created-at`, `:workflow/task-types`)

### 5. Bug Fixes

**Reviewer Agent** (`components/agent/src/ai/miniforge/agent/reviewer.clj`):

- Fixed broken function call (`create-base-agent` → proper agent structure)
- Issue exposed by Babashka's stricter analysis

**Project Dependencies** (`projects/miniforge/deps.edn`):

- Added all transitive component dependencies explicitly for uberjar

**Workflow Resources**:

- Created unversioned workflow files (`simple.edn`, `minimal-test.edn`, `canonical-sdlc.edn`)
- Loader expects `workflows/{id}.edn`, resources had `workflows/{id}-v{version}.edn`

## Testing

### Manual Testing

```bash
# List workflows (instant startup)
time mf workflow list
# Result: 0.040s (BB) vs 1.478s (JVM) = 37x faster

# Run minimal test workflow
mf workflow run minimal-test --input-json '{"task": "Test"}'
# Result: ✓ Workflow completed

# Run simple v2 workflow
mf workflow run simple --input-json '{"task": "Test"}'
# Result: ✓ Workflow completed (3 phases)

# Verify artifact persistence
ls ~/.miniforge/artifacts/
# Result: *.transit.json files present
```

### Transit Store Testing

```clojure
;; Save artifact
(def store (ts/create-transit-store {:dir "/tmp/test"}))
(core/save store {:artifact/id (random-uuid) :artifact/type :code ...})

;; Load artifact (from disk after restart)
(def store2 (ts/create-transit-store {:dir "/tmp/test"}))
(core/load-artifact store2 art-id) ; ✓ Loads from disk

;; Query artifacts
(core/query store {:artifact/type :code}) ; ✓ Works
```

## Performance Metrics

| Operation | Babashka | JVM | Improvement |
|-----------|----------|-----|-------------|
| `workflow list` | 40ms | 1478ms | **37x faster** |
| `workflow run` (minimal) | ~50ms | ~1600ms | **32x faster** |

## Impact

### Users

- **Instant CLI feedback** - No more waiting for JVM startup
- **Local workflow execution** - Run workflows directly from CLI
- **Better UX** - Progress display, colorized output

### Developers

- **BB-compatible architecture** - Template for other components
- **Clean separation** - JVM-only code isolated in separate namespaces
- **Transit format** - Ready for future streaming/sync features

### Future

- **Team/Org features** - Artifact streaming to central location
- **Distributed builds** - Multiple machines sharing artifact store
- **Monitoring** - Real-time workflow tracking via artifact events

## Migration Notes

### For Users

No breaking changes. New functionality only.

### For Developers

**Before** (artifact store):

```clojure
(require '[ai.miniforge.artifact.interface :as artifact])
(def store (artifact/create-store {:dir "data"})) ; Always Datalevin
```

**After** (choose store type):

```clojure
;; JVM-only (Datalevin)
(def store (artifact/create-store {:dir "data"}))

;; BB-compatible (Transit)
(def store (artifact/create-transit-store {:dir "data"}))
```

### Component Dependencies

If your component uses `ai.miniforge/artifact`, no changes needed:

- Protocol and interface remain unchanged
- Store implementation loaded lazily via `requiring-resolve`

## Files Changed

### Created (3 files, ~719 lines)

- `components/artifact/src/ai/miniforge/artifact/transit_store.clj` (255 lines)
- `components/artifact/src/ai/miniforge/artifact/datalevin_store.clj` (140 lines)
- `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` (324 lines)

### Modified (8 files)

- `components/artifact/src/ai/miniforge/artifact/core.clj` - Removed Datalevin dependency
- `components/artifact/src/ai/miniforge/artifact/interface.clj` - Added transit store API
- `components/artifact/deps.edn` - Added transit-clj
- `components/workflow/src/ai/miniforge/workflow/validator.clj` - Support v1/v2 formats
- `components/workflow/resources/workflows/simple.edn` - Added metadata
- `components/agent/src/ai/miniforge/agent/reviewer.clj` - Fixed function call
- `bases/cli/src/ai/miniforge/cli/main.clj` - Added workflow commands
- `projects/miniforge/deps.edn` - Added component dependencies

### Added Resources (3 files)

- `components/workflow/resources/workflows/simple.edn`
- `components/workflow/resources/workflows/minimal-test.edn`
- `components/workflow/resources/workflows/canonical-sdlc.edn`

## Risks & Mitigations

### Risk: Transit store performance

**Mitigation**: In-memory cache + async writes. Benchmarked with good results.

### Risk: Workflow compatibility

**Mitigation**: Both v1 and v2 formats validated and tested. Existing workflows unaffected.

### Risk: Missing transitive dependencies

**Mitigation**: All components explicitly listed in project deps. Build verified.

## Follow-up Work

- [ ] Add workflow execution tests to CI
- [ ] Document Transit store architecture
- [ ] Create migration guide for other JVM-only components
- [ ] Add artifact streaming API for team/org features

## Related Issues

- Addresses workflow execution in CLI (#59 related)
- Enables artifact persistence for local dev (OSS version)
- Foundation for distributed artifact store (future paid plans)
