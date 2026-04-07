<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Meta-Agent Architecture

> Meta-agents are a **Miniforge SDLC** feature, built on top of the MiniForge Core workflow engine.

Miniforge uses a **team of specialized meta-agents** to monitor and control workflow execution. This is
analogous to how the main workflow uses specialized agents (Planner, Implementer, Tester, Reviewer), but
applied at the meta level.

## Philosophy

### Distributed Authority, Not Centralized Control

- Any meta-agent can halt the factory floor
- Coordinator is a lightweight message router, not a controller
- Each meta-agent has focused domain expertise
- Decisions are observable and logged

## Meta-Agent Roles

### Progress Monitor `:progress-monitor`

**Responsibility:** Detect workflow stagnation

**Halts When:**

- No streaming activity for 2 minutes (configurable)
- Hard timeout of 10 minutes exceeded (configurable)
- Repeated "thinking" messages without file writes

**Signals Healthy When:**

- Tokens streaming
- Files being written
- Unique content appearing

**Configuration:**

```clojure
{:id :progress-monitor
 :enabled? true
 :config {:stagnation-threshold-ms 120000
          :max-total-ms 600000}}
```

### Test Quality Enforcer `:test-quality`

**Responsibility:** Ensure test coverage and quality

**Halts When:**

- Test coverage below threshold
- No tests generated for new code
- Tests don't exercise key paths

**Signals Warning When:**

- Coverage declining
- Flaky tests detected
- Test quality metrics below par

**Configuration:**

```clojure
{:id :test-quality
 :enabled? true
 :config {:min-coverage-percent 80
          :require-tests-for-new-code true}}
```

### Conflict Detector `:conflict-detector`

**Responsibility:** Detect and prevent merge conflicts

**Halts When:**

- Merge conflicts detected
- Branch diverged significantly from main
- Conflicting file modifications

**Signals Warning When:**

- Branch aging (needs rebase)
- Potential conflicts in same files

**Configuration:**

```clojure
{:id :conflict-detector
 :enabled? true
 :config {:max-branch-age-hours 24
          :check-interval-ms 15000}}
```

### Resource Manager `:resource-manager`

**Responsibility:** Track costs and resource usage

**Halts When:**

- Token budget exhausted
- Cost limit exceeded
- API rate limit approaching

**Signals Warning When:**

- 80% of budget consumed
- High token usage detected

**Configuration:**

```clojure
{:id :resource-manager
 :enabled? true
 :config {:max-tokens 150000
          :max-cost-usd 5.00
          :warn-threshold 0.8}}
```

### Evidence Collector `:evidence-collector`

**Responsibility:** Gather workflow artifacts and metrics

**Never Halts** (informational only)

**Collects:**

- Test results
- Code artifacts
- Policy gate decisions
- Timing metrics

**Configuration:**

```clojure
{:id :evidence-collector
 :enabled? true
 :config {:collect-artifacts true
          :collect-metrics true}}
```

## Protocol

All meta-agents implement the `MetaAgent` protocol:

```clojure
(defprotocol MetaAgent
  (check-health [this workflow-state]
    "Returns {:status :healthy|:warning|:halt
              :agent/id keyword
              :message string
              :data map}")

  (get-meta-config [this]
    "Returns {:id keyword
              :name string
              :can-halt? boolean
              :check-interval-ms int
              :priority :high|:medium|:low}")

  (reset-state! [this]
    "Reset internal state for workflow restart"))
```

## Coordinator

The `MetaCoordinator` routes workflow state to all enabled meta-agents and aggregates their decisions:

```clojure
(def coordinator (create-coordinator
                  [progress-monitor
                   test-quality
                   conflict-detector]))

(def result (check-all-agents coordinator workflow-state))
;; => {:status :halt
;;     :halting-agent :progress-monitor
;;     :halt-reason "No progress for 2 minutes"
;;     :checks [...]}
```

**Key Behaviors:**

1. **Interval-based Checking** - Each agent checked according to its interval
2. **Any Can Halt** - First halt signal stops workflow immediately
3. **Aggregated Warnings** - Multiple warnings collected and reported
4. **History Tracking** - Last 1000 checks kept for debugging

## Workflow Integration

### Workflow Spec

Specify meta-agents in workflow definition:

```clojure
{:workflow/type :standard-sdlc
 :workflow/version "2.0.0"
 :title "Implement Feature"

 ;; Meta-agent configuration
 :workflow/meta-agents
 [{:id :progress-monitor
   :enabled? true
   :config {:stagnation-threshold-ms 120000
            :max-total-ms 600000}}
  {:id :test-quality
   :enabled? true
   :config {:min-coverage-percent 80}}
  {:id :conflict-detector
   :enabled? true}
  {:id :resource-manager
   :enabled? true
   :config {:max-tokens 150000}}
  {:id :evidence-collector
   :enabled? true}]}
```

### Runtime Checks

Workflow runner integrates coordinator:

```clojure
;; Create meta-agents from config
(def meta-agents (create-meta-agents-from-config
                  (:workflow/meta-agents workflow)))

(def coordinator (create-coordinator meta-agents))

;; Check health during workflow execution
(loop [state initial-state]
  (let [health (check-all-agents coordinator state)]
    (cond
      ;; Any agent signals halt - stop immediately
      (= :halt (:status health))
      (halt-workflow! state (:halt-reason health))

      ;; Warnings - log but continue
      (= :warning (:status health))
      (do
        (log-warnings (:warnings health))
        (recur (execute-next-phase state)))

      ;; Healthy - continue
      :else
      (recur (execute-next-phase state)))))
```

## Observability

### Check History

```clojure
;; Get recent checks
(get-check-history coordinator {:limit 50})

;; Filter by agent
(get-check-history coordinator
                   {:agent-id :progress-monitor
                    :limit 20})

;; Filter by status
(get-check-history coordinator
                   {:status :halt
                    :limit 10})
```

### Agent Statistics

```clojure
(get-agent-stats coordinator)
;; => {:agents
;;     [{:id :progress-monitor
;;       :name "Progress Monitor"
;;       :checks-run 42
;;       :last-check #inst "2026-01-31"
;;       :last-status :healthy
;;       :halt-count 2
;;       :warning-count 5}
;;      ...]}
```

## Adding New Meta-Agents

1. **Implement Protocol**

   ```clojure
   (defrecord MyMetaAgent [state config]
     mp/MetaAgent
     (check-health [this workflow-state]
       (mp/create-health-check
        :my-agent
        :healthy
        "All good"
        {:metric 42}))
     (get-meta-config [this] config)
     (reset-state! [this] (reset! state {})))
   ```

2. **Add to Registry**

   ```clojure
   ;; In components/schema/src/ai/miniforge/schema/core.clj
   (def meta-agent-roles
     [...existing...
      :my-agent])
   ```

3. **Document Behavior**
   - Add section to this document
   - Describe halt conditions
   - Provide configuration examples

## Design Rationale

### Why Not a Monolithic Orchestrator?

❌ **Single Point of Failure** - If orchestrator has a bug, entire system broken
❌ **God Object** - Becomes massive, hard to reason about
❌ **Tightly Coupled** - Changes to one concern affect all others
❌ **Testing Nightmare** - Can't test individual concerns in isolation

### Why Specialized Meta-Agents?

✅ **Focused Expertise** - Each agent deeply understands its domain
✅ **Independent Testing** - Test progress monitoring without test quality logic
✅ **Composable** - Add/remove agents without system redesign
✅ **Observable** - Each agent's decisions clearly attributed
✅ **Distributed Authority** - No single point of control/failure
✅ **Follows Existing Pattern** - Same architecture as main agents

### Why Any Agent Can Halt?

This is a **safety mechanism**. If the progress monitor detects stagnation, it doesn't matter that tests
look good - the workflow is stuck. Any critical issue should stop the factory floor.

## Future Extensions

**Potential Additional Meta-Agents:**

- **Security Scanner** - Halt on security violations
- **License Checker** - Halt on license incompatibilities
- **Performance Monitor** - Halt on memory leaks or perf degradation
- **Dependency Analyzer** - Warn on circular dependencies
- **Code Quality Gate** - Halt on code smell threshold

**Dynamic Agent Loading:**

```clojure
;; Add agent at runtime
(add-agent! coordinator (create-security-scanner))

;; Remove agent
(remove-agent! coordinator :security-scanner)
```

**Agent Communication:**

Future enhancement: Meta-agents could communicate through coordinator to share context (e.g., progress
monitor tells resource manager that workflow is stalled, so stop counting tokens).

## See Also

- [Agent Architecture](./agents.md)
- [Workflow System](./workflows.md)
- [Progress Monitoring](./progress-monitoring.md)
- [Configuration Management](./configuration.md)
