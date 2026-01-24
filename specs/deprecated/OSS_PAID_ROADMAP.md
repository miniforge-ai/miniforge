# miniforge.ai: OSS/Paid Implementation Roadmap

**Date:** 2026-01-22
**Status:** Planning
**Target Product:** Change Train Wedge (Multi-Repo PR Orchestration with Policy-as-Code)

---

## Executive Summary

### Current State

- **Phase 1 COMPLETE**: ~14K LOC across 16 components
- Foundation components working: schema, logging, LLM, tool, agent, policy, heuristic, knowledge, loop, task, workflow, operator, orchestrator, artifact, reporting
- Postmortem from Phase 1 shows dogfooding works - found bugs that Phase 2 will prevent

### Strategic Decision: The Change Train Wedge

**Target Market:** Platform/Infrastructure teams managing multi-repo changes (Terraform, K8s, microservices)

**Core Value Proposition:**

1. **Explicit Repo DAG** - Model cross-repo dependencies, enforce merge order topologically
2. **Policy-as-Code** - Extensible scanner marketplace (Terraform, K8s, CloudFormation, custom)
3. **Semantic Intent** - "IMPORT means no creates" - validate intent, not just artifacts
4. **Evidence Bundles** - Full audit trail from intent → plan → PRs → merge

**Why This Wedge:**

- Narrower, higher-value use case (startup strategy)
- Delivers basic Fleet Mode (option A) plus advanced Change Train (option B)
- Clear paid differentiation from basic OSS

---

## Architecture: OSS vs Paid Boundary

### OSS (Community Edition)

**Value Proposition:** Local-first single-user fleet view for PR management

**Components Included:**

```
miniforge-oss/
├── components/
│   ├── schema/              # Domain types (public API)
│   ├── logging/             # EDN structured logging
│   ├── tool/                # Tool protocol
│   ├── agent/               # Agent runtime
│   ├── task/                # Task management
│   ├── loop/                # Inner/outer loops
│   ├── workflow/            # SDLC workflow engine
│   ├── knowledge/           # Zettelkasten (learning capture)
│   ├── policy/              # Core policy engine (gate protocol)
│   ├── heuristic/           # Heuristic registry (versioning)
│   ├── artifact/            # Local artifact store
│   ├── llm/                 # LLM client (Claude API)
│   ├── reporting/           # CLI reporting/views
│   └── pr-loop/             # Basic PR management (NEW)
│
├── bases/
│   └── cli/                 # Single-user CLI
│
└── projects/
    └── oss-cli/             # OSS build configuration
```

**Capabilities:**

- ✅ Local-first operation on dev machine
- ✅ Watch PRs across N repos
- ✅ CLI dashboard (non-interactive status)
- ✅ Manual PR actions (respond to comments, merge)
- ✅ Basic policy gates (syntax, lint, test)
- ✅ Sample policy packs (Terraform, K8s basics)
- ✅ Single workflow at a time
- ✅ Local heuristic store
- ✅ Knowledge capture (learnings)

**License:** Apache 2.0

### Paid (Operators Console - Enterprise Edition)

**Value Proposition:** Multi-repo change orchestration with policy governance at team/org scale

**Additional Components:**

```
miniforge-enterprise/
├── components/
│   ├── repo-dag/            # Multi-repo dependency graph (NEW)
│   ├── pr-train/            # Linked PR choreography (NEW)
│   ├── policy-distribution/ # Signed packs, org distribution (NEW)
│   ├── eval-harness/        # Canary/shadow evaluation (NEW)
│   ├── telemetry/           # Analytics, MTTR tracking (NEW)
│   ├── authz/               # RBAC, approvals, audit (NEW)
│   ├── fleet-advanced/      # Advanced scheduling (NEW)
│   └── meta-loop/           # Self-improvement (NEW)
│
├── bases/
│   ├── operators-console/   # Multi-user web UI (NEW)
│   ├── fleet-daemon/        # Remote runners (NEW)
│   └── api-server/          # REST API (NEW)
│
└── projects/
    └── enterprise/          # Enterprise build
```

**Capabilities (everything OSS plus):**

- ✅ **Repo DAG with topological sort** - Enforce merge order across repos
- ✅ **PR Train orchestration** - Linked PRs with dependency tracking
- ✅ **Policy pack marketplace** - Import/share rulesets (Dewey decimal organized)
- ✅ **Signed policy packs** - Org-wide distribution with versioning
- ✅ **Domain-specific scanners** - Terraform, K8s, CloudFormation, Pulumi, custom
- ✅ **Semantic intent validation** - "IMPORT" vs "CREATE" enforcement
- ✅ **Evidence bundles** - Full audit trail per change train
- ✅ **Multi-user RBAC** - Roles, approvals, escalations
- ✅ **Advanced scheduling** - Resource limits, priority queues, concurrent trains
- ✅ **Telemetry & analytics** - MTTR, success rates, cost tracking
- ✅ **Meta loop** - Self-improvement via heuristic evolution
- ✅ **Web dashboard** - Real-time multi-user UI
- ✅ **Remote runners** - Distributed execution
- ✅ **Enterprise integrations** - Slack, PagerDuty, Jira, SSO

**License:** Commercial (seat-based or repo-based pricing)

---

## Implementation Phases

### Phase 2A: Complete Agent Layer (4-6 weeks)

**Goal:** Implement remaining agents and integrate with existing policy/loop components

**Components to Build:**

#### 2A.1: Tester Agent

```clojure
;; components/agent/src/ai/miniforge/agent/tester.clj
(defn generate-tests
  "Generate tests from spec and code"
  [task context]
  ;; Use heuristics for test generation prompts
  ;; Validate rich comment examples actually run
  ;; Report coverage metrics
  )
```

**Tasks:**

- [ ] Implement test generation from specs
- [ ] Add coverage reporting
- [ ] Validate rich comment examples (prevent Phase 1 bugs!)
- [ ] Integration with loop/gates.clj

#### 2A.2: Reviewer Agent

```clojure
;; components/agent/src/ai/miniforge/agent/reviewer.clj
(defn review-artifact
  "Review artifact against policies"
  [artifact policies context]
  ;; Run linters (clj-kondo, eslint, etc.)
  ;; Check against policy gates
  ;; Generate feedback
  ;; Approve/reject with rationale
  )
```

**Tasks:**

- [ ] Implement code review logic
- [ ] Integrate with policy/gates.clj (use what we built!)
- [ ] Add feedback generation
- [ ] Make linting MANDATORY (non-bypassable)

#### 2A.3: Agent-Loop Integration

**Tasks:**

- [ ] Agents must use policy gates from policy/
- [ ] Inner loop uses agents for repair
- [ ] Outer loop enforces phase gates
- [ ] Cannot bypass validation (fix Phase 1 issue)

**Deliverable:** Self-validating system that catches its own bugs

---

### Phase 2B: Repo DAG & PR Train (Core Wedge - 6-8 weeks)

**Goal:** Build the foundational components for the "Change Train" wedge product

#### 2B.1: Repo DAG Component (NEW)

```clojure
;; components/repo-dag/src/ai/miniforge/repo_dag/interface.clj

(defprotocol RepoDag
  (create-dag [this repos edges]
    "Create dependency graph from repo definitions")

  (topological-sort [this dag]
    "Return repos in merge order")

  (validate-dag [this dag]
    "Check for cycles, unreachable nodes")

  (get-merge-order [this dag changed-repos]
    "Given changed repos, return ordered list of PRs to merge"))

;; Example DAG
{:repo-dag/id uuid
 :repo-dag/nodes
 [{:repo/url "github.com/acme/infra"
   :repo/type :terraform
   :repo/path "terraform/"}
  {:repo/url "github.com/acme/k8s"
   :repo/type :kubernetes
   :repo/path "k8s/"}
  {:repo/url "github.com/acme/backend"
   :repo/type :application
   :repo/path "src/"}]

 :repo-dag/edges
 [{:from "acme/infra"
   :to "acme/k8s"
   :constraint :infra-before-k8s
   :rationale "K8s needs AWS resources provisioned first"}
  {:from "acme/k8s"
   :to "acme/backend"
   :constraint :manifests-before-deploy
   :rationale "Manifests must exist before app deploys"}]}
```

**Tasks:**

- [ ] Define DAG schema (nodes, edges, constraints)
- [ ] Implement topological sort (Kahn's algorithm)
- [ ] Add cycle detection
- [ ] Create DAG visualization (CLI)
- [ ] Persist DAG to artifact store

**Algorithm Reference:**

```clojure
(defn topological-sort
  "Kahn's algorithm for topological sorting"
  [dag]
  (let [in-degree (compute-in-degrees dag)
        queue (filter #(zero? (in-degree %)) (nodes dag))]
    (loop [result []
           q queue
           degrees in-degree]
      (if (empty? q)
        result
        (let [node (first q)
              neighbors (successors dag node)
              new-degrees (reduce #(update %1 %2 dec) degrees neighbors)
              new-queue (concat (rest q)
                               (filter #(zero? (new-degrees %)) neighbors))]
          (recur (conj result node) new-queue new-degrees))))))
```

#### 2B.2: PR Train Component (NEW)

```clojure
;; components/pr-train/src/ai/miniforge/pr_train/interface.clj

(defprotocol PRTrain
  (create-train [this dag spec]
    "Create PR train from DAG and change spec")

  (get-train-status [this train-id]
    "Get status of all PRs in train")

  (advance-train [this train-id]
    "Merge next ready PR in sequence")

  (get-blocked-prs [this train-id]
    "Get PRs blocked by dependencies"))

;; Example PR Train
{:pr-train/id uuid
 :pr-train/spec-id uuid
 :pr-train/dag-id uuid
 :pr-train/created-at inst
 :pr-train/status :in-progress  ; :planning, :in-progress, :completed, :failed

 :pr-train/prs
 [{:pr/repo "acme/infra"
   :pr/number 123
   :pr/url "github.com/acme/infra/pull/123"
   :pr/status :merged
   :pr/sequence 1
   :pr/dependencies []
   :pr/merged-at inst}

  {:pr/repo "acme/k8s"
   :pr/number 456
   :pr/url "github.com/acme/k8s/pull/456"
   :pr/status :approved
   :pr/sequence 2
   :pr/dependencies ["acme/infra#123"]
   :pr/ready-to-merge? true}  ; dep is merged

  {:pr/repo "acme/backend"
   :pr/number 789
   :pr/url "github.com/acme/backend/pull/789"
   :pr/status :open
   :pr/sequence 3
   :pr/dependencies ["acme/k8s#456"]
   :pr/ready-to-merge? false}]  ; dep not merged yet

 :pr-train/evidence
 {:intent "Import existing RDS instance to Terraform"
  :plan-artifact-id uuid
  :scan-results [{:repo "acme/infra" :scanner :terraform-plan :passed? true}]
  :semantic-checks [{:check :no-creates :passed? true}]}}
```

**Tasks:**

- [ ] Define PR train schema
- [ ] Implement train creation from DAG
- [ ] Add train status tracking
- [ ] Create merge sequencer (respects topo order)
- [ ] Build dependency checker
- [ ] Add evidence bundle generation

---

### Phase 2C: Policy Packs & Scanner Marketplace (6 weeks)

**Goal:** Make policy extensible with Dewey-organized rulesets (like cursor-rules)

#### 2C.1: Policy Pack Schema

```clojure
;; Based on cursor-rules model

{:policy-pack/id "terraform-aws-production"
 :policy-pack/version "2026.01.22"
 :policy-pack/org "miniforge"  ; org/author
 :policy-pack/name "Terraform AWS Production Rules"
 :policy-pack/description "Production-grade AWS Terraform policies"

 ;; Dewey decimal organization (like cursor-rules)
 :policy-pack/categories
 [{:category "300-infrastructure"
   :rules
   [{:rule/id "300-terraform-plan"
     :rule/name "Terraform Plan Review"
     :rule/file "300-infrastructure/terraform-plan.mdc"
     :rule/scanner :terraform-plan-scanner
     :rule/severity :critical}

    {:rule/id "301-terraform-state"
     :rule/name "Terraform State Safety"
     :rule/scanner :terraform-state-scanner
     :rule/severity :high}]}

  {:category "400-orchestration"
   :rules
   [{:rule/id "400-k8s-manifests"
     :rule/name "Kubernetes Manifest Validation"
     :rule/scanner :k8s-manifest-scanner
     :rule/severity :high}]}]

 ;; Signature for verification
 :policy-pack/signature
 {:signed-by "miniforge-bot"
  :signature-hash "sha256:..."
  :signed-at inst}

 ;; Metadata
 :policy-pack/metadata
 {:tags [:terraform :aws :production]
  :min-miniforge-version "0.2.0"
  :license "Apache-2.0"}}
```

#### 2C.2: Scanner Protocol

```clojure
;; components/policy/src/ai/miniforge/policy/scanner.clj

(defprotocol Scanner
  (scan [this artifact context]
    "Scan artifact, return findings")

  (scanner-id [this]
    "Unique scanner identifier")

  (supports? [this artifact-type]
    "Does this scanner support artifact type?"))

;; Built-in scanners (OSS)
;; components/policy/src/ai/miniforge/policy/scanners/

;; terraform_plan_scanner.clj
(defn scan-terraform-plan
  "Scan terraform plan for destructive changes"
  [plan-artifact context]
  (let [plan-output (:content plan-artifact)
        destructive-changes (parse-plan-for-destroys plan-output)
        network-recreations (find-network-recreations destructive-changes)]
    {:scanner/id :terraform-plan
     :scanner/findings
     (concat
       ;; Check for network resource recreations (CRITICAL)
       (when (seq network-recreations)
         [{:severity :critical
           :category :network-disruption
           :message "Network resources will be destroyed and recreated"
           :resources network-recreations
           :remediation "Use create_before_destroy lifecycle or refactor change"}])

       ;; Check for unexpected creates when intent is IMPORT
       (when (= (:intent context) :import)
         (let [creates (parse-plan-for-creates plan-output)]
           (when (seq creates)
             [{:severity :critical
               :category :semantic-violation
               :message "IMPORT intent but plan shows resource creation"
               :resources creates
               :remediation "Verify import blocks are correct"}]))))}))

;; k8s_manifest_scanner.clj
(defn scan-k8s-manifests
  "Scan K8s manifests for issues"
  [manifest-artifact context]
  ;; Check for:
  ;; - Missing resource limits
  ;; - Privileged containers
  ;; - Host path mounts
  ;; - Latest image tags
  ;; - Missing health checks
  )
```

**Built-in Scanners (OSS):**

- [ ] `terraform-plan-scanner` - Detect destroys, network changes, forced recreations
- [ ] `terraform-state-scanner` - Validate state drift
- [ ] `k8s-manifest-scanner` - K8s best practices
- [ ] `dockerfile-scanner` - Dockerfile security
- [ ] `syntax-scanner` - Language-specific syntax
- [ ] `lint-scanner` - Linter integration (clj-kondo, eslint, etc.)
- [ ] `test-scanner` - Test coverage validation
- [ ] `secret-scanner` - Detect secrets in code

**Marketplace Scanners (Paid/Community):**

- CloudFormation scanner
- Pulumi scanner
- Ansible scanner
- Custom org-specific scanners

#### 2C.3: Policy Distribution

```clojure
;; components/policy-distribution/ (PAID ONLY)

(defprotocol PolicyDistribution
  (publish-pack [this pack signature]
    "Publish signed policy pack to org registry")

  (fetch-pack [this pack-id version]
    "Fetch pack from registry")

  (verify-signature [this pack]
    "Verify pack signature")

  (list-packs [this filters]
    "List available packs"))

;; OSS: Local policy packs only (file-based)
;; Paid: Central registry with signature verification
```

**Tasks:**

- [ ] Define policy pack schema (Dewey-inspired)
- [ ] Implement scanner protocol
- [ ] Build 6-8 built-in scanners
- [ ] Create pack verification (signature checking) - PAID
- [ ] Build pack registry - PAID
- [ ] CLI for pack management (`miniforge policy install terraform-aws`)

---

### Phase 2D: Semantic Intent Validation (3 weeks)

**Goal:** Validate that artifacts match declared intent

```clojure
;; components/task/src/ai/miniforge/task/intent.clj

{:task/intent-type :import  ; vs :create, :modify, :delete, :refactor
 :task/intent-description "Import existing RDS instance to Terraform"

 :task/semantic-constraints
 [{:constraint/type :no-creates
   :constraint/scope :terraform-resources
   :constraint/rationale "Import should only create state entries, not real resources"}

  {:constraint/type :must-match-existing
   :constraint/resource-type :aws_db_instance
   :constraint/identifier "production-db"}

  {:constraint/type :no-destroys
   :constraint/scope :all
   :constraint/rationale "Import must be non-destructive"}]}

;; Semantic validation in loop
(defn validate-semantic-intent
  [artifacts intent]
  (let [terraform-plan (get-artifact artifacts :terraform-plan)]
    (case (:task/intent-type intent)
      :import
      (do
        (assert-no-creates terraform-plan)
        (assert-no-destroys terraform-plan)
        (assert-only-state-changes terraform-plan))

      :create
      (assert-creates-match-spec terraform-plan intent)

      :modify
      (do
        (assert-updates-only terraform-plan)
        (assert-no-recreations terraform-plan))

      :delete
      (assert-destroys-match-spec terraform-plan intent))))
```

**Tasks:**

- [ ] Extend task schema with intent types
- [ ] Define semantic constraints per intent type
- [ ] Implement validators for Terraform
- [ ] Implement validators for K8s
- [ ] Add to policy gate evaluation
- [ ] Generate evidence for audit trail

---

### Phase 3: OSS MVP (2 weeks)

**Goal:** Package OSS version for early adopter testing

#### 3.1: OSS Component Boundary

**Tasks:**

- [ ] Create `projects/oss-cli/` build configuration
- [ ] Exclude paid components from OSS build
- [ ] Add feature flags for OSS vs Paid detection
- [ ] Create OSS README with capabilities
- [ ] Add sample policy packs (3-4 basic ones)

#### 3.2: CLI Enhancements for OSS

```bash
# OSS commands
miniforge fleet watch --repos repos.edn
miniforge fleet status
miniforge pr respond <pr-url>
miniforge pr merge <pr-url>
miniforge policy list
miniforge policy install terraform-basics
```

**Tasks:**

- [ ] Implement `fleet watch` command
- [ ] Add `fleet status` (non-interactive)
- [ ] Build `pr respond` with comment handling
- [ ] Build `pr merge` with safety checks
- [ ] Add `policy` subcommands

#### 3.3: Documentation

**Tasks:**

- [ ] OSS getting started guide
- [ ] Example workflows
- [ ] Policy pack authoring guide
- [ ] Scanner development guide
- [ ] Migration from cursor-rules to miniforge policies

**Deliverable:** Homebrew-installable OSS CLI ready for beta users

---

### Phase 4: Paid Features (Enterprise) (8 weeks)

#### 4.1: Repo DAG UI & Management

**Tasks:**

- [ ] Web UI for DAG visualization (D3.js graph)
- [ ] DAG editor (add/remove edges)
- [ ] DAG validation with cycle detection UI
- [ ] Import DAG from existing repos (auto-detect dependencies)

#### 4.2: PR Train Orchestration UI

**Tasks:**

- [ ] Train creation wizard
- [ ] Real-time train status dashboard
- [ ] Evidence bundle viewer
- [ ] Train analytics (MTTR, success rate)

#### 4.3: Policy Pack Marketplace

**Tasks:**

- [ ] Web registry for policy packs
- [ ] Pack publishing workflow
- [ ] Signature verification UI
- [ ] Pack marketplace browse/search
- [ ] Org-private packs
- [ ] Version pinning and rollback

#### 4.4: RBAC & Multi-User

**Tasks:**

- [ ] User authentication (SSO)
- [ ] Role definitions (admin, operator, viewer)
- [ ] Approval workflows
- [ ] Audit log
- [ ] Team management

#### 4.5: Advanced Scheduling

**Tasks:**

- [ ] Resource limits (max concurrent trains)
- [ ] Priority queues (by org, team, urgency)
- [ ] Train queueing with ETA
- [ ] Conflict detection (same repo in multiple trains)

#### 4.6: Telemetry & Analytics

**Tasks:**

- [ ] Metrics collection (OpenTelemetry)
- [ ] MTTR dashboard
- [ ] Success rate tracking
- [ ] Cost analytics (LLM tokens, time)
- [ ] Alerting integration (Slack, PagerDuty)

**Deliverable:** Enterprise-ready platform with multi-user, RBAC, analytics

---

### Phase 5: Meta Loop (Self-Improvement) (6 weeks)

**Goal:** Implement learning and adaptation (PAID feature)

#### 5.1: Signal Collection

**Tasks:**

- [ ] Training example capture (already designed in learning.spec)
- [ ] Quality score computation
- [ ] Heuristic performance tracking
- [ ] Benchmark scenario curation

#### 5.2: Heuristic Evolution

**Tasks:**

- [ ] Improvement proposal generation
- [ ] Shadow mode evaluation
- [ ] Canary rollout for heuristics
- [ ] A/B testing infrastructure
- [ ] Rollback mechanism

**Deliverable:** Self-improving system that learns from execution

---

## Release Strategy

### When to Release OSS?

**Recommendation: Release OSS BEFORE Paid is Complete**

**Strategy: Progressive Disclosure**

#### Release 1: OSS Beta (After Phase 2B + 3)

**Timing:** ~3-4 months from now

**What's Included:**

- ✅ Core components (agents, loops, workflows)
- ✅ Basic Fleet Mode (watch PRs, manual actions)
- ✅ Policy engine with sample packs
- ✅ CLI tooling
- ✅ Repo DAG (basic)
- ✅ PR Train (basic, single train at a time)
- ❌ No web UI (CLI only)
- ❌ No RBAC (single user)
- ❌ No advanced analytics
- ❌ No meta loop

**Goals:**

1. **Validate product-market fit** - Do platform teams actually use it?
2. **Generate feedback** - What features are critical vs nice-to-have?
3. **Build community** - Early adopters, contributors, policy pack authors
4. **Dogfood at scale** - Use OSS to build Paid version
5. **Marketing** - Show you're not vaporware, build credibility

**License:** Apache 2.0

**Messaging:**
> "miniforge OSS: Local-first PR orchestration for platform teams. Manage multi-repo changes with policy-as-code. Self-hosted, no cloud required."

#### Release 2: OSS Stable + Paid Preview (After Phase 4)

**Timing:** ~6-7 months from now

**What's Included:**

- OSS: Everything from Beta, now stable
- Paid: Preview/beta access to enterprise features
  - Web UI
  - Multi-user
  - RBAC
  - Advanced analytics
  - Policy marketplace

**Goals:**

1. **Convert OSS users to Paid** - Show clear value upgrade path
2. **Validate pricing** - Test different pricing models
3. **Enterprise pilots** - Get 3-5 design partners

**Pricing Model (TBD):**

- Option A: Per-seat ($X/user/month)
- Option B: Per-repo ($Y/repo/month)
- Option C: Hybrid (base fee + per-user)

**Messaging:**
> "miniforge Enterprise: Scale your platform team's PR orchestration across the org. Team collaboration, policy governance, and analytics."

#### Release 3: General Availability (After Phase 5)

**Timing:** ~9-12 months from now

**What's Included:**

- OSS: Fully stable, community-driven
- Paid: Full enterprise feature set including meta loop

**Goals:**

1. **Scale revenue** - 50-100 paying customers
2. **Self-sustaining OSS** - Community contributions, plugin ecosystem
3. **Market leadership** - The standard for multi-repo PR orchestration

---

## Why Release OSS Early?

### Advantages of Early OSS Release

1. **Faster validation** - Know if this solves a real problem
2. **Community leverage** - Others build policy packs, scanners
3. **Credibility** - Not just slides, working software
4. **Dogfooding** - Use OSS to build Paid (meta!)
5. **Hiring** - Attract engineers who use the OSS
6. **Less competition risk** - Already in market with users

### Risks of Early OSS Release

1. **Feature expectations** - Users want Paid features in OSS
2. **Support burden** - Issues, questions, PRs
3. **Forks** - Someone could build Paid features in fork
4. **Quality bar** - OSS needs to be polished

### Mitigation

- **Clear feature matrix** - OSS vs Paid capabilities documented upfront
- **Limited support** - Community support only for OSS
- **Strong paid moat** - Web UI, RBAC, analytics, meta loop are substantial
- **Quality first** - Only release OSS when it works well

---

## Concrete Milestones & Timeline

### Month 1-2: Phase 2A (Agents)

- Week 1-2: Tester agent
- Week 3-4: Reviewer agent
- Week 5-6: Agent-loop integration
- Week 7-8: Dogfood on miniforge itself

**Exit Criteria:**

- [ ] Tester agent generates tests
- [ ] Reviewer agent catches Phase 1 bugs
- [ ] No bypassing validation in workflow
- [ ] All components tested by Tester agent

### Month 3-4: Phase 2B (Repo DAG + PR Train)

- Week 1-2: Repo DAG component
- Week 3-4: PR Train component
- Week 5-6: Integration testing
- Week 7-8: CLI commands

**Exit Criteria:**

- [ ] Can model multi-repo dependencies
- [ ] Topological sort works
- [ ] PR train creates linked PRs
- [ ] Merge order enforced

### Month 4-5: Phase 2C (Policy Packs)

- Week 1-2: Policy pack schema
- Week 3-4: 6 built-in scanners
- Week 5-6: Pack CLI tooling

**Exit Criteria:**

- [ ] Policy packs loadable
- [ ] Scanners work on real Terraform/K8s
- [ ] Can install/uninstall packs
- [ ] Documentation complete

### Month 5: Phase 2D (Semantic Intent)

- Week 1-2: Intent schema
- Week 3-4: Validators

**Exit Criteria:**

- [ ] "IMPORT" intent validated
- [ ] Evidence bundles generated
- [ ] Clear error messages

### Month 6: Phase 3 (OSS MVP)

- Week 1-2: OSS packaging
- Week 3-4: Documentation
- **🚀 OSS Beta Release**

**Exit Criteria:**

- [ ] Homebrew formula works
- [ ] Getting started guide complete
- [ ] 3 example workflows documented
- [ ] Beta user onboarding smooth

### Month 7-10: Phase 4 (Paid Features)

- Month 7: Web UI foundations
- Month 8: RBAC + Multi-user
- Month 9: Analytics + Telemetry
- Month 10: Policy marketplace
- **🚀 Paid Preview Launch**

**Exit Criteria:**

- [ ] 3-5 design partners using Paid
- [ ] Pricing model validated
- [ ] Clear upgrade path from OSS

### Month 11-12: Phase 5 (Meta Loop)

- Month 11: Signal collection + Heuristic evolution
- Month 12: Canary rollout + A/B testing
- **🚀 General Availability**

**Exit Criteria:**

- [ ] System learns from execution
- [ ] Heuristics improve over time
- [ ] Full dogfooding loop complete

---

## Success Metrics

### OSS Success Metrics (Month 6-12)

- **Adoption:** 100+ unique installations (Homebrew analytics)
- **Engagement:** 20+ active weekly users
- **Community:** 10+ external contributors
- **Policy Packs:** 5+ community-contributed packs
- **GitHub:** 500+ stars

### Paid Success Metrics (Month 10-12)

- **Design Partners:** 5 enterprise pilots
- **Conversion:** 10% OSS → Paid conversion
- **Revenue:** $10K MRR by GA
- **Retention:** 90%+ renewal rate
- **NPS:** 50+

---

## Open Questions & Decisions Needed

### Technical Decisions

1. **Policy Pack Distribution**
   - OSS: File-based only? Or basic registry?
   - Paid: Central hosted registry?

2. **Scanner Extensibility**
   - Plugin architecture (Babashka sci sandbox)?
   - Or just Clojure namespaces (compile-time)?

3. **Repo DAG Storage**
   - Per-repo `.miniforge/dag.edn`?
   - Or central config?

4. **Web UI Technology**
   - ClojureScript + Re-frame?
   - Or simple server-rendered HTML?

### Business Decisions

1. **Pricing Model**
   - Per-seat vs per-repo vs hybrid?
   - Monthly vs annual?

2. **Support Model**
   - OSS: Community only?
   - Paid: Email? Slack? Dedicated Slack?

3. **Launch Partners**
   - Who are the 3-5 design partners?
   - What do they get (free/discount)?

---

## Appendix: Component Dependency Graph

```
OSS Components (All Open Source)
┌─────────────────────────────────────────────────────────────┐
│ schema ──► logging ──► tool                                 │
│    │          │          │                                   │
│    ▼          ▼          ▼                                   │
│  agent ──► knowledge  loop ──► policy                        │
│    │          │          │        │                          │
│    ▼          ▼          ▼        ▼                          │
│  task ────► workflow ──► heuristic                           │
│    │          │                                              │
│    ▼          ▼                                              │
│  artifact  orchestrator ──► operator                         │
│    │          │               │                              │
│    ▼          ▼               ▼                              │
│  llm ──────► reporting ─────► pr-loop                        │
└─────────────────────────────────────────────────────────────┘

Paid Components (Commercial License)
┌─────────────────────────────────────────────────────────────┐
│ repo-dag ──► pr-train ──► fleet-advanced                    │
│    │            │             │                              │
│    ▼            ▼             ▼                              │
│ policy-distribution ──► eval-harness                         │
│    │                         │                               │
│    ▼                         ▼                               │
│ authz ──────────────────► telemetry                          │
│    │                         │                               │
│    ▼                         ▼                               │
│ meta-loop ──────────────► operators-console                  │
└─────────────────────────────────────────────────────────────┘
```

---

## Next Steps

1. **Review and approve this plan**
2. **Make technical decisions** (see Open Questions)
3. **Recruit design partners** (start now for Month 10 target)
4. **Start Phase 2A** - Tester and Reviewer agents
5. **Set up dogfooding** - Use miniforge to build miniforge
6. **Prepare for OSS launch** - GitHub org, website, documentation

---

**Let's build the future of platform engineering! 🚀**
