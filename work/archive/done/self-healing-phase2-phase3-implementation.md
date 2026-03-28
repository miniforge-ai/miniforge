# Self-Healing System: Phase 2 & 3 Implementation Plan

## Context

This plan continues the self-healing system implementation. Phase 1 is complete, and partial Phase 2 work has been done.

## Already Completed

### Phase 1: Error Classifier Cleanup ✅

- Deleted old `error_classifier.clj` facade (400 lines removed)
- Updated `interface.clj` to import from subdirectories
- Fixed `extract-completed-work` to return `[]` instead of `nil`
- Copied error patterns to `components/agent-runtime/resources/error-patterns/`
- Added backend setup patterns for Codex, Ollama, Anthropic, OpenAI errors
- All 86 tests passing

### Phase 2: Partial Implementation ✅

- Created `components/self-healing/` structure
- Implemented `workaround_registry.clj` (~190 lines) with:
  - Persistent storage to `~/.miniforge/known_workarounds.edn`
  - Add/update/query/delete workarounds
  - Confidence tracking
  - High-confidence filtering
- Implemented `workaround_detector.clj` (~280 lines) with:
  - Pattern matching from `resources/error-patterns/*.edn`
  - Auto-fix with safety tiers (Tier 1: auto, Tier 2: prompt-once, Tier 3: always-prompt)
  - User approval tracking in `~/.miniforge/workaround_approvals.edn`
  - Shell command execution
  - Backend switching support
  - GitHub integration placeholder
- Created comprehensive tests for workaround registry
- Created `deps.edn` for self-healing component

## Remaining Work

### Phase 2: Complete Self-Healing System

#### Task 1: Implement Backend Health Tracker (~200 lines)

**File:** `components/self-healing/src/ai/miniforge/self_healing/backend_health.clj`

**Requirements:**

- Persistent storage: `~/.miniforge/backend_health.edn`
- Data structure:

  ```clojure
  {:backends
   {:anthropic {:total-calls 100 :successful-calls 95 :success-rate 0.95 :last-failure inst}
    :openai {:total-calls 50 :successful-calls 48 :success-rate 0.96 :last-failure inst}}
   :switch-cooldowns
   {:anthropic inst  ; 30 min cooldown after switch
    :openai inst}
   :default-backend :anthropic
   :fallback-order [:anthropic :openai :codex :ollama :google]}
  ```

**Functions to implement:**

- `load-health` - Load from disk
- `save-health!` - Save to disk (atomic)
- `record-backend-call!` - Track success/failure per backend
- `get-backend-success-rate` - Calculate rate
- `should-switch-backend?` - Check if rate < 0.90 threshold
- `in-cooldown?` - Check if within 30 min of last switch
- `select-best-backend` - Find healthy backend not in cooldown
- `trigger-backend-switch!` - Switch backend and record cooldown

**Algorithm:**

1. Track all backend calls with success/failure
2. If success rate drops below 90% (configurable)
3. And not in cooldown (30 min since last switch)
4. Switch to next healthy backend in fallback order
5. Emit `:self-healing/backend-switched` event

**Tests to write:**

- `components/self-healing/test/ai/miniforge/self_healing/backend_health_test.clj`
- Load/save roundtrip
- Record calls updates success rate correctly
- Success rate below threshold triggers switch
- Cooldown prevents rapid switching
- Select best backend skips unhealthy + cooldown backends

---

#### Task 2: Create Self-Healing Interface (~60 lines)

**File:** `components/self-healing/src/ai/miniforge/self_healing/interface.clj`

**Requirements:**

- Re-export all public functions from:
  - `workaround_registry` - load, add, get, update-stats
  - `workaround_detector` - detect-and-apply, match-error-to-workaround
  - `backend_health` - record-call, should-switch, select-best, trigger-switch

**Example structure:**

```clojure
(ns ai.miniforge.self-healing.interface
  (:require
   [ai.miniforge.self-healing.workaround-registry :as registry]
   [ai.miniforge.self-healing.workaround-detector :as detector]
   [ai.miniforge.self-healing.backend-health :as health]))

;; Workaround registry
(def load-workarounds registry/load-workarounds)
(def add-workaround! registry/add-workaround!)
;; ... etc
```

---

#### Task 3: Integrate with Workflow Runner (~50 lines)

**File:** `components/workflow/src/ai/miniforge/workflow/runner.clj`

**Requirements:**

1. Add self-healing require at top
2. Wrap agent/LLM execution to track backend health:

   ```clojure
   (defn- execute-with-health-tracking
     [backend operation-fn]
     (try
       (let [result (operation-fn)]
         (self-healing/record-backend-call! backend true)
         result)
       (catch Exception e
         (self-healing/record-backend-call! backend false)
         ;; Try workaround
         (let [workaround-result (self-healing/detect-and-apply-workaround e)]
           (if (:success? workaround-result)
             (operation-fn) ;; Retry
             (throw e))))))
   ```

3. Check if backend switch needed after phase completion
4. Emit events for workaround application and backend switches

**Integration points:**

- Find where LLM/agent calls are made
- Wrap with health tracking
- On error, try workaround before failing
- After workaround success, retry operation
- Check backend health at phase boundaries
- Switch backend if needed

---

#### Task 4: Add Self-Healing Configuration (~16 lines)

**Files to modify:**

1. `components/config/src/ai/miniforge/config/user.clj`
   - Add config loading for `:self-healing` section

2. `resources/config/default-user-config.edn`
   - Add default config:

     ```edn
     :self-healing
     {:enabled true
      :workaround-auto-apply true
      :backend-auto-switch true
      :backend-health-threshold 0.90
      :backend-switch-cooldown-ms 1800000}  ; 30 min
     ```

**Environment variables to support:**

- `MINIFORGE_SELF_HEALING_ENABLED`
- `MINIFORGE_SELF_HEALING_AUTO_APPLY`
- `MINIFORGE_BACKEND_AUTO_SWITCH`
- `MINIFORGE_BACKEND_HEALTH_THRESHOLD`

---

#### Task 5: Add Event Types (~5 lines)

**File:** `components/event-stream/src/ai/miniforge/event_stream/events.clj`

**Add event types:**

```clojure
:self-healing/workaround-applied
  {:workaround-id keyword
   :pattern-id keyword
   :success? boolean
   :message string}

:self-healing/backend-switched
  {:from keyword
   :to keyword
   :reason string
   :cooldown-until inst}
```

---

#### Task 6: Write Tests for New Components

**Tests needed:**

1. `backend_health_test.clj` - All backend health functions
2. `workaround_detector_test.clj` - Pattern matching, application, approval tracking
3. Integration test - Full self-healing flow (error → detect → apply → retry)

**Test coverage:**

- Backend health tracking and switching
- Workaround detection and application
- User approval tracking (always/never/once)
- Configuration loading
- Event emission

---

### Phase 3: PR 152 Conflict Resolution

#### Task 7: Resolve PR 152 Conflicts

**Branch:** `feat/intelligent-workflow-selection`

**Context:**

- PR 152 adds workflow selection logic
- PR 153 (merged to main) refactored error classifier and added model selection
- Conflicts in `error_classifier.clj` and `workflow_runner.clj`

**Steps:**

1. Checkout branch:

   ```bash
   git checkout feat/intelligent-workflow-selection
   ```

2. Rebase onto main:

   ```bash
   git rebase main
   ```

3. Resolve conflicts:
   - `error_classifier.clj` - Accept deletion (Phase 1 completed this)
   - Check if PR 152 added error patterns - if yes, migrate to `resources/error-patterns/workflow.edn`
   - `workflow_runner.clj` - Keep both changes (model selection + workflow selection)

4. Update imports:
   - Change any `error-classifier` imports to `error-classifier.core`

5. Run tests:

   ```bash
   clojure -M:dev:test
   ```

6. Verify functionality:
   - Model selection still works
   - Workflow selection works
   - Error classification works

7. Force push:

   ```bash
   git push origin feat/intelligent-workflow-selection --force-with-lease
   ```

**Acceptance criteria:**

- Branch rebased onto main
- All conflicts resolved
- Tests pass
- Both PR 152 and PR 153 features work together
- PR ready for review

---

## Implementation Guidelines

### For Phase 2

1. Follow the existing code style in `workaround_registry.clj` and `workaround_detector.clj`
2. Use stratified design with layered comments (`Layer 0`, `Layer 1`, etc.)
3. Include comprehensive docstrings
4. Write tests before/alongside implementation
5. Use atomic file writes for persistence (temp file + rename)
6. Emit events for visibility
7. Check configuration before applying auto-fixes

### For Phase 3

1. Read PR 152 changes carefully before rebasing
2. Test after each conflict resolution
3. Ensure backward compatibility
4. Don't break existing features

### Testing Strategy

- Unit tests for each component
- Integration tests for self-healing flow
- Manual testing with simulated backend failures
- Verify configuration changes take effect
- Check event stream shows self-healing events

---

## Success Criteria

**Phase 2 Complete When:**

- ✅ Backend health tracker implemented and tested
- ✅ Interface exports all functions
- ✅ Workflow runner integrates self-healing
- ✅ Configuration added with env var support
- ✅ Events added and emitted
- ✅ All tests pass (unit + integration)
- ✅ Self-healing works in real workflow runs

**Phase 3 Complete When:**

- ✅ PR 152 rebased onto main
- ✅ All conflicts resolved
- ✅ Tests pass
- ✅ Both features work together
- ✅ PR ready for review

---

## Files Reference

**Already created:**

- `components/self-healing/deps.edn`
- `components/self-healing/src/ai/miniforge/self_healing/workaround_registry.clj`
- `components/self-healing/src/ai/miniforge/self_healing/workaround_detector.clj`
- `components/self-healing/test/ai/miniforge/self_healing/workaround_registry_test.clj`
- `resources/error-patterns/backend-setup.edn`
- `components/agent-runtime/resources/error-patterns/backend-setup.edn`

**To create:**

- `components/self-healing/src/ai/miniforge/self_healing/backend_health.clj`
- `components/self-healing/src/ai/miniforge/self_healing/interface.clj`
- `components/self-healing/test/ai/miniforge/self_healing/backend_health_test.clj`
- `components/self-healing/test/ai/miniforge/self_healing/workaround_detector_test.clj`
- `resources/error-patterns/workflow.edn` (if PR 152 has patterns)

**To modify:**

- `components/workflow/src/ai/miniforge/workflow/runner.clj`
- `components/config/src/ai/miniforge/config/user.clj`
- `resources/config/default-user-config.edn`
- `components/event-stream/src/ai/miniforge/event_stream/events.clj`

---

## Start Here

Begin with **Task 1: Backend Health Tracker** as it's needed by the workflow integration. Then proceed sequentially through Tasks 2-7.

Good luck! 🚀
