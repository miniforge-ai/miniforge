# miniforge.ai — Reporting & Status Specification

**Version:** 0.1.0  
**Status:** Draft  
**Date:** 2026-01-18  

---

## 1. Overview

### 1.1 Purpose

miniforge.ai provides **comprehensive visibility** into all workflows and the meta loop through:

- Real-time dashboard views of workflow status
- Meta loop signal visibility and trends
- Customer interaction as a "Meta Agent" with control authority
- Support for Meta-Meta loops (including agent-driven)

Customers are not passive observers—they are **first-class participants** in the control plane.

### 1.2 Design Principles

1. **Customers as Meta Agents**: Humans have the same interface as the Operator agent
2. **Observable by default**: All state is queryable; nothing is hidden
3. **Actionable views**: Dashboards enable control, not just observation
4. **Recursive meta**: The same mechanisms that power Meta loop power Meta-Meta
5. **Real-time + Historical**: Live updates with full history access

---

## 2. Customer as Meta Agent

### 2.1 Conceptual Model

```
┌─────────────────────────────────────────────────────────────────┐
│                        META-META LAYER                          │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │   Human     │    │  External   │    │  AI Meta    │         │
│  │  Operator   │    │  Automation │    │    Agent    │         │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘         │
│         │                  │                  │                 │
│         └──────────────────┼──────────────────┘                 │
│                            ▼                                    │
│                   ┌─────────────────┐                           │
│                   │  Control API    │                           │
│                   └────────┬────────┘                           │
└────────────────────────────┼────────────────────────────────────┘
                             ▼
┌────────────────────────────────────────────────────────────────┐
│                         META LAYER                              │
│  ┌────────────────┐              ┌────────────────┐            │
│  │ Operator Agent │◄────────────►│   Meta Loop    │            │
│  └────────┬───────┘              └────────┬───────┘            │
│           │                               │                     │
│           ▼                               ▼                     │
│  ┌────────────────────────────────────────────────────┐        │
│  │              Workflow Orchestration                 │        │
│  └────────────────────────────────────────────────────┘        │
└────────────────────────────────────────────────────────────────┘
                             ▼
┌────────────────────────────────────────────────────────────────┐
│                      WORKFLOW LAYER                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │Workflow A│  │Workflow B│  │Workflow C│  │Workflow D│       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
└────────────────────────────────────────────────────────────────┘
```

### 2.2 Meta Agent Capabilities

Customers (via dashboard or API) can:

| Capability              | Description                                      |
|-------------------------|--------------------------------------------------|
| View all workflows      | Status, progress, health of every workflow       |
| Control workflows       | Pause, resume, stop, restart any workflow        |
| View meta loop          | Signals, trends, proposed improvements           |
| Approve/reject changes  | Gate meta loop improvements                      |
| Adjust policies         | Modify thresholds, budgets, gates                |
| Spawn workflows         | Create new workflows programmatically            |
| Query history           | Full audit trail and artifact provenance         |
| Configure alerts        | Set up notifications for conditions              |

### 2.3 Meta Agent Interface

```clojure
(defprotocol MetaAgent
  ;; Observation
  (get-system-status [this]
    "Overall system health and summary")
  
  (list-workflows [this criteria]
    "List workflows with filtering/sorting")
  
  (get-workflow-detail [this workflow-id]
    "Full detail for a specific workflow")
  
  (get-meta-loop-status [this]
    "Current meta loop state, signals, pending improvements")
  
  (query-signals [this criteria time-range]
    "Query meta loop signals")
  
  ;; Control
  (execute-command [this command]
    "Issue a workflow control command")
  
  (approve-improvement [this improvement-id]
    "Approve a meta loop improvement proposal")
  
  (reject-improvement [this improvement-id reason]
    "Reject a meta loop improvement proposal")
  
  (update-policy [this policy-path new-value]
    "Modify a policy setting")
  
  ;; Alerts
  (configure-alert [this alert-config]
    "Set up an alert condition")
  
  (list-alerts [this]
    "List configured alerts")
  
  (acknowledge-alert [this alert-id]
    "Acknowledge a triggered alert"))
```

---

## 3. Dashboard Views

### 3.1 System Overview Dashboard

**Purpose**: At-a-glance health of the entire system

**Components**:

| Component              | Data                                            |
|------------------------|-------------------------------------------------|
| Workflow Summary       | Active, Pending, Completed, Failed counts       |
| Resource Usage         | Tokens, cost, compute utilization               |
| Meta Loop Health       | Signal trends, improvement status               |
| Recent Events          | Last N significant events across all workflows  |
| Alerts                 | Active alerts requiring attention               |

**Schema**:

```clojure
{:dashboard/system-overview
 {:workflows
  {:active    integer
   :pending   integer
   :completed {:last-24h integer :total integer}
   :failed    {:last-24h integer :total integer}}
  
  :resources
  {:tokens   {:used integer :limit integer :percent float}
   :cost-usd {:used float :limit float :percent float}
   :compute  {:active-agents integer :max-agents integer}}
  
  :meta-loop
  {:status   keyword           ; :healthy, :degraded, :learning
   :pending-improvements integer
   :last-improvement-at inst}
  
  :recent-events   [Event]     ; last 20 events
  :active-alerts   [Alert]}}
```

### 3.2 Workflow List View

**Purpose**: Browse and manage all workflows

**Columns**:

| Column        | Description                            | Sortable | Filterable |
|---------------|----------------------------------------|----------|------------|
| Name          | Workflow name/identifier               | Yes      | Yes        |
| Status        | Current status                         | Yes      | Yes        |
| Phase         | Current outer loop phase               | Yes      | Yes        |
| Progress      | Completion percentage                  | Yes      | No         |
| Priority      | Scheduling priority                    | Yes      | Yes        |
| Age           | Time since creation                    | Yes      | Yes        |
| Cost          | Resources consumed                     | Yes      | No         |
| Owner         | Who created/owns                       | Yes      | Yes        |
| Tags          | Classification tags                    | No       | Yes        |
| Actions       | Quick actions (pause, stop, etc.)      | No       | No         |

**Filter Presets**:

- Active (RUNNING)
- Needs Attention (PAUSED, FAILED)
- My Workflows
- High Priority
- Recently Completed

### 3.3 Workflow Detail View

**Purpose**: Deep dive into a single workflow

**Sections**:

```clojure
{:workflow-detail
 {:header
  {:id         uuid
   :name       string
   :status     keyword
   :phase      keyword
   :progress   float
   :created-at inst
   :owner      string}
  
  :timeline
  [{:phase     keyword
    :started   inst
    :completed inst
    :status    keyword
    :artifacts [uuid]}]
  
  :current-task
  {:task-id   uuid
   :type      keyword
   :agent     keyword
   :started   inst
   :iteration integer}
  
  :artifacts
  [{:id      uuid
    :type    keyword
    :version string
    :created inst}]
  
  :resources
  {:budget   {:tokens n :cost n :time n}
   :consumed {:tokens n :cost n :time n}
   :remaining {:tokens n :cost n :time n}}
  
  :logs
  [LogEntry]                  ; recent logs for this workflow
  
  :children
  [WorkflowSummary]           ; sub-workflows if any
  
  :actions
  [:pause :resume :stop :restart :adjust-priority :adjust-budget]}}
```

### 3.4 Meta Loop Dashboard

**Purpose**: Visibility into the self-improvement system

**Sections**:

| Section               | Content                                        |
|-----------------------|------------------------------------------------|
| Signal Summary        | Current signal values and trends               |
| Improvement Queue     | Pending improvements awaiting approval         |
| Recent Improvements   | Recently applied changes and their impact      |
| Policy Overview       | Current thresholds and configurations          |
| Anomaly Alerts        | Detected patterns requiring attention          |

**Signal Visualization**:

```clojure
{:meta-loop-signals
 [{:signal-type    :review-rejection-rate
   :current-value  0.15
   :trend          :increasing    ; :stable, :increasing, :decreasing
   :threshold      0.20
   :status         :warning}      ; :normal, :warning, :critical
  
  {:signal-type    :avg-inner-iterations
   :current-value  2.3
   :trend          :stable
   :threshold      4.0
   :status         :normal}
  ...]}
```

**Improvement Queue**:

```clojure
{:pending-improvements
 [{:improvement-id  uuid
   :type            :prompt-refinement
   :target          :implementer-agent
   :rationale       "High repair rate on type errors"
   :proposed-change {:old "..." :new "..."}
   :risk-level      :low
   :rollout         :canary
   :proposed-at     inst
   :evidence        [SignalRef]}]}
```

### 3.5 Audit & History View

**Purpose**: Full traceability and compliance

**Capabilities**:

- Search all events by time, type, workflow, agent
- Trace artifact provenance (intent → artifact → deployment)
- View decision history (who approved what, when)
- Export audit reports

---

## 4. Meta-Meta Loops

### 4.1 Concept

A Meta-Meta loop operates on the Meta loop itself:

- Monitors Meta loop effectiveness
- Adjusts Meta loop parameters
- Can be human-driven or agent-driven

```
Meta-Meta Observer
      │
      │ observes
      ▼
┌─────────────┐
│  Meta Loop  │ ◄── signals about process
└──────┬──────┘
       │ observes
       ▼
┌─────────────┐
│  Workflows  │ ◄── signals about work
└─────────────┘
```

### 4.2 Meta-Meta Signals

| Signal                          | Meaning                                    |
|---------------------------------|--------------------------------------------|
| Improvement acceptance rate     | Are Meta proposals getting approved?       |
| Improvement impact              | Do improvements actually help?             |
| Signal accuracy                 | Do signals correlate with real problems?   |
| False positive rate             | Are we alerting on non-issues?             |
| Time to detect                  | How quickly do we notice problems?         |
| Recovery time                   | How quickly do improvements take effect?   |

### 4.3 Meta-Meta Actions

| Action                          | Effect                                     |
|---------------------------------|--------------------------------------------|
| Adjust signal thresholds        | Tune sensitivity of Meta loop              |
| Modify improvement rollout      | Change canary % or shadow duration         |
| Add/remove signals              | Expand or contract what Meta observes      |
| Adjust approval requirements    | More/less human oversight                  |
| Tune Operator agent             | Modify Operator decision rules             |

### 4.4 Agent-Driven Meta-Meta

An AI agent can serve as Meta-Meta observer:

```clojure
{:meta-meta-agent
 {:role        :meta-meta-observer
  :inputs      [:meta-loop-signals :improvement-outcomes :human-overrides]
  :outputs     [:meta-adjustments :recommendations]
  :constraints {:requires-approval true
                :max-changes-per-day 3
                :rollback-on-regression true}}}
```

---

## 5. Alerting System

### 5.1 Alert Configuration

```clojure
{:alert/id           uuid
 :alert/name         string
 :alert/description  string
 :alert/condition    [:and|:or|:not|:threshold ...]
 :alert/severity     keyword        ; :info, :warning, :critical
 :alert/channels     [Channel]      ; where to send
 :alert/throttle     {:min-interval-ms long
                      :max-per-hour integer}
 :alert/enabled      boolean
 :alert/owner        string}
```

### 5.2 Condition Types

```clojure
;; Threshold
{:type :threshold
 :metric :workflow.failed.count
 :operator :>
 :value 5
 :window-minutes 60}

;; Rate of change
{:type :rate
 :metric :meta.signal.error-rate
 :change :increase
 :percent 50
 :window-minutes 30}

;; Pattern
{:type :pattern
 :events [:workflow/failed :workflow/failed :workflow/failed]
 :within-minutes 10}

;; Compound
{:type :and
 :conditions [{:type :threshold ...}
              {:type :threshold ...}]}
```

### 5.3 Alert Channels

| Channel     | Configuration                              |
|-------------|-------------------------------------------|
| Email       | Recipients, template                      |
| Slack       | Webhook URL, channel                      |
| PagerDuty   | Integration key, severity mapping         |
| Webhook     | URL, auth, payload template               |
| In-app      | Dashboard notification                    |

### 5.4 Alert Lifecycle

```
CONDITION MET ──► FIRING ──► ACKNOWLEDGED ──► RESOLVED
                    │              │
                    │              ▼
                    │         (manual action)
                    │
                    └──► (auto-resolve when condition clears)
```

---

## 6. Real-Time Updates

### 6.1 WebSocket Protocol

```clojure
;; Client subscription
{:type :subscribe
 :topics [:workflow/all        ; all workflow events
          :workflow/abc123     ; specific workflow
          :meta-loop           ; meta loop events
          :alerts]}            ; alert events

;; Server push
{:type :event
 :topic :workflow/abc123
 :event {:event/type :outer/phase-completed
         :phase :implement
         :timestamp inst
         ...}}
```

### 6.2 Event Topics

| Topic                    | Events                                     |
|--------------------------|--------------------------------------------|
| `workflow/all`           | All workflow lifecycle events              |
| `workflow/<id>`          | Events for specific workflow               |
| `meta-loop`              | Meta loop signals and improvements         |
| `alerts`                 | Alert firing/resolution                    |
| `system`                 | System-level events                        |

### 6.3 Polling Fallback

For clients without WebSocket:

```
GET /api/v1/events?since=<timestamp>&topics=workflow,meta-loop
```

---

## 7. API Endpoints

### 7.1 REST API

```
# System
GET  /api/v1/status                    # System overview
GET  /api/v1/health                    # Health check

# Workflows
GET  /api/v1/workflows                 # List workflows (paginated)
POST /api/v1/workflows                 # Create workflow
GET  /api/v1/workflows/:id             # Get workflow detail
POST /api/v1/workflows/:id/pause       # Pause workflow
POST /api/v1/workflows/:id/resume      # Resume workflow
POST /api/v1/workflows/:id/stop        # Stop workflow
POST /api/v1/workflows/:id/restart     # Restart workflow

# Meta Loop
GET  /api/v1/meta/status               # Meta loop status
GET  /api/v1/meta/signals              # Query signals
GET  /api/v1/meta/improvements         # List pending improvements
POST /api/v1/meta/improvements/:id/approve
POST /api/v1/meta/improvements/:id/reject

# Policies
GET  /api/v1/policies                  # List policies
PUT  /api/v1/policies/:path            # Update policy

# Alerts
GET  /api/v1/alerts                    # List alerts
POST /api/v1/alerts                    # Create alert
PUT  /api/v1/alerts/:id                # Update alert
DELETE /api/v1/alerts/:id              # Delete alert
POST /api/v1/alerts/:id/acknowledge    # Acknowledge alert

# Audit
GET  /api/v1/audit/events              # Query audit log
GET  /api/v1/artifacts/:id/provenance  # Artifact trace
```

### 7.2 GraphQL API (Optional)

For flexible queries:

```graphql
query {
  workflows(status: RUNNING, first: 10) {
    edges {
      node {
        id
        name
        phase
        progress
        resources {
          tokensUsed
          costUsd
        }
        currentTask {
          type
          agent
        }
      }
    }
  }
  
  metaLoop {
    status
    signals {
      type
      value
      trend
    }
    pendingImprovements {
      id
      type
      rationale
    }
  }
}
```

---

## 8. Dashboard Technology

### 8.1 Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Web Dashboard                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │    React    │  │  Charts/    │  │  Real-time  │     │
│  │ Components  │  │  D3/Vega    │  │  WebSocket  │     │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘     │
│         └─────────────────┼─────────────────┘           │
│                           ▼                             │
│                    ┌─────────────┐                      │
│                    │  API Client │                      │
│                    └──────┬──────┘                      │
└───────────────────────────┼─────────────────────────────┘
                            │ HTTPS/WSS
                            ▼
┌───────────────────────────────────────────────────────┐
│                   miniforge API                        │
└───────────────────────────────────────────────────────┘
```

### 8.2 UI Components

| Component            | Purpose                                    |
|----------------------|--------------------------------------------|
| WorkflowCard         | Summary card for workflow list             |
| WorkflowTimeline     | Phase progression visualization            |
| SignalGauge          | Current value + threshold indicator        |
| SignalTrend          | Time-series chart of signal                |
| LogViewer            | Filterable log display                     |
| ArtifactTree         | Provenance visualization                   |
| AlertBanner          | Active alert notification                  |
| CommandPalette       | Quick action keyboard interface            |

---

## 9. Deliverables

### Phase 0 (Foundations)

- [ ] Dashboard data schemas
- [ ] Alert configuration schema
- [ ] Event topic definitions

### Phase 1 (API)

- [ ] REST API implementation
- [ ] WebSocket server
- [ ] Authentication/authorization

### Phase 2 (Dashboard)

- [ ] System overview page
- [ ] Workflow list page
- [ ] Workflow detail page
- [ ] Meta loop page

### Phase 3 (Alerting)

- [ ] Alert engine
- [ ] Channel integrations (Slack, email, webhook)
- [ ] Alert management UI

### Phase 4 (Advanced)

- [ ] GraphQL API (optional)
- [ ] Audit/compliance reports
- [ ] Meta-Meta agent integration

---

## 10. Open Questions

1. **Multi-tenant isolation**: How to handle multiple customers on shared infra?
2. **Role-based access**: What RBAC model for dashboard permissions?
3. **Data retention**: How long to keep dashboard-accessible history?
4. **Offline mode**: Should dashboard work with cached data when disconnected?
5. **Mobile**: Native mobile app or responsive web only?
