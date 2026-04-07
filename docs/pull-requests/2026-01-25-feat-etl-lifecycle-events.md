# feat: ETL Lifecycle Events

## Overview

Adds two new ETL lifecycle events (`etl/completed` and `etl/failed`) to the event stream as specified in N3 §3.4. Enables observability and audit trails for ETL workflows that generate knowledge packs from repositories.

## Motivation

Spec enhancement PR #84 added event emission requirements for ETL workflows. Without these events:

- ETL workflow outcomes are not observable in the event stream
- Duration and performance metrics are not tracked
- Failure diagnostics require diving into logs
- Summary statistics (packs generated, risks found) are lost
- Audit trails for pack generation are incomplete

This PR implements structured lifecycle events for ETL workflows.

## Changes in Detail

### New Files

**`components/logging/src/ai/miniforge/logging/events/etl.clj`** (164 lines)

- **`etl/completed` event schema**:
  - `:etl/duration-ms` - Total ETL execution time
  - `:etl/summary` - Summary statistics:
    - `:packs-generated` - Number of knowledge packs created
    - `:packs-promoted` - Number of packs promoted to higher trust levels
    - `:high-risk-findings` - Security/policy violations detected
    - `:sources-processed` - Total source files/repos processed
- **`etl/failed` event schema**:
  - `:etl/failure-stage` - Which stage failed: `:classification`, `:scanning`, `:extraction`, or `:validation`
  - `:etl/failure-reason` - Human-readable failure reason
  - `:etl/error-details` - Structured error details (optional)
- Helper functions:
  - `emit-etl-completed` - Emit success event
  - `emit-etl-failed` - Emit failure event

**`components/workflow/src/ai/miniforge/workflow/etl.clj`** (253 lines)

- Full ETL pipeline implementation with 4 stages:
  1. **Classification** - Categorize sources by type (markdown, code, config)
  2. **Scanning** - Run security and policy scanners
  3. **Extraction** - Extract structured knowledge into packs
  4. **Validation** - Verify pack integrity and schemas
- Automatic lifecycle event emission:
  - `etl/completed` on successful pipeline completion
  - `etl/failed` on any stage failure with stage-specific context
- Comprehensive statistics tracking throughout execution
- Proper error handling with stage-specific failure tracking

### Modified Files

**`components/logging/src/ai/miniforge/logging/interface.clj`** (+41 lines)

- Exported `emit-etl-completed` function for public API
- Exported `emit-etl-failed` function for public API
- Both functions fully documented with examples

**`tests/conformance/conformance/event_stream_test.clj`** (+161 lines)

- **`etl-completed-event-test`**: Verifies `etl/completed` event emission and N3 §3.4 schema conformance
- **`etl-failed-event-test`**: Verifies `etl/failed` event emission with all required fields
- **`etl-failure-stages-test`**: Ensures all 4 failure stages are supported
- **`etl-workflow-integration-test`**: Tests ETL workflow integration with event stream

## Event Schemas

### `etl/completed` Event

```clojure
{:event/type :etl/completed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :etl/duration-ms 12450
 :etl/summary {:packs-generated 5
               :packs-promoted 2
               :high-risk-findings 0
               :sources-processed 12}

 :message "ETL workflow completed successfully"}
```

### `etl/failed` Event

```clojure
{:event/type :etl/failed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :etl/failure-stage :scanning  ; or :classification, :extraction, :validation
 :etl/failure-reason "Security scanner detected malicious patterns"
 :etl/error-details {:scanner "prompt-injection-tripwire"
                     :violation-count 3
                     :files ["docs/malicious.md"]}

 :message "ETL workflow failed"}
```

## ETL Pipeline Stages

1. **Classification** (`:classification`)
   - Inventories source files by path/type/metadata
   - Categorizes sources into candidate pack inputs
   - Failure: Unable to read sources or classify file types

2. **Scanning** (`:scanning`)
   - Runs deterministic sanitization and static scanners
   - Detects prompt injection, malicious code, policy violations
   - MUST NOT execute or evaluate untrusted content
   - Failure: High-risk findings or scanner errors

3. **Extraction** (`:extraction`)
   - Extracts normalized EDN packs from sources
   - Converts markdown/code/config into structured knowledge
   - Failure: Extraction errors or invalid source format

4. **Validation** (`:validation`)
   - Validates packs against schemas
   - Checks pack dependencies and trust levels
   - Emits pack index with content hashes and trust labels
   - Failure: Schema validation errors or invalid dependencies

## Testing Plan

- `bb test:conformance` - All conformance tests passing
- `bb test` - All existing tests passing (1,865 assertions)
- `bb pre-commit` - All checks passed

## Deployment Plan

- No special deployment steps; merge and release with OSS v1.0
- Breaking changes: None (additive only)
- Events are emitted automatically when ETL workflows run

## Related Issues/PRs

- Spec enhancement: PR #84 (ETL & Trust System Enhancements)
- N3 spec: §3.4 ETL and Pack Events
- ETL critical sequence: PR16/21 (this PR)
- Complements: PR14 (trust rules), PR15 (dependency validation), PR17 (promotion justification)

## Checklist

- [x] `etl/completed` event defined and emitted
- [x] `etl/failed` event defined and emitted
- [x] Events include required fields (duration, summary stats, failure stage, errors)
- [x] Events emitted in ETL workflows
- [x] Conformance tests verify emission
- [x] All 4 failure stages supported
- [x] ETL pipeline implemented (Classification → Scanning → Extraction → Validation)
- [x] Pre-commit validation passed
- [x] N3 §3.4 conformant
