# miniforge.ai: Implementation Build Plan

**Date:** 2026-01-22
**Goal:** Ship OSS Beta with AI features in 7 weeks
**Status:** Ready to build

---

## Executive Summary

### What We're Building

**AI-powered PR orchestration for platform teams managing multi-repo infrastructure changes**

**Core Features:**

1. ✅ Repo DAG with topological merge ordering (DONE)
2. ✅ PR Train with evidence bundles (DONE)
3. ✅ Policy packs with Dewey organization (DONE)
4. 🔨 CLI TUI (K9s-inspired) - TO BUILD
5. 🔨 Web Dashboard (Linear-inspired) - TO BUILD
6. 🔨 AI summaries & chat - TO BUILD
7. 🔨 Batch AI gate operations - TO BUILD

### Timeline

**7 weeks to OSS Beta** (mid-March 2026)

- Week 1-2: Integration & Core CLI
- Week 3-4: AI Features (summaries, chat, gates)
- Week 5: Web Dashboard MVP
- Week 6: Policy Packs & Documentation
- Week 7: OSS Packaging & Beta Launch

### Resource Model

- **You + Claude Max** building at 2-3K LOC/day sustained
- **Dogfooding** - Use miniforge to build miniforge from Week 3 onward
- **Design partners** - Identify 3-5 early adopters during build

---

## Week 1-2: Integration & Core CLI (Jan 22 - Feb 5)

### Goal

All components working together, basic CLI operational

### Component Integration Tasks

#### Day 1-2: End-to-End Integration Tests

**Build the glue between existing components**

```clojure
;; Integration test: Full train flow
;; tests/integration/train_flow_test.clj

(deftest full-train-execution
  (testing "Create train, add PRs, validate, merge"
    (let [dag-mgr (dag/create-manager)
          dag (create-test-dag dag-mgr)
          train-mgr (train/create-manager)
          train (train/create-train train-mgr "test-train" (:dag/id dag) "Test")

          ;; Add PRs to train
          _ (train/add-pr train-mgr train "acme/infra" 123
                          "https://..." "feat/test" "Test PR")
          _ (train/add-pr train-mgr train "acme/k8s" 456
                          "https://..." "feat/test" "Test PR 2")

          ;; Link PRs based on DAG
          train (train/link-prs train-mgr train)

          ;; Run policy checks
          policy-reg (policy/create-registry)
          terraform-pack (load-terraform-pack)
          _ (policy/register-pack policy-reg terraform-pack)

          ;; Check first PR
          pr1 (train/get-pr-from-train train 123)
          artifact {:artifact/type :terraform-plan
                    :artifact/content (slurp "test/fixtures/plan.txt")}
          result (policy/check-artifact terraform-pack artifact {:phase :implement})

          ;; Verify
          _ (is (:passed? result))
          _ (is (empty? (:violations result)))

          ;; Update PR status
          train (train/update-pr-status train-mgr train 123 :approved)
          train (train/update-pr-ci-status train-mgr train 123 :passed)

          ;; Check ready to merge
          ready (train/get-ready-to-merge train-mgr train)]

      (is (= [123] ready))
      (is (= :in-progress (:train/status train))))))
```

**Exit Criteria:**

- [ ] Can create DAG → create train → add PRs → link → validate → merge
- [ ] Policy packs load and evaluate artifacts
- [ ] State machine transitions work correctly
- [ ] Evidence bundles generated with all artifacts

**Deliverable:** `make integration-test` passes

#### Day 3-4: CLI Base Structure

```
bases/cli/
├── src/ai/miniforge/cli/
│   ├── main.clj              # Entry point (EXISTS, enhance)
│   ├── commands/
│   │   ├── init.clj          # miniforge init
│   │   ├── dag.clj           # DAG management
│   │   ├── train.clj         # Train operations
│   │   ├── fleet.clj         # Fleet daemon
│   │   └── policy.clj        # Policy pack management
│   ├── tui/
│   │   ├── core.clj          # TUI framework (NEW)
│   │   ├── views/
│   │   │   ├── train_list.clj
│   │   │   ├── pr_detail.clj
│   │   │   └── evidence.clj
│   │   └── components/
│   │       ├── table.clj
│   │       ├── panel.clj
│   │       └── statusline.clj
│   └── api.clj               # HTTP API client
└── test/
```

**Commands to implement:**

```bash
# Initialization
miniforge init                 # Create .miniforge/ config
miniforge dag create <name>    # Create DAG
miniforge dag add-repo <url>   # Add repo to DAG
miniforge dag add-edge <from> <to> <constraint>

# Train operations
miniforge train create <spec-file>    # Create train from spec
miniforge train status <train-id>     # Show train status
miniforge train list                  # List all trains

# Fleet operations
miniforge fleet watch                 # Start fleet daemon
miniforge fleet status                # Show fleet status
miniforge fleet dashboard             # Interactive TUI (Week 2)

# Policy operations
miniforge policy install <pack-path>  # Install policy pack
miniforge policy list                 # List installed packs
miniforge policy check <artifact>     # Check artifact against policies
```

**Implementation approach:**

- Use babashka.cli for argument parsing (already in main.clj)
- Keep CLI commands simple - delegate to component interfaces
- Use bblgum (gum wrapper) for interactive prompts

**Example: `miniforge dag create`**

```clojure
;; bases/cli/src/ai/miniforge/cli/commands/dag.clj

(ns ai.miniforge.cli.commands.dag
  (:require
   [ai.miniforge.repo-dag.interface :as dag]
   [babashka.fs :as fs]
   [clojure.edn :as edn]))

(defn create-dag [opts]
  (let [name (:name opts)
        description (:description opts)
        mgr (dag/create-manager)
        dag (dag/create-dag mgr name description)

        ;; Save to .miniforge/dag.edn
        dag-file ".miniforge/dag.edn"]

    (fs/create-dirs ".miniforge")
    (spit dag-file (pr-str dag))

    (println (str "✓ Created DAG: " name))
    (println (str "  ID: " (:dag/id dag)))
    (println (str "  Config: " dag-file))

    ;; Return dag for scripting
    dag))

(defn add-repo [opts]
  (let [url (:url opts)
        dag-file ".miniforge/dag.edn"
        dag (edn/read-string (slurp dag-file))
        mgr (dag/create-manager)

        ;; Parse repo info from URL
        repo-name (last (clojure.string/split url #"/"))
        repo-type (or (:type opts) :application)

        ;; Add to DAG
        updated (dag/add-repo mgr (:dag/id dag)
                              {:repo/url url
                               :repo/name repo-name
                               :repo/type repo-type})]

    (spit dag-file (pr-str updated))
    (println (str "✓ Added repo: " repo-name " (" repo-type ")"))))
```

**Exit Criteria:**

- [ ] All basic commands work
- [ ] DAG can be created and modified via CLI
- [ ] Train can be created from spec file
- [ ] Config persists to .miniforge/

**Deliverable:** Working CLI commands (non-interactive)

#### Day 5-7: CLI TUI (Interactive Dashboard)

**Library decision:** Use Lanterna (JVM/Clojure) or Bubble Tea (Go)?

**Recommendation: Start with Lanterna**

- Same language as components
- Can import components directly
- Faster to prototype
- Can rewrite in Go later if needed

```clojure
;; bases/cli/src/ai/miniforge/cli/tui/core.clj

(ns ai.miniforge.cli.tui.core
  (:require
   [lanterna.terminal :as terminal]
   [lanterna.screen :as screen]
   [ai.miniforge.cli.tui.views.train-list :as train-list])
  (:import
   [com.googlecode.lanterna TerminalSize TextColor]
   [com.googlecode.lanterna.input KeyType]))

(defn create-tui [state-atom]
  (let [term (terminal/get-terminal :auto)
        scr (screen/get-screen term)]

    {:terminal term
     :screen scr
     :state state-atom
     :current-view :train-list}))

(defn render-view [tui]
  (let [view (:current-view tui)]
    (case view
      :train-list (train-list/render tui)
      :pr-detail (pr-detail/render tui)
      :evidence (evidence/render tui))))

(defn handle-input [tui key]
  (case (:key-type key)
    KeyType/Character
    (case (.getCharacter key)
      \q :quit
      \j (update tui :selected-row inc)
      \k (update tui :selected-row dec)
      \Enter (assoc tui :current-view :pr-detail)
      tui)

    KeyType/Escape
    (assoc tui :current-view :train-list)

    tui))

(defn main-loop [tui]
  (loop [current-tui tui]
    (render-view current-tui)
    (screen/refresh (:screen current-tui))

    (let [key (screen/get-key-blocking (:screen current-tui))
          next-tui (handle-input current-tui key)]

      (if (= next-tui :quit)
        :done
        (recur next-tui)))))
```

**Week 2 Focus: Train List View**

```
╭─────────────────────────────────────────────────────────────────────────────╮
│ miniforge fleet  [Trains: 5 | PRs: 23 | Blocked: 3 | Ready: 7]   ⟳ 15s ago │
├─────────────────────────────────────────────────────────────────────────────┤
│ TRAIN                    STATUS      PRs  PROGRESS        BLOCKING  AGE     │
├─────────────────────────────────────────────────────────────────────────────┤
│ ● add-auth              in-progress  5/5  ██████▓▓▓▓ 60%  CI #123  2h      │
│ ● rds-import            ready        3/3  ██████████ 100% -        4h      │
│ ● k8s-migration         blocked      7/7  ███▓▓▓▓▓▓▓ 30%  Deps     1d      │
```

**Implementation:**

```clojure
;; bases/cli/src/ai/miniforge/cli/tui/views/train_list.clj

(ns ai.miniforge.cli.tui.views.train-list
  (:require
   [lanterna.screen :as screen]
   [ai.miniforge.pr-train.interface :as train]))

(defn render-progress-bar [percent width]
  (let [filled (int (* width (/ percent 100)))
        empty (- width filled)]
    (str (apply str (repeat filled "█"))
         (apply str (repeat empty "▓")))))

(defn render-train-row [scr row train y]
  (let [status-color (case (:train/status train)
                       :in-progress :green
                       :ready :green
                       :blocked :red
                       :completed :gray
                       :white)
        progress (:progress train 0)
        blocking (or (:blocking-reason train) "-")]

    ;; Render row
    (screen/put-string scr 0 y
                       (format "%-25s %-12s %2d/%2d  %s %3d%%  %-8s %s"
                               (:train/name train)
                               (:train/status train)
                               (:merged-count train 0)
                               (:total-prs train 0)
                               (render-progress-bar progress 10)
                               progress
                               blocking
                               (:age train ""))
                       {:fg-color status-color})))

(defn render [tui]
  (let [scr (:screen tui)
        state @(:state tui)
        trains (:trains state)
        selected (:selected-row tui 0)]

    ;; Header
    (screen/put-string scr 0 0 "miniforge fleet" {:fg-color :cyan :style :bold})
    (screen/put-string scr 0 1 (repeat 80 "─"))

    ;; Column headers
    (screen/put-string scr 0 2
                       "TRAIN                    STATUS      PRs  PROGRESS        BLOCKING  AGE")
    (screen/put-string scr 0 3 (repeat 80 "─"))

    ;; Train rows
    (doseq [[idx train] (map-indexed vector trains)]
      (let [highlight? (= idx selected)]
        (render-train-row scr train (+ 4 idx) highlight?)))

    ;; Footer
    (screen/put-string scr 0 (- (screen/get-size scr :rows) 1)
                       "[j/k] Navigate  [Enter] Drill down  [q] Quit")))
```

**Exit Criteria:**

- [ ] TUI shows train list with real data
- [ ] Keyboard navigation works (j/k, Enter, Esc, q)
- [ ] Auto-refresh every 15s
- [ ] Colors and progress bars display correctly

**Deliverable:** `miniforge fleet dashboard` launches working TUI

#### Day 8-10: API Server (for TUI/Web)

**Why needed:** TUI and Web both need same data

```
bases/api-server/
├── src/ai/miniforge/api/
│   ├── main.clj           # HTTP server (http-kit or ring)
│   ├── routes.clj         # REST endpoints
│   ├── handlers/
│   │   ├── trains.clj
│   │   ├── prs.clj
│   │   ├── dag.clj
│   │   └── policy.clj
│   └── websocket.clj      # Real-time updates
└── test/
```

**Endpoints:**

```clojure
;; bases/api-server/src/ai/miniforge/api/routes.clj

(ns ai.miniforge.api.routes
  (:require
   [reitit.ring :as ring]
   [ai.miniforge.api.handlers.trains :as trains]
   [ai.miniforge.api.handlers.prs :as prs]))

(def routes
  [["/api"
    ["/trains"
     ["" {:get trains/list-trains
          :post trains/create-train}]
     ["/:id" {:get trains/get-train
              :put trains/update-train}]
     ["/:id/prs" {:get trains/list-prs}]
     ["/:id/status" {:get trains/get-status}]]

    ["/prs"
     ["" {:get prs/list-prs}]
     ["/:number" {:get prs/get-pr}]
     ["/:number/approve" {:post prs/approve}]
     ["/:number/merge" {:post prs/merge}]]

    ["/dag"
     ["/:id" {:get dag/get-dag}]
     ["/:id/topo-order" {:get dag/get-topo-order}]]

    ["/policy"
     ["/packs" {:get policy/list-packs}]
     ["/check" {:post policy/check-artifact}]]]])

(def app
  (ring/ring-handler
   (ring/router routes)
   (ring/create-default-handler)))
```

**Example handler:**

```clojure
;; bases/api-server/src/ai/miniforge/api/handlers/trains.clj

(ns ai.miniforge.api.handlers.trains
  (:require
   [ai.miniforge.pr-train.interface :as train]
   [cheshire.core :as json]))

(def train-manager (train/create-manager))

(defn list-trains [request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:trains (train/list-trains train-manager)})})

(defn get-train [request]
  (let [train-id (-> request :path-params :id parse-uuid)
        train (train/get-train train-manager train-id)]

    (if train
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string train)}
      {:status 404
       :body "Train not found"})))

(defn get-status [request]
  (let [train-id (-> request :path-params :id parse-uuid)
        train (train/get-train train-manager train-id)]

    (when train
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:status (:train/status train)
               :progress (train/get-progress train-manager train-id)
               :ready-to-merge (train/get-ready-to-merge train-manager train-id)
               :blocking (train/get-blocking train-manager train-id)})})))
```

**Exit Criteria:**

- [ ] REST API serves train/PR data
- [ ] TUI can fetch from API instead of direct component calls
- [ ] WebSocket for real-time updates (optional for Week 1)

**Deliverable:** API server running on localhost:3000

### Week 1-2 Deliverables

**Working System:**

- ✅ All components integrated
- ✅ CLI commands operational
- ✅ TUI dashboard showing live trains
- ✅ API server for data access

**Demo:**

```bash
# Create DAG
miniforge dag create kiddom-infra "Kiddom infrastructure repos"
miniforge dag add-repo https://github.com/kiddom/terraform-modules
miniforge dag add-repo https://github.com/kiddom/terraform
miniforge dag add-edge terraform-modules terraform :module-before-live :sequential

# Create train
miniforge train create rds-import-spec.edn

# Watch in TUI
miniforge fleet dashboard
# Shows train, PRs, status, progress bars
```

---

## Week 3-4: AI Features (Feb 5 - Feb 19)

### Goal

AI summaries, chat, and batch gate operations working

### Component Architecture

```
components/ai-analysis/
├── src/ai/miniforge/ai_analysis/
│   ├── interface.clj         # Public API
│   ├── core.clj              # Analysis orchestration
│   ├── summarizer.clj        # PR summarization
│   ├── chat.clj              # Chat sessions
│   ├── gates.clj             # AI-powered gates
│   ├── cache.clj             # Result caching
│   └── prompts.clj           # Prompt templates
└── test/
```

### Day 1-3: AI Summarizer

**Schema:**

```clojure
;; components/ai-analysis/src/ai/miniforge/ai_analysis/schema.clj

{:ai-summary/pr-number 234
 :ai-summary/created-at inst
 :ai-summary/expires-at inst  ; 1 hour cache

 ;; Levels
 :ai-summary/one-liner "Imports existing RDS, no infra changes, low risk ✓"
 :ai-summary/executive "Risk: LOW\nType: Infrastructure - State Import\n..."
 :ai-summary/deep-analysis "..."

 ;; Risk assessment
 :ai-summary/risk-level :low  ; :low, :medium, :high
 :ai-summary/risk-factors []

 ;; Metadata
 :ai-summary/model-used "claude-sonnet-4"
 :ai-summary/tokens-used 1200
 :ai-summary/generation-time-ms 850}
```

**Implementation:**

```clojure
;; components/ai-analysis/src/ai/miniforge/ai_analysis/summarizer.clj

(ns ai.miniforge.ai-analysis.summarizer
  (:require
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.ai-analysis.prompts :as prompts]
   [ai.miniforge.ai-analysis.cache :as cache]))

(defn generate-one-liner
  "Generate one-line summary (<80 chars)"
  [pr evidence-bundle]
  (let [prompt (prompts/one-liner-prompt pr evidence-bundle)
        llm-client (llm/create-client :claude-cli {:model "claude-haiku-4"})
        response (llm/generate llm-client prompt)
        summary (clojure.string/trim (:content response))]

    {:one-liner summary
     :tokens-used (get-in response [:usage :total-tokens])
     :model "claude-haiku-4"}))

(defn generate-executive-summary
  "Generate 4-paragraph executive summary"
  [pr evidence-bundle]
  (let [prompt (prompts/executive-summary-prompt pr evidence-bundle)
        llm-client (llm/create-client :claude-cli {:model "claude-sonnet-4"})
        response (llm/generate llm-client prompt)]

    {:executive-summary (:content response)
     :tokens-used (get-in response [:usage :total-tokens])
     :model "claude-sonnet-4"}))

(defn generate-deep-analysis
  "Generate full deep analysis"
  [pr evidence-bundle]
  (let [prompt (prompts/deep-analysis-prompt pr evidence-bundle)
        llm-client (llm/create-client :claude-cli {:model "claude-sonnet-4"})
        response (llm/generate llm-client prompt)]

    {:deep-analysis (:content response)
     :tokens-used (get-in response [:usage :total-tokens])
     :model "claude-sonnet-4"}))

(defn analyze-pr
  "Generate all summary levels for a PR"
  [pr evidence-bundle]
  (let [cache-key [:ai-summary (:pr/number pr)]
        cached (cache/get cache-key)]

    (if cached
      cached
      (let [one-liner (generate-one-liner pr evidence-bundle)
            exec (generate-executive-summary pr evidence-bundle)

            summary {:ai-summary/pr-number (:pr/number pr)
                     :ai-summary/created-at (java.time.Instant/now)
                     :ai-summary/expires-at (+ (java.time.Instant/now) 3600000)
                     :ai-summary/one-liner (:one-liner one-liner)
                     :ai-summary/executive (:executive-summary exec)
                     :ai-summary/risk-level (extract-risk-level exec)
                     :ai-summary/tokens-used (+ (:tokens-used one-liner)
                                               (:tokens-used exec))}]

        (cache/put cache-key summary 3600)  ; 1 hour TTL
        summary))))
```

**Prompts:**

```clojure
;; components/ai-analysis/src/ai/miniforge/ai_analysis/prompts.clj

(defn one-liner-prompt [pr evidence-bundle]
  (str "Summarize this PR in one sentence (max 80 chars):\n\n"
       "Intent: " (get-in evidence-bundle [:intent/type]) "\n"
       "Changes: +" (get pr :additions 0) " -" (get pr :deletions 0) "\n"
       "Policy: " (count (get-in evidence-bundle [:policy-results :violations] [])) " violations\n"
       "CI: " (:ci-status pr) "\n\n"
       "Focus on risk and action needed. Format: '<What> <Impact> <Risk>'\n"
       "Examples:\n"
       "- 'Imports existing RDS, no infra changes, low risk ✓'\n"
       "- 'Destroys NAT gateway, will cause outage, high risk ⚠'\n"
       "- 'Updates IAM policy, adds S3 permissions, review needed'"))

(defn executive-summary-prompt [pr evidence-bundle]
  (str "Generate a 4-paragraph executive summary for this PR:\n\n"
       "Context:\n"
       "- Train: " (get pr :train-name) "\n"
       "- Intent: " (get-in evidence-bundle [:intent/description]) "\n"
       "- Repo: " (:repo pr) " (" (get-in evidence-bundle [:repo/type]) ")\n\n"
       "Technical Details:\n"
       "- Files changed: " (pr-str (get pr :files [])) "\n"
       "- Diff summary: +" (get pr :additions 0) " -" (get pr :deletions 0) "\n"
       "- Terraform plan: " (get-in evidence-bundle [:artifacts :terraform-plan :summary]) "\n"
       "- Policy violations: " (get-in evidence-bundle [:policy-results :violations]) "\n"
       "- CI status: " (:ci-status pr) "\n\n"
       "Answer these questions:\n"
       "1. What is the risk level? (LOW/MEDIUM/HIGH)\n"
       "2. What type of change is this?\n"
       "3. Does the implementation match the stated intent?\n"
       "4. Are there any concerns or blockers?\n"
       "5. Is it safe to merge?\n\n"
       "Format as 4 short paragraphs (2-3 sentences each)."))
```

**Exit Criteria:**

- [ ] One-liners generated in <5s (Haiku)
- [ ] Executive summaries generated in <10s (Sonnet)
- [ ] Results cached for 1 hour
- [ ] Cache invalidates on PR updates

**Deliverable:** `(ai-analysis/analyze-pr pr evidence)` returns summaries

### Day 4-6: AI Chat

```clojure
;; components/ai-analysis/src/ai/miniforge/ai_analysis/chat.clj

(ns ai.miniforge.ai-analysis.chat
  (:require
   [ai.miniforge.llm.interface :as llm]))

(defn create-session
  "Create a chat session with context"
  [context-type context-data]
  {:chat-session/id (random-uuid)
   :chat-session/type context-type  ; :pr or :batch
   :chat-session/context context-data
   :chat-session/messages []
   :chat-session/created-at (java.time.Instant/now)})

(defn add-message
  "Add a message to chat session"
  [session role content]
  (update session :chat-session/messages conj
          {:role role
           :content content
           :timestamp (java.time.Instant/now)}))

(defn generate-system-prompt
  "Generate system prompt with full context"
  [session]
  (let [ctx (:chat-session/context session)]
    (str "You are miniforge AI, an expert at reviewing infrastructure changes.\n\n"
         "Current Context:\n"
         (case (:chat-session/type session)
           :pr (str "- Reviewing PR #" (:pr/number ctx) " in the " (:train/name ctx) " train\n"
                    "- User is a " (:user/role ctx) " with " (pr-str (:user/permissions ctx)) "\n"
                    "- PR is " (:pr/status ctx) ", CI is " (:pr/ci-status ctx) "\n\n"
                    "Evidence Bundle:\n"
                    (pr-str (:evidence/bundle ctx)))

           :batch (str "- Reviewing " (count (:prs ctx)) " PRs\n"
                       "- Train: " (:train/name ctx) "\n"
                       "- User permissions: " (pr-str (:user/permissions ctx)) "\n\n"))
         "\n\nYour role:\n"
         "1. Answer questions clearly and concisely\n"
         "2. Highlight risks and concerns\n"
         "3. Reference specific evidence\n"
         "4. Recommend actions\n"
         "5. Offer to take actions (if user approves)\n\n"
         "Be direct. If asked 'is this safe?', give yes/no first, then justify.")))

(defn send-message
  "Send user message and get AI response"
  [session user-message]
  (let [system-prompt (generate-system-prompt session)
        messages (conj (:chat-session/messages session)
                       {:role :user :content user-message})

        llm-messages (cons {:role :system :content system-prompt}
                           messages)

        llm-client (llm/create-client :claude-cli {:model "claude-sonnet-4"})
        response (llm/chat llm-client llm-messages)

        ;; Extract action buttons from response
        actions (extract-action-buttons (:content response))]

    (-> session
        (add-message :user user-message)
        (add-message :assistant (:content response))
        (assoc :available-actions actions))))

(defn extract-action-buttons
  "Parse [Action] buttons from AI response"
  [response-text]
  ;; Look for [Action] pattern
  (let [pattern #"\[([^\]]+)\]"
        matches (re-seq pattern response-text)]
    (map (fn [[_ action]]
           {:label action
            :type (parse-action-type action)})
         matches)))
```

**Chat API:**

```clojure
;; Usage in CLI/Web

;; Create session for single PR
(def session (chat/create-session :pr
                {:pr/number 234
                 :pr/data {...}
                 :evidence/bundle {...}
                 :train/name "rds-import"
                 :user/role :senior-engineer
                 :user/permissions [:approve :merge]}))

;; Send message
(def updated (chat/send-message session "Is this safe to merge?"))

;; Get response
(println (-> updated :chat-session/messages last :content))
;; => "Yes, this is safe to merge. Here's why:
;;     1. **No infrastructure changes** - This is a state-only import...
;;     [Approve] [Merge]"

;; Extract actions
(:available-actions updated)
;; => [{:label "Approve" :type :approve}
;;     {:label "Merge" :type :merge}]
```

**Exit Criteria:**

- [ ] Can create chat session with PR context
- [ ] AI responds with context-aware answers
- [ ] Action buttons extracted from responses
- [ ] Batch chat works (multiple PRs)

**Deliverable:** Chat interface in both CLI and Web

### Day 7-10: Batch AI Gates

```clojure
;; components/ai-analysis/src/ai/miniforge/ai_analysis/gates.clj

(ns ai.miniforge.ai-analysis.gates
  (:require
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.policy.interface :as policy]))

(defn terraform-plan-safety-batch
  "Analyze multiple Terraform plans for dangerous changes"
  [prs-with-evidence]

  ;; Parallel analysis with Haiku (fast)
  (let [individual-analyses
        (pmap (fn [pr-data]
                (let [plan (:terraform-plan pr-data)
                      prompt (terraform-safety-prompt plan)
                      llm (llm/create-client :claude-cli {:model "claude-haiku-4"})
                      response (llm/generate llm prompt)]

                  {:pr-number (:pr/number pr-data)
                   :analysis (:content response)
                   :risk (extract-risk-level response)}))
              prs-with-evidence)

        ;; Aggregate with Sonnet (quality)
        aggregate-prompt (aggregate-analysis-prompt individual-analyses)
        llm (llm/create-client :claude-cli {:model "claude-sonnet-4"})
        aggregate (llm/generate llm aggregate-prompt)]

    {:individual individual-analyses
     :aggregate (:content aggregate)
     :safe-prs (filter #(= :low (:risk %)) individual-analyses)
     :review-prs (filter #(= :medium (:risk %)) individual-analyses)
     :blocked-prs (filter #(= :high (:risk %)) individual-analyses)}))

(defn terraform-safety-prompt [plan-text]
  (str "Analyze this Terraform plan for dangerous changes.\n\n"
       "Plan:\n```\n" plan-text "\n```\n\n"
       "Check for:\n"
       "- Network resource recreations (-/+ on aws_route, aws_route_table_association, etc.)\n"
       "- Resource deletions\n"
       "- Forced new resources\n"
       "- Security group/IAM changes\n"
       "- Database changes\n\n"
       "Respond with:\n"
       "Risk: LOW|MEDIUM|HIGH\n"
       "Finding: <brief description>\n"
       "Recommendation: <what to do>"))

(defn aggregate-analysis-prompt [individual-analyses]
  (str "Aggregate these Terraform plan analyses:\n\n"
       (pr-str individual-analyses) "\n\n"
       "Provide:\n"
       "1. Summary by risk level (count per level)\n"
       "2. Specific concerns for medium/high risk PRs\n"
       "3. Recommendation (batch approve vs individual review)\n\n"
       "Format for display in a table."))
```

**CLI Integration:**

```clojure
;; In TUI, user selects 20 PRs and presses 'G' (run gate)

(defn handle-run-gate [tui]
  (let [selected-prs (get-selected-prs tui)

        ;; Show gate selection menu
        gate-type (show-gate-menu)  ; Returns :terraform-safety, :k8s-safety, etc.

        ;; Run gate
        results (case gate-type
                  :terraform-safety
                  (ai-gates/terraform-plan-safety-batch selected-prs))]

    ;; Display results table
    (show-gate-results tui results)))
```

**Exit Criteria:**

- [ ] Can analyze 20 PRs in <15s
- [ ] Results categorized by risk
- [ ] Specific findings for each flagged PR
- [ ] Batch approve option for safe PRs

**Deliverable:** Batch gate operations in CLI

### Week 3-4 Deliverables

**AI-Powered Features:**

- ✅ One-liner summaries on every PR (<5s)
- ✅ Executive summaries on expand (<10s)
- ✅ AI chat for single/batch PRs
- ✅ Batch Terraform plan safety review
- ✅ All results cached (1-hour TTL)

**Demo:**

```bash
# In TUI
miniforge fleet dashboard
# Select PR #234
# See: "🤖 AI: Imports existing RDS, no infra changes, low risk ✓"
# Press 'c' for chat
# Ask: "Is this safe to merge?"
# AI responds with full context + [Approve] [Merge] buttons

# Batch operation
# Select 20 Terraform PRs
# Press 'G' → "Terraform Plan Safety"
# AI analyzes all 20 in 12 seconds
# Shows: 15 safe, 4 review needed, 1 blocked
# Press 'A' → Approves 15 safe PRs
```

---

## Week 5: Web Dashboard MVP (Feb 19 - Feb 26)

### Goal

Basic web UI with train list, PR detail, and AI features

### Stack

```
bases/web-dashboard/
├── src/miniforge/
│   ├── core.cljs              # Entry point
│   ├── events.cljs            # Re-frame events
│   ├── subs.cljs              # Re-frame subscriptions
│   ├── db.cljs                # App state
│   ├── views/
│   │   ├── fleet.cljs         # Main fleet view
│   │   ├── pr_modal.cljs      # PR detail modal
│   │   ├── chat.cljs          # AI chat panel
│   │   └── command.cljs       # Cmd+K palette
│   ├── components/
│   │   ├── table.cljs
│   │   ├── badge.cljs
│   │   ├── progress.cljs
│   │   └── ai_summary.cljs
│   └── api.cljs               # API client
└── resources/public/
    ├── index.html
    └── css/styles.css
```

**Day 1-2: Re-frame Setup**

```clojure
;; bases/web-dashboard/src/miniforge/db.cljs

(ns miniforge.db)

(def default-db
  {:trains []
   :selected-train nil
   :selected-prs #{}
   :filters {:status :all
             :repo :all}
   :ai-chat {:open? false
             :session nil}
   :loading? false})
```

```clojure
;; bases/web-dashboard/src/miniforge/events.cljs

(ns miniforge.events
  (:require
   [re-frame.core :as rf]
   [miniforge.api :as api]))

(rf/reg-event-db
 ::initialize
 (fn [_ _]
   default-db))

(rf/reg-event-fx
 ::load-trains
 (fn [{:keys [db]} _]
   {:db (assoc db :loading? true)
    :http-xhrio {:method :get
                 :uri "/api/trains"
                 :response-format (ajax/json-response-format)
                 :on-success [::trains-loaded]
                 :on-failure [::load-failed]}}))

(rf/reg-event-db
 ::trains-loaded
 (fn [db [_ trains]]
   (-> db
       (assoc :trains trains)
       (assoc :loading? false))))

(rf/reg-event-db
 ::select-pr
 (fn [db [_ pr-number]]
   (update db :selected-prs conj pr-number)))

(rf/reg-event-fx
 ::approve-selected
 (fn [{:keys [db]} _]
   (let [selected (:selected-prs db)]
     {:db db
      :dispatch-n (map (fn [pr] [::approve-pr pr]) selected)})))
```

**Day 3-5: Fleet View**

```clojure
;; bases/web-dashboard/src/miniforge/views/fleet.cljs

(ns miniforge.views.fleet
  (:require
   [re-frame.core :as rf]
   [miniforge.components.table :as table]
   [miniforge.components.ai-summary :as ai-summary]))

(defn train-row [train selected?]
  [:tr {:class (when selected? "selected")
        :on-click #(rf/dispatch [::events/select-train (:train/id train)])}
   [:td [:span.status-dot {:class (name (:train/status train))}]
    (:train/name train)]
   [:td [:span.badge (:train/status train)]]
   [:td (str (:merged-count train) "/" (:total-prs train))]
   [:td [progress-bar (:progress train 0)]]
   [:td (:blocking-reason train "-")]
   [:td (:age train)]])

(defn fleet-view []
  (let [trains @(rf/subscribe [::subs/trains])
        selected-train @(rf/subscribe [::subs/selected-train])]
    [:div.fleet-view
     [:div.header
      [:h1 "miniforge"]
      [:div.filters
       [filter-dropdown :status]
       [filter-dropdown :repo]
       [:input.search {:placeholder "Search..."}]]]

     [:div.quick-actions
      [:button {:on-click #(rf/dispatch [::events/refresh])} "↻ Refresh"]
      [:button {:on-click #(rf/dispatch [::events/approve-selected])} "✓ Approve Selected"]
      [:button {:on-click #(rf/dispatch [::events/merge-ready])} "→ Merge Ready"]]

     [:div.trains-list
      [:div.section
       [:h2 "🔴 BLOCKED (" (count-blocked trains) " trains)"]
       [:table
        [:thead
         [:tr [:th "Train"] [:th "Status"] [:th "PRs"] [:th "Progress"] [:th "Blocking"] [:th "Age"]]]
        [:tbody
         (for [train (filter-blocked trains)]
           ^{:key (:train/id train)}
           [train-row train (= train selected-train)])]]]

      [:div.section
       [:h2 "🟢 READY TO MERGE (" (count-ready trains) " PRs)"]
       ;; Similar structure]]]))
```

**Day 6-7: PR Modal with AI**

```clojure
;; bases/web-dashboard/src/miniforge/views/pr_modal.cljs

(ns miniforge.views.pr-modal
  (:require
   [re-frame.core :as rf]
   [miniforge.components.ai-summary :as ai]))

(defn pr-modal [pr]
  [:div.modal-overlay {:on-click #(rf/dispatch [::events/close-modal])}
   [:div.modal.pr-detail {:on-click #(.stopPropagation %)}
    [:div.modal-header
     [:h2 "PR #" (:pr/number pr) ": " (:pr/title pr)]
     [:button.close {:on-click #(rf/dispatch [::events/close-modal])} "✕"]]

    [:div.modal-body
     ;; AI Summary
     [ai/summary-panel pr]

     ;; Status grid
     [:div.status-grid
      [:div.status-item
       [:h3 "Status"]
       [:span.badge (:pr/status pr)]]
      [:div.status-item
       [:h3 "CI"]
       [:span.ci-badges
        (for [check (:ci-checks pr)]
          ^{:key (:name check)}
          [:span.ci-badge {:class (status-class check)} (:name check)])]]
      [:div.status-item
       [:h3 "Approvals"]
       [:span (str (:approvals-count pr) "/" (:approvals-required pr))]]]

     ;; Evidence Bundle
     [:div.evidence-section
      [:h3 "Evidence Bundle" [:a {:href "#"} "View Full →"]]
      [:div.evidence-summary
       [:p "Intent: " (get-in pr [:evidence :intent/description])]
       [:ul
        [:li "✓ No resource creation (state-only)"]
        [:li "✓ No resource destruction"]
        [:li "✓ Import target exists in AWS"]]]]

     ;; AI Chat
     [ai/chat-panel pr]]

    [:div.modal-footer
     [:button.primary {:on-click #(rf/dispatch [::events/merge-pr (:pr/number pr)])} "→ Merge Now"]
     [:button {:on-click #(rf/dispatch [::events/approve-pr (:pr/number pr)])} "✓ Approve"]
     [:button {:on-click #(rf/dispatch [::events/view-github pr])} "🔗 View on GitHub"]]]])
```

**AI Summary Component:**

```clojure
;; bases/web-dashboard/src/miniforge/components/ai_summary.cljs

(ns miniforge.components.ai-summary
  (:require
   [re-frame.core :as rf]))

(defn summary-panel [pr]
  (let [summary @(rf/subscribe [::subs/ai-summary (:pr/number pr)])]
    [:div.ai-summary
     [:div.one-liner
      [:span.ai-badge "🤖 AI"]
      [:span.summary-text (:ai-summary/one-liner summary)]]

     [:details
      [:summary "Show AI Analysis"]
      [:div.executive-summary
       [:pre (:ai-summary/executive summary)]]]]))

(defn chat-panel [pr]
  (let [chat-session @(rf/subscribe [::subs/chat-session])
        messages (:chat-session/messages chat-session)]
    [:div.ai-chat
     [:h3 "Chat with miniforge AI"]

     [:div.chat-messages
      (for [msg messages]
        ^{:key (:timestamp msg)}
        [:div.message {:class (name (:role msg))}
         [:div.content (:content msg)]])]

     [:div.chat-input
      [:textarea {:placeholder "Ask a question..."
                  :on-key-down #(when (and (= (.-key %) "Enter")
                                           (.-metaKey %))
                                  (rf/dispatch [::events/send-chat-message
                                               (.. % -target -value)]))}]
      [:button "Send"]]]))
```

**Exit Criteria:**

- [ ] Can view train list
- [ ] Can click PR to open modal
- [ ] AI one-liner visible inline
- [ ] AI executive summary in modal
- [ ] Chat panel works
- [ ] Action buttons execute

**Deliverable:** Web dashboard at localhost:8080

---

## Week 6: Policy Packs & Docs (Feb 26 - Mar 5)

### Goal

Built-in policy packs + complete documentation

**See OSS_PAID_ROADMAP.md for detailed policy pack specs**

**Built-in packs to create:**

1. `terraform-aws` - Based on cursor-rules/31-terraform-plan.mdc
2. `kubernetes` - K8s manifest safety
3. `foundations` - General code quality (no TODOs, no secrets)

**Documentation:**

1. README.md
2. GETTING_STARTED.md
3. WORKFLOW_EXAMPLES.md (5 examples)
4. POLICY_PACK_AUTHORING.md
5. SCANNER_DEVELOPMENT.md

---

## Week 7: OSS Packaging & Launch (Mar 5 - Mar 12)

### Goal

Installable OSS release

**Tasks:**

1. Polylith OSS project configuration
2. Babashka uberscript build
3. Homebrew formula
4. Docker image
5. CI/CD (GitHub Actions)
6. Beta user onboarding

---

## Implementation Notes

### Dogfooding Strategy

**Starting Week 3:** Use miniforge to build miniforge

```bash
# Create miniforge-dev DAG
miniforge dag create miniforge-dev "miniforge development repos"
miniforge dag add-repo https://github.com/miniforge/miniforge

# Create train for each feature
miniforge train create ai-summarizer-spec.edn
miniforge train create ai-chat-spec.edn
miniforge train create web-dashboard-spec.edn

# Watch progress
miniforge fleet dashboard
```

**Benefits:**

- Immediate validation of features
- Find UX issues quickly
- Build credibility (screenshots of miniforge building itself)

### Testing Strategy

**Unit tests:** Per component (already done for existing components)

**Integration tests:** Week 1-2 focus

**E2E tests:** Week 5+ for web dashboard

**Manual testing:** Daily dogfooding

### Performance Targets

**CLI TUI:**

- Train list render: <100ms
- Auto-refresh: every 15s
- Keyboard action latency: <50ms

**AI Features:**

- One-liner generation: <5s (Haiku)
- Executive summary: <10s (Sonnet)
- Batch analysis (20 PRs): <15s
- Cache hit: <10ms

**Web Dashboard:**

- Initial load: <2s
- Train list render: <200ms
- PR modal open: <300ms
- Real-time update: <500ms

---

## Success Metrics

### Week 1-2

- [ ] All 23 components integrated
- [ ] CLI commands work end-to-end
- [ ] TUI shows live data
- [ ] API server responds

### Week 3-4

- [ ] AI summaries on 100% of PRs
- [ ] Chat works for single/batch
- [ ] Batch gates analyze 20 PRs in <15s
- [ ] Cache hit rate >80%

### Week 5

- [ ] Web dashboard accessible
- [ ] Can view/approve/merge from web
- [ ] AI features work in web
- [ ] Real-time updates via WebSocket

### Week 6

- [ ] 4 policy packs ready
- [ ] Documentation complete
- [ ] 5 example workflows

### Week 7 (OSS BETA LAUNCH)

- [ ] `brew install miniforge` works
- [ ] Docker image published
- [ ] 5+ beta users onboarded
- [ ] First GitHub stars

---

## Next Immediate Steps (This Week)

### Day 1 (Today): Integration Testing

**Focus:** Get all components talking to each other

```bash
cd /Users/chris/Local/miniforge.ai/miniforge
mkdir -p tests/integration
touch tests/integration/train_flow_test.clj

# Write first integration test
# Goal: DAG → Train → PRs → Policy Check → Merge
```

### Day 2: CLI Commands

**Focus:** Basic commands working

```bash
# Implement:
# - miniforge dag create
# - miniforge dag add-repo
# - miniforge train create
# - miniforge train status
```

### Day 3: TUI Foundation

**Focus:** Lanterna setup + train list view

```bash
# Add Lanterna dependency
# Create basic TUI skeleton
# Render train list (static data)
```

### Weekend: API Server

**Focus:** REST endpoints for TUI/Web

```bash
# HTTP server with basic routes
# /api/trains
# /api/prs
# TUI connects to API
```

---

**Ready to start building?**

I recommend starting with Day 1 integration testing - get the existing components working together, then build outward from there. Want me to help write the first integration test?
