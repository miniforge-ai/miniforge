# miniforge.ai — Learning & Adaptation Specification

**Version:** 0.1.0  
**Status:** Draft  
**Date:** 2026-01-18  

---

## 1. Overview

### 1.1 Purpose

miniforge.ai learns at multiple timescales:

| Timescale | Mechanism | What Changes |
|-----------|-----------|--------------|
| **Immediate** (inner loop) | Repair feedback | Current task context |
| **Session** (outer loop) | Agent memory | Workflow-scoped knowledge |
| **Operational** (meta loop) | Heuristic tuning | Prompts, thresholds, policies |
| **Evolutionary** (training) | Fine-tuning | Model weights |

This spec defines how data flows from execution → learning → improved behavior.

### 1.2 Design Principles

1. **Everything is a training example**: Every agent invocation produces learnable data
2. **Heuristics are data**: Prompts, thresholds, and strategies are versioned artifacts
3. **Offline-first evaluation**: Test improvements on historical data before deployment
4. **Gradual rollout**: Shadow → Canary → Full for any learned change
5. **Reversibility**: Any adaptation can be rolled back within minutes

---

## 2. Training Example Schema

### 2.1 Core Training Record

Every agent task produces a training record:

```clojure
{:training/id            uuid
 :training/timestamp     inst
 :training/agent-role    keyword         ; :planner, :implementer, :tester, etc.
 :training/model-version string          ; which model produced this
 
 ;; Input context (what the agent saw)
 :training/input
 {:task          Task                    ; the task definition
  :context       map                     ; injected context (memory, tools, etc.)
  :prompt        string                  ; actual prompt sent (hashed for privacy)
  :prompt-hash   string                  ; for deduplication
  :artifacts-in  [ArtifactRef]}          ; input artifacts
 
 ;; Output (what the agent produced)
 :training/output
 {:response      string                  ; raw response (hashed for privacy)
  :response-hash string
  :artifacts-out [ArtifactRef]           ; produced artifacts
  :tool-calls    [ToolCall]              ; tools invoked
  :tokens-used   long
  :latency-ms    long}
 
 ;; Feedback (what happened next)
 :training/feedback
 {:validation-result   keyword           ; :passed, :failed, :partial
  :gates-passed        [keyword]
  :gates-failed        [{:gate keyword :reason string}]
  :repair-attempted?   boolean
  :repair-succeeded?   boolean
  :iterations-to-pass  integer           ; how many inner loop cycles
  :escalated?          boolean
  :human-override?     boolean
  :human-correction    string}           ; if human provided correction
 
 ;; Outcome (ultimate result)
 :training/outcome
 {:phase-succeeded?    boolean           ; did the outer loop phase complete?
  :workflow-succeeded? boolean           ; did the whole workflow complete?
  :production-incident? boolean          ; did this cause issues in prod?
  :time-to-feedback-ms long}             ; how long until we knew the outcome
 
 ;; Labels (for supervised learning)
 :training/labels
 {:quality-score       float             ; 0.0-1.0, computed from feedback+outcome
  :example-type        keyword           ; :positive, :negative, :corrected
  :error-category      keyword           ; :syntax, :logic, :incomplete, :wrong-tool, etc.
  :difficulty          keyword}          ; :easy, :medium, :hard (based on iterations)
 
 ;; Provenance
 :training/provenance
 {:workflow-id   uuid
  :task-id       uuid
  :scenario-id   uuid                    ; if from a test scenario
  :environment   keyword}}               ; :production, :staging, :test
```

### 2.2 Quality Score Computation

Quality score is computed from observable signals:

```clojure
(defn compute-quality-score
  [{:keys [validation-result iterations-to-pass escalated? 
           human-override? phase-succeeded? production-incident?]}]
  (let [base-score (case validation-result
                     :passed 1.0
                     :partial 0.5
                     :failed 0.0)
        ;; Penalties
        iteration-penalty (* 0.1 (max 0 (- iterations-to-pass 1)))
        escalation-penalty (if escalated? 0.3 0)
        override-penalty (if human-override? 0.2 0)
        incident-penalty (if production-incident? 0.5 0)
        ;; Bonuses
        phase-bonus (if phase-succeeded? 0.1 0)]
    (-> base-score
        (- iteration-penalty)
        (- escalation-penalty)
        (- override-penalty)
        (- incident-penalty)
        (+ phase-bonus)
        (max 0.0)
        (min 1.0))))
```

### 2.3 Example Types

| Type | Criteria | Use |
|------|----------|-----|
| `:positive` | quality-score >= 0.8, no corrections | Reinforcement |
| `:negative` | quality-score < 0.3, clear failure mode | Learn what not to do |
| `:corrected` | Human provided correction | Highest-value examples |
| `:hard-positive` | Passed after multiple iterations | Edge case handling |
| `:ambiguous` | 0.3 <= quality-score < 0.8 | Needs human labeling |

---

## 3. Inner Loop Learning

### 3.1 Repair Feedback Capture

Each inner loop iteration captures what was tried and what happened:

```clojure
{:repair/id              uuid
 :repair/iteration       integer          ; which iteration (1, 2, 3...)
 :repair/parent-attempt  uuid             ; previous attempt's training-id
 
 ;; What failed
 :repair/failure
 {:gate             keyword              ; which gate failed
  :error-type       keyword              ; :syntax, :type, :test, :lint, :security
  :error-message    string
  :error-location   map                  ; {:file, :line, :column} if applicable
  :error-context    string}              ; surrounding code/content
 
 ;; Repair strategy used
 :repair/strategy
 {:strategy-id      keyword              ; :direct-fix, :regenerate, :decompose, :ask-clarify
  :strategy-version string               ; heuristic version that selected this
  :prompt-delta     string               ; what was added to prompt
  :context-added    map}                 ; additional context injected
 
 ;; Repair result
 :repair/result
 {:succeeded?       boolean
  :new-errors       [Error]              ; different errors introduced
  :same-error?      boolean              ; exact same failure
  :partial-fix?     boolean              ; fixed some but not all
  :tokens-used      long
  :latency-ms       long}}
```

### 3.2 Repair Strategy Heuristics

Repair strategies are data, not code:

```clojure
{:heuristic/id       :repair-strategy-selector
 :heuristic/version  "1.3.0"
 :heuristic/rules
 [{:condition {:error-type :syntax
               :iteration 1}
   :strategy :direct-fix
   :prompt-template "The code has a syntax error: {{error-message}}\nFix only this error."}
  
  {:condition {:error-type :type
               :same-error-repeated? true}
   :strategy :regenerate
   :prompt-template "Previous approach had type errors. Try a different approach."}
  
  {:condition {:error-type :test
               :iteration (> 2)}
   :strategy :decompose
   :prompt-template "Break this into smaller testable units."}
  
  {:condition {:iteration (> 4)}
   :strategy :escalate
   :action :escalate-to-outer-loop}]
 
 :heuristic/default
 {:strategy :direct-fix
  :prompt-template "Fix the following error: {{error-message}}"}}
```

### 3.3 Inner Loop Convergence Metrics

Track whether loops are converging or thrashing:

```clojure
{:convergence/workflow-id    uuid
 :convergence/task-id        uuid
 :convergence/iterations     [IterationMetrics]
 :convergence/pattern        keyword    ; :converging, :thrashing, :stuck, :diverging
 :convergence/efficiency     float}     ; tokens-to-success ratio

;; Per-iteration metrics
{:iteration/number          integer
 :iteration/error-count     integer
 :iteration/error-types     #{keyword}
 :iteration/error-delta     integer     ; change from previous (-N = improvement)
 :iteration/tokens-used     long
 :iteration/strategy-used   keyword}
```

---

## 4. Heuristics as Data

### 4.1 Heuristic Schema

All tunable behaviors are represented as versioned data:

```clojure
{:heuristic/id          keyword          ; unique identifier
 :heuristic/name        string
 :heuristic/description string
 :heuristic/version     string           ; semver
 :heuristic/type        keyword          ; :prompt-template, :threshold, :rule-set, :classifier
 
 ;; The actual heuristic content
 :heuristic/content     any              ; type-specific
 
 ;; Metadata
 :heuristic/created-at  inst
 :heuristic/created-by  string           ; human or meta-loop
 :heuristic/parent      string           ; previous version (nil if original)
 :heuristic/rationale   string           ; why this version exists
 
 ;; Evaluation
 :heuristic/metrics
 {:sample-size          integer
  :quality-score-avg    float
  :quality-score-p50    float
  :quality-score-p95    float
  :tokens-per-task-avg  float
  :success-rate         float}
 
 ;; Deployment
 :heuristic/status      keyword          ; :draft, :shadow, :canary, :active, :deprecated
 :heuristic/traffic     float}           ; 0.0-1.0, percentage of traffic
```

### 4.2 Heuristic Types

| Type | Content Format | Example |
|------|----------------|---------|
| `:prompt-template` | String with `{{placeholders}}` | Agent system prompts |
| `:threshold` | `{:metric keyword :value number}` | Max iterations, budget limits |
| `:rule-set` | `[{:condition :action}]` | Repair strategies, escalation rules |
| `:classifier` | Model reference or decision tree | Error categorization |
| `:few-shot-examples` | `[{:input :output}]` | In-context learning examples |

### 4.3 Heuristic Registry

```clojure
(defprotocol HeuristicRegistry
  (get-heuristic [this heuristic-id]
    "Get active version of heuristic")
  
  (get-heuristic-version [this heuristic-id version]
    "Get specific version")
  
  (list-heuristics [this criteria]
    "List heuristics matching criteria")
  
  (register-heuristic [this heuristic]
    "Register new heuristic version")
  
  (set-traffic [this heuristic-id version traffic-pct]
    "Adjust traffic allocation for A/B testing")
  
  (select-heuristic [this heuristic-id context]
    "Select which version to use for given context (respects traffic splits)"))
```

### 4.4 Agent Prompts as Heuristics

Agent system prompts are first-class heuristics:

```clojure
{:heuristic/id      :agent-prompt/implementer
 :heuristic/type    :prompt-template
 :heuristic/version "2.1.0"
 :heuristic/content
 "You are an expert software implementer working on {{project-context}}.

Your task: {{task-description}}

Available tools:
{{available-tools}}

Constraints:
- Write idiomatic {{language}} code
- Include error handling
- Follow the existing code style

Output your implementation as a single code block."

 :heuristic/metrics
 {:sample-size 1523
  :quality-score-avg 0.82
  :success-rate 0.91
  :tokens-per-task-avg 2340}}
```

---

## 5. Meta Loop Learning

### 5.1 Signal → Improvement Pipeline

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Signals   │ ──► │  Analysis   │ ──► │  Proposal   │ ──► │  Evaluation │
│  (logging)  │     │  (patterns) │     │ (candidate) │     │   (shadow)  │
└─────────────┘     └─────────────┘     └─────────────┘     └──────┬──────┘
                                                                   │
┌─────────────┐     ┌─────────────┐     ┌─────────────┐            │
│   Active    │ ◄── │   Rollout   │ ◄── │   Approve   │ ◄──────────┘
│  (deploy)   │     │  (canary)   │     │  (gate)     │
└─────────────┘     └─────────────┘     └─────────────┘
```

### 5.2 Improvement Proposal Schema

```clojure
{:improvement/id          uuid
 :improvement/type        keyword         ; :prompt-refinement, :threshold-adjustment, 
                                          ; :rule-addition, :few-shot-update
 :improvement/target      keyword         ; heuristic-id being modified
 :improvement/status      keyword         ; :proposed, :evaluating, :approved, :rejected,
                                          ; :canary, :active, :rolled-back
 
 ;; What's changing
 :improvement/current     HeuristicRef    ; current version
 :improvement/proposed    Heuristic       ; proposed new version
 :improvement/diff        string          ; human-readable diff
 
 ;; Why
 :improvement/rationale   string
 :improvement/evidence
 {:signals        [SignalRef]             ; signals that triggered this
  :examples       [TrainingRef]           ; example failures/successes
  :pattern        string}                 ; detected pattern description
 
 ;; Evaluation
 :improvement/evaluation
 {:method         keyword                 ; :shadow, :replay, :ab-test
  :sample-size    integer
  :baseline       Metrics
  :candidate      Metrics
  :significant?   boolean
  :p-value        float
  :lift           float}                  ; % improvement
 
 ;; Approval
 :improvement/requires-approval boolean
 :improvement/approved-by       string
 :improvement/approved-at       inst
 
 ;; Rollout
 :improvement/rollout
 {:strategy       keyword                 ; :immediate, :canary, :gradual
  :canary-pct     float
  :canary-start   inst
  :canary-end     inst
  :full-deploy    inst}}
```

### 5.3 Improvement Types

| Type | Trigger | Mechanism |
|------|---------|-----------|
| `:prompt-refinement` | High iteration count on specific error type | LLM generates improved prompt from examples |
| `:threshold-adjustment` | Budget exceeded or underutilized | Statistical analysis of optimal values |
| `:rule-addition` | Recurring failure pattern | Extract rule from successful repairs |
| `:few-shot-update` | High-quality corrected examples | Add to in-context examples |
| `:tool-preference` | Tool success/failure rates | Adjust tool selection heuristics |

### 5.4 Evaluation Methods

#### Shadow Mode
Run both old and new heuristic, compare results without affecting production:

```clojure
{:evaluation/method    :shadow
 :evaluation/duration  {:hours 24}
 :evaluation/traffic   1.0              ; 100% of traffic runs both
 :evaluation/metrics   [:quality-score :tokens-used :latency]
 :evaluation/success-criteria
 {:quality-score-lift  0.05            ; at least 5% improvement
  :tokens-delta        0.10            ; no more than 10% increase
  :p-value             0.05}}          ; statistically significant
```

#### Replay Evaluation
Replay historical scenarios with new heuristic:

```clojure
{:evaluation/method    :replay
 :evaluation/scenarios [scenario-id ...]   ; or {:tags [:regression]}
 :evaluation/metrics   [:quality-score :iterations-to-pass]
 :evaluation/success-criteria
 {:scenarios-improved  0.80            ; 80% of scenarios same or better
  :regressions-max     0}}             ; zero regressions allowed
```

#### A/B Test
Split live traffic:

```clojure
{:evaluation/method    :ab-test
 :evaluation/traffic   {:control 0.90 :candidate 0.10}
 :evaluation/duration  {:days 3}
 :evaluation/metrics   [:quality-score :workflow-success-rate]
 :evaluation/success-criteria
 {:quality-score-lift  0.03
  :p-value             0.05}}
```

---

## 6. Fine-Tuning Pipeline

### 6.1 Training Data Export

Export training examples for fine-tuning:

```clojure
(defprotocol TrainingDataExporter
  (export-examples [this criteria format]
    "Export training examples matching criteria")
  
  (create-dataset [this dataset-config]
    "Create a versioned training dataset")
  
  (get-dataset [this dataset-id]
    "Retrieve a dataset")
  
  (compare-datasets [this dataset-a dataset-b]
    "Compare two datasets for drift"))
```

### 6.2 Dataset Schema

```clojure
{:dataset/id           uuid
 :dataset/name         string
 :dataset/version      string
 :dataset/created-at   inst
 :dataset/created-by   string
 
 ;; Content
 :dataset/agent-role   keyword           ; which agent this trains
 :dataset/example-count integer
 :dataset/token-count  long
 
 ;; Filters used to create
 :dataset/criteria
 {:time-range     [inst inst]
  :quality-min    float                  ; minimum quality score
  :example-types  #{keyword}             ; :positive, :corrected, etc.
  :exclude-tags   #{keyword}             ; scenarios to exclude
  :sample-method  keyword}               ; :all, :stratified, :weighted
 
 ;; Statistics
 :dataset/stats
 {:quality-score-distribution histogram
  :error-type-distribution    map
  :difficulty-distribution    map
  :token-length-distribution  histogram}
 
 ;; Provenance
 :dataset/training-runs  [TrainingRunRef]}
```

### 6.3 Training Run Schema

```clojure
{:training-run/id         uuid
 :training-run/status     keyword        ; :pending, :running, :completed, :failed
 :training-run/started-at inst
 :training-run/completed-at inst
 
 ;; Configuration
 :training-run/config
 {:base-model      string               ; e.g., "claude-3-opus"
  :dataset-id      uuid
  :method          keyword              ; :fine-tune, :rlhf, :dpo
  :hyperparameters map}
 
 ;; Output
 :training-run/output
 {:model-id        string               ; resulting model identifier
  :model-version   string
  :training-loss   [float]              ; per-epoch
  :validation-loss [float]}
 
 ;; Evaluation
 :training-run/evaluation
 {:eval-dataset-id uuid                 ; held-out evaluation set
  :metrics         Metrics
  :vs-baseline     {:lift float :p-value float}}}
```

### 6.4 Model Registry

```clojure
(defprotocol ModelRegistry
  (register-model [this model-metadata]
    "Register a new model version")
  
  (get-model [this model-id]
    "Get model metadata")
  
  (list-models [this agent-role]
    "List models for an agent role")
  
  (set-active [this agent-role model-id]
    "Set the active model for an agent role")
  
  (get-active [this agent-role]
    "Get the currently active model for an agent role")
  
  (compare-models [this model-a model-b eval-dataset]
    "Compare two models on evaluation dataset"))
```

### 6.5 Model Deployment

```clojure
{:model-deployment/id       uuid
 :model-deployment/model-id string
 :model-deployment/agent-role keyword
 :model-deployment/status   keyword     ; :shadow, :canary, :active, :deprecated
 :model-deployment/traffic  float       ; 0.0-1.0
 
 ;; Rollout
 :model-deployment/rollout
 {:strategy     keyword                 ; :immediate, :canary, :gradual
  :started-at   inst
  :canary-pct   float
  :canary-until inst
  :auto-promote boolean                 ; promote if metrics good?
  :rollback-on  #{keyword}}             ; conditions that trigger rollback
 
 ;; Live metrics
 :model-deployment/metrics
 {:requests       long
  :quality-score  MovingAverage
  :latency-p50    MovingAverage
  :error-rate     MovingAverage}}
```

---

## 7. Evaluation Harness

### 7.1 Benchmark Scenarios

Curated scenarios for regression testing:

```clojure
{:benchmark/id          uuid
 :benchmark/name        string
 :benchmark/description string
 :benchmark/agent-role  keyword
 
 ;; Test cases
 :benchmark/cases
 [{:case/id         uuid
   :case/name       string
   :case/input      Task
   :case/context    map
   :case/expected   {:artifacts [...] :gates-pass #{...}}
   :case/difficulty keyword
   :case/tags       #{keyword}}]
 
 ;; Scoring
 :benchmark/scoring
 {:weights {:correctness 0.5
            :efficiency 0.2      ; tokens/time
            :style 0.15
            :robustness 0.15}}}
```

### 7.2 Evaluation Protocol

```clojure
(defprotocol Evaluator
  (run-benchmark [this benchmark model-config]
    "Run a model against a benchmark, returns results")
  
  (compare-results [this results-a results-b]
    "Compare two benchmark runs")
  
  (regression-check [this model-config baseline-results]
    "Check if model regresses on any benchmark case")
  
  (create-report [this benchmark-results]
    "Generate human-readable evaluation report"))
```

### 7.3 Continuous Evaluation

Run benchmarks automatically:

```clojure
{:continuous-eval/schedule "0 0 * * *"    ; daily
 :continuous-eval/benchmarks [:core-implement :core-test :edge-cases]
 :continuous-eval/models [:active :canary]
 :continuous-eval/alert-on
 {:regression-detected true
  :quality-drop-pct 0.05
  :latency-increase-pct 0.20}}
```

---

## 8. Data Flow Summary

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           EXECUTION LAYER                                │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                  │
│  │   Agent     │───►│   Inner     │───►│   Outer     │                  │
│  │   Task      │    │   Loop      │    │   Loop      │                  │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘                  │
│         │                  │                  │                          │
│         ▼                  ▼                  ▼                          │
│  ┌──────────────────────────────────────────────────────────────┐       │
│  │                    Training Examples                          │       │
│  │  (input, output, feedback, outcome, quality-score, labels)   │       │
│  └──────────────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           LEARNING LAYER                                 │
│                                                                          │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐     │
│  │ Signal          │    │ Improvement     │    │ Evaluation      │     │
│  │ Extraction      │───►│ Generation      │───►│ Harness         │     │
│  └─────────────────┘    └─────────────────┘    └────────┬────────┘     │
│                                                         │               │
│  ┌─────────────────┐    ┌─────────────────┐            │               │
│  │ Heuristic       │◄───│ Approval        │◄───────────┘               │
│  │ Registry        │    │ Gate            │                            │
│  └────────┬────────┘    └─────────────────┘                            │
│           │                                                             │
│           │ (operational learning - fast feedback)                      │
└───────────┼─────────────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           TRAINING LAYER                                 │
│                                                                          │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐     │
│  │ Dataset         │    │ Training        │    │ Model           │     │
│  │ Builder         │───►│ Pipeline        │───►│ Registry        │     │
│  └─────────────────┘    └─────────────────┘    └────────┬────────┘     │
│                                                         │               │
│  ┌─────────────────┐    ┌─────────────────┐            │               │
│  │ Agent           │◄───│ Model           │◄───────────┘               │
│  │ Configuration   │    │ Deployment      │                            │
│  └─────────────────┘    └─────────────────┘                            │
│                                                                          │
│  (evolutionary learning - slow but powerful)                            │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Implementation Phases

### Phase 0 (Foundations)

- [ ] Training example schema
- [ ] Repair feedback schema
- [ ] Heuristic schema
- [ ] Dataset schema

### Phase 1 (Capture)

- [ ] Training example capture in agent execution
- [ ] Repair feedback capture in inner loop
- [ ] Quality score computation
- [ ] Example labeling pipeline

### Phase 2 (Heuristics)

- [ ] Heuristic registry
- [ ] Agent prompts as heuristics
- [ ] Traffic splitting for A/B tests
- [ ] Heuristic versioning

### Phase 3 (Meta Loop Learning)

- [ ] Signal → improvement pipeline
- [ ] Shadow mode evaluation
- [ ] Replay evaluation
- [ ] Improvement approval workflow

### Phase 4 (Fine-Tuning)

- [ ] Training data exporter
- [ ] Dataset builder
- [ ] Training run orchestration
- [ ] Model registry

### Phase 5 (Evaluation)

- [ ] Benchmark scenario curation
- [ ] Evaluation harness
- [ ] Continuous evaluation
- [ ] Regression alerting

---

## 10. Integration with Other Specs

| Spec | Integration Point |
|------|-------------------|
| **Logging** | Training examples are derived from log events |
| **Async Workflows** | Training captures span workflow success/failure |
| **Reporting** | Dashboard shows learning metrics, heuristic performance |
| **Extensibility** | Plugins can register custom heuristics |

---

## 11. Open Questions

1. **Privacy**: How to handle PII in training examples? Redaction vs hashing vs exclusion?
2. **Fine-tuning infrastructure**: Self-hosted vs API (Anthropic doesn't offer fine-tuning yet)?
3. **Human labeling**: When to require human labels vs automated quality scores?
4. **Cross-customer learning**: Can improvements from one customer benefit others?
5. **Negative examples**: How to weight failures in training? Too many could over-correct.
6. **Prompt optimization**: Automated prompt optimization (DSPy-style) vs LLM-generated improvements?

---

## 12. Feasibility Notes

### What's Feasible Now

- Training example capture (just structured logging)
- Heuristics as data (store in DB, version control)
- Shadow mode evaluation (run twice, compare)
- Replay evaluation (re-run scenarios)
- Benchmark harness (automated testing)

### What Requires Infrastructure

- Fine-tuning (Anthropic doesn't offer this for Claude; may need to use open models for specialized agents)
- Large-scale A/B testing (needs traffic management)
- Automated improvement generation (LLM-in-the-loop for meta)

### Recommended Path

1. Start with heuristics-as-data and A/B testing of prompts
2. Build training example capture from day one (even if not used immediately)
3. Implement replay evaluation for safe rollout
4. Defer fine-tuning until we have sufficient high-quality examples (10K+)
5. Consider open models (Llama, Mistral) for specialized agents that need fine-tuning
