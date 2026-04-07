# Dogfooding Session #2 - 2026-01-28

**Objective:** Complete interceptor-based workflow migration and wire up agent invocation

**Status:** MAJOR PROGRESS - Agents now being invoked! Running in mock mode until LLM configured.

---

## Completed Work

### ✅ Task #3: Update Workflow Validator (DONE)

**Changes:**

- Updated `components/workflow/src/ai/miniforge/workflow/validator.clj`
- Now supports both `:workflow/phases` (old) and `:workflow/pipeline` (new) formats
- Made `:workflow/created-at` and `:workflow/task-types` optional
- Both simple-v2.0.0.edn (new format) and simple-test-v1.0.0.edn (old format) now validate

**Result:** Can load workflows in either format ✅

---

### ✅ Task #1: Wire Up Agent Invocation (DONE - Plan & Implement phases)

**Changes:**

1. Updated `components/phase/src/ai/miniforge/phase/plan.clj`:
   - Added `agent.interface` require
   - `enter-plan` now creates planner agent and invokes it
   - `leave-plan` now extracts and merges metrics
   - Agent result stored in context

2. Updated `components/phase/src/ai/miniforge/phase/implement.clj`:
   - Added `agent.interface` require
   - `enter-implement` now creates implementer agent and invokes it
   - `leave-implement` now extracts and merges metrics
   - Passes plan result to implementer

**Pattern established:**

```clojure
(defn- enter-phase [ctx]
  (let [agent (agent/create-agent :role {})
        task {...}
        result (agent/invoke agent task ctx)]
    (assoc-in ctx [:phase :result] result)))
```

**Result:** Agents are actually being invoked! ✅

---

## Evidence of Progress

### Before (Session #1)

```clojure
:plan {:duration-ms 0, :status :completed}  ;; 0ms = stub
:implement {:duration-ms 0}                 ;; No work done
```

### After (Session #2)

```clojure
:plan {:status :completed
       :result {:success true
                :outputs []
                :decisions [:no-llm-backend]  ;; ← Agent was invoked!
                :signals [:mock-execution]     ;; ← Running in mock mode
                :metrics {:tokens-input 0
                         :tokens-output 0
                         :llm-calls 0}}}
```

Agents are **working** but running in **mock mode** because `:llm-backend` not in context.

---

## Next Steps

### Immediate (to get real LLM calls)

1. **Add LLM backend to workflow context**
   - Agents check `context[:llm-backend]` or `opts[:llm-backend]`
   - Need to wire LLM client creation in workflow runner
   - Configure API key (environment variable or config file)

2. **Test with real LLM**
   - Should see token usage > 0
   - Duration > 0ms
   - Actual plan/code artifacts

### Near-term (complete migration)

1. **Wire remaining phases** (verify, review, release)
   - Copy pattern from plan.clj/implement.clj
   - All phases should invoke their respective agents

2. **Task #2: Migrate workflows to pipeline format**
   - Convert canonical-sdlc-v1, lean-sdlc-v1, etc. to `:workflow/pipeline`
   - Remove old `:workflow/phases` format
   - Establish single canonical format

3. **Task #4: Remove obsolete code**
   - Delete `components/workflow/src/ai/miniforge/workflow/configurable.clj`
   - Clean up old workflow execution paths
   - Keep only interceptor-based runner

---

## Task Status

- [x] Task #3: Update workflow validator ✅ DONE
- [~] Task #1: Wire up agent invocation (2/5 phases done)
  - [x] plan.clj ✅
  - [x] implement.clj ✅
  - [ ] verify.clj
  - [ ] review.clj
  - [ ] release.clj
- [ ] Task #2: Migrate workflows to pipeline format
- [ ] Task #4: Remove obsolete code

---

## Files Changed This Session

### Modified

1. `components/workflow/src/ai/miniforge/workflow/validator.clj` (58 lines changed)
   - Support both :workflow/phases and :workflow/pipeline formats

2. `components/phase/src/ai/miniforge/phase/plan.clj` (32 lines changed)
   - Wire up planner agent invocation

3. `components/phase/src/ai/miniforge/phase/implement.clj` (34 lines changed)
   - Wire up implementer agent invocation

4. `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` (1 line changed)
   - Switch to simple (pipeline format) workflow

### Created

1. `docs/dogfooding-session-1.md` - First session notes
2. `docs/dogfooding-session-2.md` - This document
3. `examples/workflows/task-1-wire-agents.edn` - Workflow spec
4. `examples/workflows/task-3-update-validator.edn` - Workflow spec

**Total:** ~125 lines changed across 4 files

---

## Learnings

### What Worked Well

1. **Manual implementation was faster** than trying to use miniforge-building-miniforge for these changes
2. **Small, focused changes** - validator first, then plan phase, then implement phase
3. **Testing after each change** - caught issues early
4. **Pattern-based approach** - establish pattern in one file, copy to others

### Architecture Insights

1. **Interceptor pattern is clean** - enter/leave/error hooks work well
2. **Phase registry is flexible** - defaults can be overridden per-workflow
3. **Agent invocation is simple** - just create + invoke + store result
4. **Mock mode is useful** - can test without LLM configured

### Issues Discovered

1. **No LLM backend wiring** - agents fall back to mock execution
2. **Three remaining phases** need wiring (verify, review, release)
3. **Mixed workflow formats** causing confusion
4. **Old workflow runner still exists** (configurable.clj)

---

## Meta Observations

**Dogfooding effectiveness:**

- ✅ Successfully identified real issues (phase stubs, validator mismatch)
- ✅ Made actual progress on critical path
- ⚠️ Manual implementation was faster than using miniforge for this work
- ⚠️ Need LLM configured before miniforge can build itself

**Path forward:**

1. Configure LLM backend
2. Test with real workflow
3. If it works, use miniforge to build remaining phases
4. Then use miniforge to migrate workflows
5. Then use miniforge to clean up obsolete code

---

**Prepared by:** Claude Sonnet 4.5 (still dogfooding!)
**Date:** 2026-01-28
**Session Duration:** ~45 minutes
**Issues Fixed:** 2 major (validator, agent invocation)
**Remaining:** 1 critical (LLM configuration)
