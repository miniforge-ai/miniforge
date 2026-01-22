# Core Loop Flow Diagram

This document visualizes the complete flow through miniforge's core loop based on the
architecture defined in `complete-core-loop.spec.edn` and Phase 1 learnings.

## High-Level Overview

```text
Input Spec → OUTER LOOP (7 phases) → Merged PR → Observe
             ↑                                     ↓
             └─────── Meta Loop (improve) ─────────┘
```

## Detailed Phase Flow

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                           OUTER LOOP                                     │
│                                                                          │
│  Each phase has:                                                         │
│  - Inner loop (generate → validate → repair)                            │
│  - Gates that must pass before moving to next phase                     │
│  - Budget constraints (tokens, time, iterations)                        │
└─────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 0: INPUT                                                            │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Input: User requirement / PM Spec (.spec.edn file)                      │
│  Validates: Spec schema, completeness                                    │
│                                                                           │
│  Gates:                                                                   │
│  ✓ spec-schema-valid                                                     │
│  ✓ requirements-clear                                                    │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 1: PLAN                                                             │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Planner                                                           │
│  Input: PM Spec                                                           │
│  Output: Implementation plan with tasks                                   │
│                                                                           │
│  Inner Loop:                                                              │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Generate implementation plan                       │               │
│  │ 2. Validate plan (completeness, clarity)             │               │
│  │ 3. If invalid → Repair (refine plan) → goto 2        │               │
│  │ 4. If valid → Exit inner loop                         │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  PM Review Loop:                                                          │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ Plan ←──→ PM Reviewer validates plan                  │               │
│  │           (feasibility, scope, approach)              │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Gates:                                                                   │
│  ✓ plan-complete                                                         │
│  ✓ plan-approved-by-pm                                                   │
│  ✓ tasks-well-defined                                                    │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 2: DESIGN                                                           │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Designer                                                          │
│  Input: Implementation plan                                               │
│  Output: Detailed design (architecture, interfaces, data flow)            │
│                                                                           │
│  Inner Loop:                                                              │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Generate design (namespaces, functions, schemas)  │               │
│  │ 2. Validate design (layering, dependencies)          │               │
│  │ 3. If invalid → Repair (adjust design) → goto 2      │               │
│  │ 4. If valid → Exit inner loop                         │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Dev Review Loop:                                                         │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ Design ←──→ Dev Reviewer validates design             │               │
│  │             (architecture, patterns, stratification)  │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Gates:                                                                   │
│  ✓ design-complete                                                       │
│  ✓ architecture-sound                                                    │
│  ✓ no-circular-dependencies                                              │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 3: TEST PLANNING                                                    │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Test Planner                                                      │
│  Input: Design + Spec                                                     │
│  Output: Test plan (test cases, coverage targets, assertions)             │
│                                                                           │
│  Inner Loop:                                                              │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Generate test plan from design + spec             │               │
│  │ 2. Validate test plan (coverage, edge cases)         │               │
│  │ 3. If invalid → Repair (add tests) → goto 2          │               │
│  │ 4. If valid → Exit inner loop                         │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Multi-Agent Review Loop:                                                 │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ Test Plan ←──→ PM Reviewer (requirements coverage)    │               │
│  │           ←──→ Dev Reviewer (testability)             │               │
│  │           ←──→ Test Reviewer (quality, completeness)  │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Gates:                                                                   │
│  ✓ test-plan-complete                                                    │
│  ✓ coverage-targets-defined                                              │
│  ✓ approved-by-reviewers                                                 │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 4: IMPLEMENT (Tests First)                                         │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Tester (implements test code)                                     │
│  Input: Test plan                                                         │
│  Output: Test code (.clj test files)                                      │
│                                                                           │
│  Inner Loop:                                                              │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Generate test code from test plan                 │               │
│  │ 2. Validate syntax, lint                             │               │
│  │ 3. If invalid → Repair (fix errors) → goto 2         │               │
│  │ 4. If valid → Exit inner loop                         │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Gates:                                                                   │
│  ✓ syntax-valid                                                          │
│  ✓ lint-clean                                                            │
│  ✓ tests-compile                                                         │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 5: IMPLEMENT (Production Code)                                     │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Implementer                                                       │
│  Input: Design + Test code                                                │
│  Output: Production code (.clj implementation files)                      │
│                                                                           │
│  Inner Loop (TDD):                                                        │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Generate implementation to satisfy tests          │               │
│  │ 2. Run tests                                          │               │
│  │ 3. If tests fail → Repair (fix code) → goto 2        │               │
│  │ 4. Validate syntax, lint                             │               │
│  │ 5. If invalid → Repair (fix) → goto 4                │               │
│  │ 6. If all valid → Exit inner loop                     │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Gates:                                                                   │
│  ✓ syntax-valid                                                          │
│  ✓ lint-clean                                                            │
│  ✓ tests-pass                                                            │
│  ✓ no-debug-statements                                                   │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 6: VERIFY                                                           │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Tester (runs comprehensive test suite)                            │
│  Input: Production code + Test code                                       │
│  Output: Test results + Coverage report                                   │
│                                                                           │
│  Actions:                                                                 │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Run full test suite (poly test :all)              │               │
│  │ 2. Generate coverage report                           │               │
│  │ 3. Check coverage thresholds                          │               │
│  │ 4. Validate all assertions pass                       │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Gates:                                                                   │
│  ✓ all-tests-pass                                                        │
│  ✓ coverage >= threshold (70-80%)                                        │
│  ✓ no-flaky-tests                                                        │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 7: REVIEW                                                           │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Reviewer                                                          │
│  Input: All code (prod + tests) + Test results                            │
│  Output: Review comments + Approval/Rejection                             │
│                                                                           │
│  Inner Loop:                                                              │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Run all checks:                                    │               │
│  │    - clj-kondo (linting)                              │               │
│  │    - Stratification validation (max 3 layers)         │               │
│  │    - Naming conventions                               │               │
│  │    - Documentation completeness                       │               │
│  │    - Test coverage threshold                          │               │
│  │    - Rich comment examples accuracy                   │               │
│  │ 2. If issues found → Generate feedback                │               │
│  │ 3. Send to Implementer → Repair → goto 1             │               │
│  │ 4. If all checks pass → Approve                       │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Gates:                                                                   │
│  ✓ lint-clean                                                            │
│  ✓ coverage >= threshold (80%)                                           │
│  ✓ stratification-valid                                                  │
│  ✓ docs-complete                                                         │
│  ✓ reviewer-approved                                                     │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 8: RELEASE                                                          │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Release Manager                                                   │
│  Input: Approved code                                                     │
│  Output: Pull Request                                                     │
│                                                                           │
│  Actions:                                                                 │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Create feature branch (if not exists)             │               │
│  │ 2. Commit all changes                                 │               │
│  │ 3. Push to remote                                     │               │
│  │ 4. Create Pull Request with summary                   │               │
│  │ 5. Wait for CI checks                                 │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  PR Monitoring Loop:                                                      │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ PR Created → CI Runs → Monitor status                 │               │
│  │              ↓                                         │               │
│  │         CI Failures? → Fetch logs                     │               │
│  │              ↓                                         │               │
│  │         Parse errors → Back to IMPLEMENT              │               │
│  │              (Inner loop repairs)                     │               │
│  │              ↓                                         │               │
│  │         CI Success? → Check PR comments               │               │
│  │              ↓                                         │               │
│  │         Comments? → Address feedback                  │               │
│  │              ↓         Back to REVIEW/IMPLEMENT       │               │
│  │         No issues? → Ready for merge                  │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Gates:                                                                   │
│  ✓ ci-lint-pass                                                          │
│  ✓ ci-test-pass                                                          │
│  ✓ ci-build-pass                                                         │
│  ✓ no-merge-conflicts                                                    │
│  ✓ pr-approved                                                           │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ Phase 9: OBSERVE                                                          │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Observer                                                          │
│  Input: Merged PR + CI results + Metrics                                  │
│  Output: Performance report + Learnings                                   │
│                                                                           │
│  Actions:                                                                 │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ 1. Collect metrics:                                   │               │
│  │    - Build time                                       │               │
│  │    - Test execution time                              │               │
│  │    - Number of iterations per phase                   │               │
│  │    - Token usage per agent                            │               │
│  │    - Repair cycles needed                             │               │
│  │ 2. Analyze outcomes:                                  │               │
│  │    - Did it meet requirements?                        │               │
│  │    - Were there bugs caught?                          │               │
│  │    - What could be improved?                          │               │
│  │ 3. Generate learnings for Meta loop                   │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Output Artifacts:                                                        │
│  - Performance metrics                                                    │
│  - Success/failure analysis                                               │
│  - Recommendations for heuristic tuning                                   │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓

┌──────────────────────────────────────────────────────────────────────────┐
│ META LOOP: Continuous Improvement                                         │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                                           │
│  Agent: Meta Learner                                                      │
│  Input: Observations from multiple runs                                   │
│  Output: Updated heuristics (prompts, thresholds, strategies)             │
│                                                                           │
│  Meta Review Loop:                                                        │
│  ┌──────────────────────────────────────────────────────┐               │
│  │ Analyze patterns:                                     │               │
│  │ - Which prompts led to fewer repairs?                │               │
│  │ - Which coverage thresholds caught most bugs?        │               │
│  │ - Which repair strategies converged fastest?         │               │
│  │                                                       │               │
│  │ Propose heuristic updates:                           │               │
│  │ - New prompt versions (implementer, tester, etc.)    │               │
│  │ - Adjusted thresholds (coverage, complexity)         │               │
│  │ - Improved repair strategies                         │               │
│  │                                                       │               │
│  │ Store in Heuristic component:                        │               │
│  │ - Version new heuristics                             │               │
│  │ - A/B test against current version                   │               │
│  │ - Promote if better outcomes                         │               │
│  └──────────────────────────────────────────────────────┘               │
│                                                                           │
│  Feeds back into: All future runs use improved heuristics                │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
                              ↓
                    (Back to Phase 0 for next task)
```

## Key Components

### Gates (Policy Component)

Each phase transition requires passing validation gates:

- **Syntax gates** - Code parses correctly
- **Lint gates** - No clj-kondo errors
- **Test gates** - All tests pass
- **Coverage gates** - Meets threshold (70-80%)
- **Review gates** - Manual or automated approval

From Phase 1: We built the Policy component that evaluates these gates.

### Inner Loops (Loop Engine)

Within each phase, agents iterate until valid:

```text
Generate → Validate → Repair
   ↑          ↓
   └──────────┘ (repeat until valid or budget exhausted)
```

### Heuristics (Heuristic Component)

Stored and versioned:

- **Prompts** - System prompts for each agent type
- **Thresholds** - Coverage %, max iterations, token limits
- **Repair strategies** - How to fix common issues

From Phase 1: We built the Heuristic component for versioned storage.

### Budget Tracking (Policy Component)

Each phase tracks:

- **Token budget** - Max tokens per LLM call
- **Time budget** - Max wall-clock time
- **Iteration budget** - Max repair cycles in inner loop

## Comparison to Your Expected Flow

Your expected flow:

```text
PM Spec <-loop-> PM Review
  ↓
Dev Spec <-loop-> Dev review
  ↓
Test Plan <-loop-> [pm, dev, test reviewer]
  ↓
test implementation
  ↓
dev implementation <- loop -> test
  ↓
pr monitoring <-loop-> pr comments
  ↓
ready for merge
  ↓
Meta loop <-loop-> Meta
```

Our implemented flow matches this with more detail:

| Your Phase | Our Phases | Notes |
|------------|-----------|-------|
| PM Spec <-loop-> PM Review | Phase 0-1: INPUT → PLAN | PM reviews plan |
| Dev Spec <-loop-> Dev review | Phase 2: DESIGN | Dev reviewer validates design |
| Test Plan <-loop-> [reviewers] | Phase 3: TEST PLANNING | Multi-agent review |
| test implementation | Phase 4: IMPLEMENT (Tests) | Tester agent writes tests |
| dev implementation <- loop -> test | Phase 5: IMPLEMENT (Code) | Inner loop with test validation |
| pr monitoring <-loop-> pr comments | Phase 8: RELEASE | PR monitoring loop |
| ready for merge | Phase 8 gates | CI pass + approval |
| Meta loop <-loop-> Meta | META LOOP | Heuristic improvement |

## What Phase 2 Will Build

Based on this flow, Phase 2 needs:

1. **Tester Agent**
   - Generates test code from test plans
   - Validates tests compile and run
   - Participates in TEST PLANNING review

2. **Reviewer Agent**
   - Validates code quality (lint, stratification, docs)
   - Checks test coverage thresholds
   - Approves/rejects with feedback
   - Participates in DESIGN and TEST PLANNING review

3. **Integration Points**
   - Wire agents into outer loop phase transitions
   - Implement gate evaluation at each transition
   - Enable inner loop repairs within phases
   - Add budget tracking and enforcement

## What We Already Have (Phase 1)

- ✅ **Policy Component** - Gate evaluation, budget tracking
- ✅ **Heuristic Component** - Versioned storage for prompts/thresholds
- ✅ **Validation** - Pre-commit hooks, CI logging
- ✅ **Foundation** - Polylith structure, testing framework

## Next Steps

Phase 2 will make this flow real by:

1. Implementing Tester agent with test generation
2. Implementing Reviewer agent with validation logic
3. Wiring agents into the outer loop
4. Testing the full flow on a simple feature
5. Dogfooding: miniforge implements Phase 3 using Phase 2 agents
