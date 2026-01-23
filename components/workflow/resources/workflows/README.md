# Workflow Catalog

This directory contains configurable workflow definitions in EDN format. Each workflow represents a complete SDLC flow that can be executed by the configurable workflow engine.

## Workflow Index

### Production Workflows

| Workflow ID | Version | Purpose | Phases | Complexity |
|-------------|---------|---------|--------|------------|
| `canonical-sdlc-v1` | 1.0.0 | Complete SDLC with all phases | 10 | High |
| `lean-sdlc-v1` | 1.0.0 | Fast iteration for small changes | 6 | Medium |

### Test Workflows

| Workflow ID | Version | Purpose | Phases | Complexity |
|-------------|---------|---------|--------|------------|
| `simple-test-v1` | 1.0.0 | Integration testing & demos | 3 | Low |
| `minimal-test-v1` | 1.0.0 | Unit testing workflow basics | 1 | Minimal |

---

## Production Workflows

### canonical-sdlc-v1.0.0

**Purpose**: Complete, production-grade SDLC workflow with comprehensive review loops.

**Phases**:

1. PM Spec → PM Review
2. Dev Spec → Dev Review
3. Test Plan → Multi-reviewer Review
4. Test Implementation
5. Dev Implementation → Test Loop
6. PR Monitoring → PR Comment Loop
7. Ready for Merge
8. Meta Loop
9. Observe

**Characteristics**:

- **Max iterations**: 100
- **Max time**: 2 hours
- **Max tokens**: 200,000
- **Gates**: Comprehensive (syntax, lint, tests, policy, reviews)
- **Review loops**: Multiple reviewers at each phase
- **Best for**: Large features, critical changes, team collaboration

**Task types**: `:feature`, `:major-refactor`, `:architecture-change`

---

### lean-sdlc-v1.0.0

**Purpose**: Streamlined workflow optimized for speed and rapid iteration.

**Phases**:

1. Input Validation
2. Planning & Design (combined)
3. TDD Implementation (tests + code combined)
4. Quick Review
5. Release
6. Observe

**Characteristics**:

- **Max iterations**: 50
- **Max time**: 1 hour
- **Max tokens**: 100,000
- **Gates**: Essential only (syntax, lint, tests)
- **Review loops**: Single reviewer, fewer rounds
- **Best for**: Small features, bug fixes, documentation

**Task types**: `:small-feature`, `:bugfix`, `:docs`

---

## Test Workflows

### simple-test-v1.0.0

**Purpose**: Minimal workflow for integration testing and demonstrations.

**Phases**:

1. **Plan**: Create implementation plan
2. **Implement**: Generate code from plan
3. **Done**: Terminal phase (no agent)

**Characteristics**:

- **Max iterations**: 20
- **Max time**: 5 minutes
- **Max tokens**: 20,000
- **Gates**: Syntax only (on implement phase)
- **Review loops**: None
- **Best for**: Testing workflow execution, demos, tutorials

**Task types**: `:test`, `:demo`, `:tutorial`

**Use cases**:

- Integration testing the configurable workflow engine
- Demonstrating workflow execution flow
- Tutorial examples for new users
- Fast iteration during development

**Phase details**:

```clojure
;; Plan phase
{:phase/id :plan
 :phase/agent :planner
 :phase/inner-loop {:max-iterations 3}
 :phase/gates []
 :phase/next [{:target :implement}]}

;; Implement phase
{:phase/id :implement
 :phase/agent :implementer
 :phase/inner-loop {:max-iterations 5}
 :phase/gates [:syntax-valid]
 :phase/next [{:target :done}]}

;; Done phase
{:phase/id :done
 :phase/agent :none
 :phase/next []}
```

---

### minimal-test-v1.0.0

**Purpose**: Absolute minimal workflow for unit testing workflow basics.

**Phases**:

1. **Plan**: Single phase that creates a plan and terminates

**Characteristics**:

- **Max iterations**: 5
- **Max time**: 1 minute
- **Max tokens**: 5,000
- **Gates**: None
- **Review loops**: None
- **Best for**: Unit testing, fastest possible execution

**Task types**: `:test`, `:unit-test`

**Use cases**:

- Unit testing workflow state management
- Testing phase execution in isolation
- Validating workflow configuration loading
- Performance testing (minimal overhead)
- CI/CD smoke tests

**Phase details**:

```clojure
;; Plan phase (terminal)
{:phase/id :plan
 :phase/agent :planner
 :phase/inner-loop {:max-iterations 2}
 :phase/gates []
 :phase/next []}  ; No transitions - terminal
```

---

## Workflow Selection Guide

### When to use each workflow

**canonical-sdlc-v1**:

- ✅ Large features requiring multiple phases
- ✅ Critical changes needing thorough review
- ✅ Team collaboration with multiple reviewers
- ✅ Complex architectures
- ❌ Time-sensitive small fixes
- ❌ Solo development iterations

**lean-sdlc-v1**:

- ✅ Small, isolated features
- ✅ Bug fixes
- ✅ Documentation updates
- ✅ Low-risk changes
- ✅ Rapid prototyping
- ❌ Complex multi-component changes
- ❌ Architecture decisions

**simple-test-v1**:

- ✅ Integration testing workflows
- ✅ Demos and tutorials
- ✅ Development/debugging workflow engine
- ✅ Learning the system
- ❌ Production use

**minimal-test-v1**:

- ✅ Unit testing
- ✅ CI smoke tests
- ✅ Performance benchmarking
- ✅ Configuration validation
- ❌ Any real work

---

## Workflow File Structure

All workflow files follow this structure:

```clojure
{:workflow/id keyword          ; Unique identifier
 :workflow/version string       ; Semantic version
 :workflow/name string          ; Human-readable name
 :workflow/description string   ; Purpose and details
 :workflow/created-at inst      ; Creation timestamp
 :workflow/task-types [keyword] ; Suitable task types
 :workflow/entry keyword        ; Entry phase ID

 :workflow/phases
 [{:phase/id keyword           ; Unique phase identifier
   :phase/name string          ; Human-readable name
   :phase/description string   ; Phase purpose
   :phase/agent keyword        ; Agent type (:planner, :implementer, etc.)
   :phase/actions [keyword]    ; Actions performed
   :phase/inner-loop map       ; Inner loop configuration
   :phase/gates [keyword]      ; Validation gates
   :phase/budget map           ; Resource limits
   :phase/next [map]           ; Phase transitions
   :phase/metrics [keyword]}]  ; Metrics to collect

 :workflow/config
 {:parallel-phases [keyword]          ; Phases that can run in parallel
  :failure-strategy keyword           ; :retry, :fail-fast, :rollback
  :max-total-iterations int           ; Global iteration limit
  :max-total-time-seconds int         ; Global time limit
  :max-total-tokens int}              ; Global token budget

 :workflow/metrics map}               ; Metrics tracking
```

---

## Adding New Workflows

To add a new workflow:

1. **Create EDN file**: `components/workflow/resources/workflows/your-workflow-v1.0.0.edn`

2. **Follow naming convention**: `{workflow-id}-v{version}.edn`

3. **Include all required fields**: See structure above

4. **Validate structure**: Run tests to ensure proper format

5. **Document here**: Add entry to this README

6. **Test execution**: Create integration test in `test/` directory

---

## Version History

| Workflow | Versions | Latest | Notes |
|----------|----------|--------|-------|
| canonical-sdlc | 1.0.0 | 1.0.0 | Initial release |
| lean-sdlc | 1.0.0 | 1.0.0 | Initial release |
| simple-test | 1.0.0 | 1.0.0 | Initial release |
| minimal-test | 1.0.0 | 1.0.0 | Initial release |

---

## Meta Loop Integration

All workflows feed metrics into the Meta Loop for optimization:

- **Success rate**: Percentage of workflows completing successfully
- **Avg iterations**: Mean iterations per phase
- **Avg time**: Mean execution time
- **Avg tokens**: Mean token usage
- **Repair cycles**: Mean repair attempts per phase

The Meta Loop uses these metrics to:

1. Compare workflow effectiveness
2. Suggest optimizations
3. Generate new workflow variants
4. Evolve SDLC processes over time

See `docs/research/workflow-optimization-theory.md` for details on workflow evolution and optimization strategies.
