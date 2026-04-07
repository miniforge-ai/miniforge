<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# ⚠️ DEPRECATED - Agent Status Streaming Protocol

**⚠️ DEPRECATION NOTICE:**
This document has been **superseded** by the normative specification:
**[N3 — Event Stream & Observability Contract](../normative/N3-event-stream.md)**

All agent status streaming is now defined in N3. This file is retained for historical reference only.

Do NOT use this document for implementation. Refer to N3 instead.

---

# Agent Status Streaming Protocol (HISTORICAL)

**Date:** 2026-01-23
**Purpose:** Real-time visibility into agent activity and thought processes
**Inspiration:** Claude Code's "Thinking...", "Reading file...", "Searching..." status messages

---

## Overview

Users are accustomed to seeing real-time status from LLMs. When agents execute autonomously for minutes or hours, users need a window into what's happening.

**Key Insight:** Agents should emit status events just like LLMs emit thinking tokens.

---

## Status Event Protocol

### Event Types

```clojure
;; Agent lifecycle events
{:event/type :agent-started
 :event/timestamp inst
 :agent/id :planner
 :agent/instance-id uuid
 :workflow/id uuid
 :phase :plan
 :message "Planner agent started"}

{:event/type :agent-completed
 :event/timestamp inst
 :agent/id :planner
 :agent/instance-id uuid
 :workflow/id uuid
 :phase :plan
 :duration-ms 45000
 :message "Planner agent completed"}

;; Activity status events
{:event/type :agent-status
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :phase :implement
 :status/type :reading
 :status/detail "Reading spec file: specs/rds-import.edn"
 :message "Reading spec file..."}

{:event/type :agent-status
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :phase :implement
 :status/type :thinking
 :status/detail "Analyzing import constraints"
 :message "Analyzing import constraints..."}

{:event/type :agent-status
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :phase :implement
 :status/type :generating
 :status/detail "Generating Terraform import blocks"
 :message "Generating Terraform code..."}

{:event/type :agent-status
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :phase :implement
 :status/type :validating
 :status/detail "Running policy checks: terraform-aws"
 :message "Validating against policies..."}

{:event/type :agent-status
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :phase :implement
 :status/type :repairing
 :status/detail "Fixing policy violation: missing region constraint"
 :message "Repairing code (inner loop iteration 2)..."}

;; Subagent spawning
{:event/type :subagent-spawned
 :event/timestamp inst
 :parent-agent/id :implementer
 :parent-agent/instance-id uuid
 :subagent/id :terraform-planner
 :subagent/instance-id uuid
 :workflow/id uuid
 :subagent/purpose "Generate terraform plan output"
 :message "Spawned subagent: terraform-planner"}

{:event/type :agent-status
 :event/timestamp inst
 :agent/id :terraform-planner
 :agent/instance-id uuid
 :parent-agent/id :implementer
 :workflow/id uuid
 :status/type :running
 :status/detail "Running: terraform plan -out=plan.tfplan"
 :message "Running terraform plan..."}

;; Tool usage
{:event/type :agent-tool-use
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :tool/name :read-file
 :tool/args {:file-path "terraform/main.tf"}
 :message "Reading file: terraform/main.tf"}

{:event/type :agent-tool-use
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :tool/name :write-file
 :tool/args {:file-path "terraform/rds.tf" :lines 35}
 :message "Writing file: terraform/rds.tf (35 lines)"}

{:event/type :agent-tool-use
 :event/timestamp inst
 :agent/id :tester
 :agent/instance-id uuid
 :workflow/id uuid
 :tool/name :run-tests
 :tool/args {:test-suite "unit" :test-count 42}
 :message "Running 42 unit tests..."}

;; LLM calls (for transparency)
{:event/type :llm-request
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :llm/model "claude-sonnet-4"
 :llm/prompt-tokens 2400
 :message "Calling Claude Sonnet (2.4k tokens)..."}

{:event/type :llm-response
 :event/timestamp inst
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :llm/model "claude-sonnet-4"
 :llm/completion-tokens 850
 :llm/duration-ms 3200
 :message "Received response (850 tokens, 3.2s)"}

;; Inter-agent communication
{:event/type :agent-message-sent
 :event/timestamp inst
 :from-agent/id :implementer
 :to-agent/id :planner
 :workflow/id uuid
 :message-type :clarification-request
 :message "Asking Planner: Should we create new security group?"}

{:event/type :agent-message-received
 :event/timestamp inst
 :from-agent/id :planner
 :to-agent/id :implementer
 :workflow/id uuid
 :message-type :clarification-response
 :message "Planner response: Reuse existing security group sg-prod-rds"}

;; Progress milestones
{:event/type :milestone-reached
 :event/timestamp inst
 :agent/id :implementer
 :workflow/id uuid
 :milestone :code-generated
 :message "Code generation complete"}

{:event/type :milestone-reached
 :event/timestamp inst
 :agent/id :implementer
 :workflow/id uuid
 :milestone :validation-passed
 :message "All policy checks passed"}
```

### Status Types

```clojure
:reading          ; Reading files, specs, context
:thinking         ; Analyzing, planning, reasoning
:generating       ; Creating code, artifacts
:validating       ; Running policy checks, tests
:repairing        ; Fixing issues (inner loop)
:running          ; Executing external commands
:waiting          ; Waiting for dependency, approval
:communicating    ; Talking to other agents
```

---

## Agent Implementation

### Emitting Status Events

```clojure
;; components/agent/src/ai/miniforge/agent/status.clj

(ns ai.miniforge.agent.status
  (:require
   [ai.miniforge.logging.interface :as log]))

(defn emit-status
  "Emit a status event for real-time UI updates"
  [agent workflow-id status-type detail]

  (let [event {:event/type :agent-status
               :event/timestamp (java.time.Instant/now)
               :agent/id (:agent/id agent)
               :agent/instance-id (:agent/instance-id agent)
               :workflow/id workflow-id
               :phase (:current-phase agent)
               :status/type status-type
               :status/detail detail
               :message (format-status-message status-type detail)}]

    ;; Log to structured log (can be streamed to UI)
    (log/write-structured event)

    ;; Publish to event bus for real-time subscribers
    (publish-event agent event)))

(defn format-status-message
  "Format human-readable status message"
  [status-type detail]
  (case status-type
    :reading (str "Reading: " detail)
    :thinking (str detail "...")
    :generating (str "Generating " detail "...")
    :validating (str "Validating: " detail)
    :repairing (str "Fixing: " detail)
    :running (str "Running: " detail)
    (str status-type ": " detail)))

;; Usage in agent code

(defn execute-implementer [agent context]

  ;; Emit status: starting
  (emit-status agent (:workflow/id context) :agent-started "Starting implementation phase")

  ;; Read spec
  (emit-status agent (:workflow/id context) :reading "spec file: specs/rds-import.edn")
  (let [spec (read-spec context)]

    ;; Analyze constraints
    (emit-status agent (:workflow/id context) :thinking "Analyzing import constraints")
    (let [analysis (analyze-constraints spec)]

      ;; Generate code
      (emit-status agent (:workflow/id context) :generating "Terraform import blocks")
      (let [code (generate-code agent spec analysis)]

        ;; Validate
        (emit-status agent (:workflow/id context) :validating "policy checks: terraform-aws")
        (let [validation (validate-code code)]

          (if (:passed? validation)
            (do
              (emit-status agent (:workflow/id context) :milestone-reached "Code generation complete")
              code)

            ;; Repair if needed
            (do
              (emit-status agent (:workflow/id context) :repairing
                           (str "Fixing: " (:violation validation)))
              (repair-code agent code validation))))))))
```

### Subagent Status

```clojure
(defn spawn-subagent
  "Spawn a subagent for specialized task"
  [parent-agent subagent-type purpose context]

  (let [subagent-id (random-uuid)
        event {:event/type :subagent-spawned
               :event/timestamp (java.time.Instant/now)
               :parent-agent/id (:agent/id parent-agent)
               :parent-agent/instance-id (:agent/instance-id parent-agent)
               :subagent/id subagent-type
               :subagent/instance-id subagent-id
               :workflow/id (:workflow/id context)
               :subagent/purpose purpose}]

    (log/write-structured event)
    (publish-event parent-agent event)

    ;; Create and return subagent
    (create-agent subagent-type {:parent parent-agent
                                 :instance-id subagent-id
                                 :workflow-id (:workflow/id context)})))

;; Usage
(let [terraform-planner (spawn-subagent implementer
                                        :terraform-planner
                                        "Generate terraform plan output"
                                        context)]

  (emit-status terraform-planner (:workflow/id context)
               :running "terraform plan -out=plan.tfplan")

  (run-terraform-plan terraform-planner context))
```

---

## API Streaming

### Server-Sent Events (SSE)

```clojure
;; bases/api-server/src/ai/miniforge/api/stream.clj

(ns ai.miniforge.api.stream
  (:require
   [ai.miniforge.logging.interface :as log]
   [ring.util.response :as response]))

(defn stream-workflow-status
  "SSE endpoint for streaming workflow status"
  [request]

  (let [workflow-id (-> request :params :workflow-id parse-uuid)

        ;; Create SSE response
        response (-> (response/response nil)
                     (response/content-type "text/event-stream")
                     (response/header "Cache-Control" "no-cache")
                     (response/header "Connection" "keep-alive"))]

    ;; Subscribe to events for this workflow
    (subscribe-to-workflow-events workflow-id
                                  (fn [event]
                                    ;; Send SSE event
                                    (send-sse-event response event)))))

(defn send-sse-event [response event]
  (let [data (pr-str event)]
    (str "event: " (name (:event/type event)) "\n"
         "data: " data "\n\n")))

;; HTTP endpoint
;; GET /api/workflows/:id/stream
```

### WebSocket Alternative

```clojure
;; WebSocket connection for bidirectional streaming

(defn websocket-handler [request]
  (websocket/connect
   request
   {:on-connect (fn [ws]
                  (subscribe-to-all-events
                   (fn [event]
                     (websocket/send ws (pr-str event)))))

    :on-message (fn [ws message]
                  ;; Client can subscribe to specific workflows
                  (let [cmd (edn/read-string message)]
                    (case (:command cmd)
                      :subscribe-workflow
                      (subscribe-to-workflow (:workflow-id cmd) ws))))}))
```

---

## UI Integration

### TUI Agent Status Panel

```
╭─────────────────────────────────────────────────────────────────────────────╮
│ Workflow: rds-import (#abc123)                              [Phase: Implement]│
├─────────────────────────────────────────────────────────────────────────────┤
│ ACTIVE AGENTS (3)                                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ ▸ Planner                                                                   │
│   └─ ✓ Completed (2m ago) - Generated 5-step plan                          │
│                                                                             │
│ ● Implementer                                                [2m 34s]       │
│   ├─ ⟳ Generating Terraform import blocks...                               │
│   └─ Subagent: terraform-planner                            [12s]          │
│       └─ ⟳ Running: terraform plan -out=plan.tfplan                        │
│                                                                             │
│ ○ Tester                                                     [waiting]      │
│   └─ Waiting for Implementer to complete                                   │
│                                                                             │
│ ○ Reviewer                                                   [waiting]      │
│   └─ Waiting for Tester to complete                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│ RECENT ACTIVITY                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ 2m 34s  Implementer   ⟳ Generating Terraform import blocks...              │
│ 2m 40s  Implementer   📖 Reading: terraform/main.tf                        │
│ 2m 45s  Implementer   🧠 Analyzing import constraints                      │
│ 2m 50s  Implementer   📖 Reading spec file: specs/rds-import.edn           │
│ 2m 55s  Implementer   ▶ Started implementation phase                       │
│ 3m 10s  Planner       ✓ Code generation complete                           │
│ 3m 15s  Planner       🧠 Evaluating risks and mitigation strategies        │
│ 3m 30s  Planner       📖 Reading: knowledge base (RDS patterns)            │
│ 4m 00s  Planner       ▶ Started planning phase                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ [a] Toggle activity log  [d] Deep dive  [Esc] Back                         │
╰─────────────────────────────────────────────────────────────────────────────╯

# Symbols:
# ▸ - Completed agent (collapsed)
# ● - Active agent (expanded)
# ○ - Waiting/pending agent
# ⟳ - Currently processing
# 📖 - Reading/loading
# 🧠 - Thinking/analyzing
# ✓ - Completed milestone
# ▶ - Started
```

### Web Dashboard Agent Activity Panel

```html
┌────────────────────────────────────────────────────────────────────────────┐
│  Workflow: rds-import                                    [Phase: Implement] │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  Agent Activity                                            [Live Updates]  │
│                                                                            │
│  ● Implementer                                              2m 34s        │
│    ┌──────────────────────────────────────────────────────────────────┐   │
│    │ ⟳ Generating Terraform import blocks...                         │   │
│    │                                                                  │   │
│    │ Progress: [████████▓▓▓▓▓▓▓▓▓▓] 40%                              │   │
│    │                                                                  │   │
│    │ ↳ Subagent: terraform-planner (12s)                             │   │
│    │   ⟳ Running: terraform plan -out=plan.tfplan                    │   │
│    └──────────────────────────────────────────────────────────────────┘   │
│                                                                            │
│  ✓ Planner                                                 Completed       │
│    └─ Generated 5-step implementation plan (2m ago)                        │
│                                                                            │
│  ○ Tester                                                  Waiting         │
│    └─ Queued - waiting for Implementer                                    │
│                                                                            │
│  Activity Timeline                                                         │
│  ────────────────────────────────────────────────────────────────────     │
│                                                                            │
│  Now     ● Implementer: Generating Terraform import blocks...             │
│  -6s     📖 Implementer: Reading terraform/main.tf                        │
│  -11s    🧠 Implementer: Analyzing import constraints                     │
│  -16s    📖 Implementer: Reading spec file                                │
│  -21s    ▶ Implementer: Started implementation phase                      │
│  -1m 36s ✓ Planner: Plan generation complete                              │
│  -1m 41s 🧠 Planner: Evaluating risks                                     │
│  -1m 56s 📖 Planner: Reading knowledge base                               │
│  -2m 16s ▶ Planner: Started planning phase                                │
│                                                                            │
│  [View Full Log]  [Pause Updates]                                         │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

### Real-Time Updates

```javascript
// Web dashboard: Subscribe to workflow status stream

const eventSource = new EventSource(`/api/workflows/${workflowId}/stream`);

eventSource.addEventListener('agent-status', (event) => {
  const status = JSON.parse(event.data);

  // Update UI with new status
  updateAgentStatus(status.agent_id, status.message);

  // Add to activity timeline
  addActivityEvent({
    timestamp: status.timestamp,
    agent: status.agent_id,
    message: status.message,
    type: status.status_type
  });
});

eventSource.addEventListener('subagent-spawned', (event) => {
  const spawn = JSON.parse(event.data);

  // Show subagent in UI
  showSubagent(spawn.parent_agent_id, spawn.subagent_id, spawn.purpose);
});

eventSource.addEventListener('milestone-reached', (event) => {
  const milestone = JSON.parse(event.data);

  // Highlight milestone
  showMilestone(milestone.agent_id, milestone.milestone);
});
```

---

## Benefits

### For Users

1. **Transparency** - See exactly what agents are doing
2. **Confidence** - Know the system is working, not stuck
3. **Debugging** - When something fails, see where it got stuck
4. **Learning** - Understand how agents think and work

### For Operators

1. **Monitoring** - Track agent performance and bottlenecks
2. **Debugging** - Trace failures to specific agent actions
3. **Optimization** - Identify slow operations
4. **Auditing** - Full activity log for compliance

### For Development

1. **Testing** - Verify agent behavior in integration tests
2. **Profiling** - Measure agent performance
3. **Iteration** - Improve prompts and strategies based on real activity
4. **Observability** - Production monitoring of agent fleet

---

## Performance Considerations

### Event Volume

**Concern:** Too many events could overwhelm UI and logs

**Solution:**

- **Throttle**: Max 2-3 status updates per second per agent
- **Batch**: Group rapid-fire events (e.g., reading multiple files)
- **Filter**: UI can subscribe to specific event types only
- **Buffer**: Server buffers events, sends batches every 500ms

### Storage

**Concern:** Status events could bloat logs

**Solution:**

- **Separate stream**: Status events in separate log stream from audit log
- **TTL**: Status events expire after 24 hours (audit events kept forever)
- **Compression**: Status logs compressed aggressively
- **Sampling**: In production, sample status events (keep every 10th)

---

## Next Steps

1. **Update Agent Interface** - Add `emit-status` to all agents
2. **Build Event Bus** - Pub/sub for status events
3. **Add SSE Endpoint** - `/api/workflows/:id/stream`
4. **Update TUI** - Add agent activity panel
5. **Update Web Dashboard** - Add agent activity component
6. **Testing** - Integration tests verify status emission

---

## Example: Full Workflow Status Stream

```clojure
;; User executes workflow
(workflow/execute engine spec)

;; Status events emitted:

{:event/type :agent-started
 :agent/id :planner
 :message "Planner agent started"}

{:event/type :agent-status
 :agent/id :planner
 :status/type :reading
 :message "Reading spec file: specs/rds-import.edn"}

{:event/type :agent-status
 :agent/id :planner
 :status/type :thinking
 :message "Analyzing import constraints and dependencies"}

{:event/type :llm-request
 :agent/id :planner
 :llm/model "claude-sonnet-4"
 :message "Calling Claude Sonnet (2.4k tokens)..."}

{:event/type :llm-response
 :agent/id :planner
 :llm/duration-ms 3200
 :message "Received response (850 tokens, 3.2s)"}

{:event/type :agent-status
 :agent/id :planner
 :status/type :generating
 :message "Generating implementation plan"}

{:event/type :milestone-reached
 :agent/id :planner
 :milestone :plan-complete
 :message "Plan generation complete"}

{:event/type :agent-completed
 :agent/id :planner
 :duration-ms 45000
 :message "Planner agent completed"}

{:event/type :agent-started
 :agent/id :implementer
 :message "Implementer agent started"}

{:event/type :agent-status
 :agent/id :implementer
 :status/type :reading
 :message "Reading plan from Planner"}

{:event/type :subagent-spawned
 :parent-agent/id :implementer
 :subagent/id :terraform-planner
 :message "Spawned subagent: terraform-planner"}

{:event/type :agent-status
 :agent/id :terraform-planner
 :status/type :running
 :message "Running: terraform plan -out=plan.tfplan"}

;; ... etc
```

**User sees in real-time:**

- Planner reading spec → thinking → calling Claude → generating plan ✓
- Implementer reading plan → spawned terraform-planner → running terraform plan
- Live updates every second, exactly like watching Claude Code work
