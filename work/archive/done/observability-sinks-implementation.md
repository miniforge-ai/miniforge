# Observability: Configurable Sinks Implementation

## Summary

Implemented a flexible sink architecture for events and logs that supports multiple deployment scenarios (local dev, containers, fleet operations) with configurable outputs.

## What Was Implemented

### 1. Event Sinks (`event-stream/sinks.clj`)

**Sink Types:**

- `:file` - Per-workflow files at `~/.miniforge/events/<workflow-id>.edn`
- `:stdout` - Print to stdout (EDN or JSON format)
- `:stderr` - Print to stderr (error events only)
- `:fleet` - Batch HTTP upload to fleet command
- `:multi` - Combine multiple sinks

**Features:**

- Automatic sink creation from config
- Environment variable support
- Batching for fleet sink (reduces HTTP calls)
- Silent failure (don't break event stream)

### 2. Log Sinks (`logging/sinks.clj`)

**Sink Types:**

- `:file` - Per-workflow files at `~/.miniforge/logs/<workflow-id>.log` (with rotation)
- `:stdout` - Print to stdout (human or EDN format)
- `:stderr` - Print to stderr (warn/error only)
- `:fleet` - Batch HTTP upload to fleet command
- `:multi` - Combine multiple sinks

**Features:**

- Automatic log rotation (10MB default)
- Retention management (7 days default)
- Min-level filtering per sink
- Format options (EDN vs human-readable)

### 3. Core Module Updates

**event-stream/core.clj:**

- Removed hardcoded file persistence
- Added `:sinks` parameter to `create-event-stream`
- Added `:config` parameter for automatic sink creation
- `publish!` now writes to all configured sinks

**logging/core.clj:**

- Updated `create-logger` to accept `:sinks` and `:config`
- Deprecated old `file-output-fn` (kept for backward compatibility)
- Uses `requiring-resolve` for lazy loading of sinks module

### 4. Configuration

**Default config (`resources/config/default-user-config.edn`):**

```edn
{:observability {:event-sinks [:file]
                 :log-sinks [:file]
                 :rotation {:max-size-mb 10
                            :retention-days 7}
                 :fleet {:url nil
                         :api-key nil
                         :batch-size 50
                         :flush-interval-ms 10000}}}
```

**Environment Variables:**

- `MINIFORGE_EVENT_SINKS=file,stdout,fleet`
- `MINIFORGE_LOG_SINKS=file,stdout`
- `MINIFORGE_FLEET_URL=https://fleet.miniforge.ai/api`
- `MINIFORGE_FLEET_API_KEY=your-api-key`

### 5. Observability Commands (`cli/observability.clj`)

**Commands:**

- `mf logs tail <workflow-id>` - Tail logs for specific workflow
- `mf logs list` - List available workflow log files
- `mf logs cleanup` - Clean up old rotated logs
- `mf events tail <workflow-id>` - Tail events for specific workflow
- `mf events list` - List available workflow event files

**Options:**

- `-n, --lines N` - Number of initial lines to show
- `-f, --follow` - Follow mode (tail -f)
- `--filter TYPE` - Filter events by type
- `--all` - Tail all workflows (not yet implemented)

## Deployment Scenarios

### 1. Local Development (Default)

```edn
{:observability {:event-sinks [:file]
                 :log-sinks [:file]}}
```

Events/logs written to `~/.miniforge/events/` and `~/.miniforge/logs/`

### 2. Container/Docker

```bash
export MINIFORGE_EVENT_SINKS=stdout
export MINIFORGE_LOG_SINKS=stdout
```

All output to stdout for Docker log collection

### 3. Kubernetes

```bash
export MINIFORGE_EVENT_SINKS=stdout
export MINIFORGE_LOG_SINKS=stdout,stderr
```

Events to stdout, errors to stderr (K8s log aggregation)

### 4. Fleet Operations

```edn
{:observability {:event-sinks [:file :fleet]
                 :log-sinks [:file :fleet]
                 :fleet {:url "https://fleet.miniforge.ai/api"
                         :api-key "prod-key-123"}}}
```

Local files + centralized fleet logging

### 5. Multi-Sink (All Destinations)

```edn
{:observability {:event-sinks [:file :stdout :fleet]
                 :log-sinks [:file :stdout :fleet]}}
```

Write to files, stdout, AND fleet simultaneously

## Usage Examples

### Event Stream

```clojure
(require '[ai.miniforge.event-stream.interface :as es])
(require '[ai.miniforge.event-stream.sinks :as event-sinks])

;; Default file sink
(def stream (es/create-event-stream))

;; Custom sinks
(def stream (es/create-event-stream
              {:sinks [(event-sinks/file-sink)
                       (event-sinks/stdout-sink)]}))

;; From config
(def stream (es/create-event-stream {:config user-config}))

;; Publish event (goes to all sinks)
(es/publish! stream (es/workflow-started stream wf-id spec))
```

### Logging

```clojure
(require '[ai.miniforge.logging.interface :as log])
(require '[ai.miniforge.logging.sinks :as log-sinks])

;; Default stdout logger
(def logger (log/create-logger {:output :human}))

;; With configurable sinks
(def logger (log/create-logger {:config user-config}))

;; With explicit sinks
(def logger (log/create-logger
              {:sinks [(log-sinks/file-sink)
                       (log-sinks/stdout-sink)]}))

;; Log with workflow context
(def logger-with-wf (log/with-context logger {:workflow/id wf-id}))
(log/info logger-with-wf :task :started {:message "Processing started"})
```

## Architecture Diagrams

### Event Flow

```
Workflow Execution
        ↓
  publish! event
        ↓
   ┌────┴─────┐
   │  Sinks   │
   └────┬─────┘
        ├─→ File Sink → ~/.miniforge/events/<wf-id>.edn
        ├─→ Stdout Sink → Docker logs
        └─→ Fleet Sink → HTTP POST /api/events
```

### Log Flow

```
Logger.log*
     ↓
  Sinks
     ↓
     ├─→ File Sink → ~/.miniforge/logs/<wf-id>.log (rotated)
     ├─→ Stdout Sink → Container stdout
     ├─→ Stderr Sink → Container stderr (errors only)
     └─→ Fleet Sink → HTTP POST /api/logs (batched)
```

## File Rotation & Cleanup

### Automatic Rotation

- Triggers when file exceeds max size (default 10MB)
- Renames to `<workflow-id>.log.20260209-143022`
- Creates new file for continued logging

### Retention

- Rotated files kept for 7 days (configurable)
- Manual cleanup: `mf logs cleanup`
- Automatic cleanup on next write (optional)

## Fleet Command API (To Implement)

### POST /api/events

```json
{
  "events": [
    {"event/type": "workflow/started", "workflow/id": "uuid", ...},
    {"event/type": "phase/completed", "workflow/id": "uuid", ...}
  ]
}
```

### POST /api/logs

```json
{
  "logs": [
    { "log/level": "info", "workflow/id": "uuid", "message": "..." },
    { "log/level": "error", "workflow/id": "uuid", "message": "..." }
  ]
}
```

## Benefits

1. **Flexibility**: Works in any deployment scenario (local, container, fleet)
2. **Multi-Sink**: Write to multiple destinations simultaneously
3. **Configuration**: Control via config file or environment variables
4. **No Breaking Changes**: Backward compatible with existing code
5. **Silent Failure**: Sink failures don't break workflows
6. **Batching**: Efficient HTTP uploads to fleet command
7. **Rotation**: Automatic log rotation prevents disk fill

## Files Modified

- `components/event-stream/src/ai/miniforge/event_stream/core.clj` - Configurable sinks
- `components/event-stream/src/ai/miniforge/event_stream/interface.clj` - Updated docs
- `components/event-stream/src/ai/miniforge/event_stream/sinks.clj` - NEW
- `components/logging/src/ai/miniforge/logging/core.clj` - Configurable sinks
- `components/logging/src/ai/miniforge/logging/sinks.clj` - NEW
- `resources/config/default-user-config.edn` - Added :observability section
- `bases/cli/src/ai/miniforge/cli/observability.clj` - NEW
- `bases/cli/src/ai/miniforge/cli/main.clj` - Added logs/events commands

## Testing

### Manual Testing

**1. File Sinks (Default):**

```bash
# Run workflow
mf run work/test.edn

# Check files created
ls -lh ~/.miniforge/events/
ls -lh ~/.miniforge/logs/

# Tail logs
mf logs tail <workflow-id>
mf events tail <workflow-id>
```

**2. Stdout Sink:**

```bash
# Set env var
export MINIFORGE_EVENT_SINKS=stdout
export MINIFORGE_LOG_SINKS=stdout

# Run workflow - should see output on stdout
mf run work/test.edn
```

**3. Multi-Sink:**

```bash
# Both file and stdout
export MINIFORGE_EVENT_SINKS=file,stdout
mf run work/test.edn

# Check both locations
mf logs tail <workflow-id>  # File
# AND stdout during execution
```

## Next Steps

1. ✅ Implement basic sinks (file, stdout, stderr)
2. ✅ Update core modules to use sinks
3. ✅ Add configuration support
4. ✅ Add observability CLI commands
5. ⏳ Implement fleet sink HTTP client
6. ⏳ Add aggregated tailing (`--all` flag)
7. ⏳ Add metrics (events/logs sent, failures)
8. ⏳ Add sink health monitoring
9. ⏳ Add compression for fleet uploads

## Backward Compatibility

All existing code continues to work:

- `(create-event-stream)` - Uses default file sink
- `(create-logger {:output :human})` - Uses stdout
- Old `file-output-fn` - Still works (deprecated)

New code can opt-in to configurable sinks:

- `(create-event-stream {:config user-config})`
- `(create-logger {:config user-config})`
