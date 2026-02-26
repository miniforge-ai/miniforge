;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.tui-views.view.project
  "Data projection functions for the view-spec interpreter.

   Each projection takes (model) -> data suitable for a widget.
   These are the 'subscriptions' in Elm/re-frame terminology —
   pure functions that derive widget data from the model.

   Registered by keyword so screens.edn can reference them.
   Layer 1: Pure model → derived data."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.model :as model])
  (:import
   [java.text SimpleDateFormat]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- safe-format-time [ts]
  (when ts
    (try
      (.format (SimpleDateFormat. "HH:mm:ss") ts)
      (catch Exception _ ""))))

(defn- status-char [status]
  (case status
    :running "●" :success "✓" :failed "✗" :blocked "◐" "○"))

(defn- format-progress-bar [pct width]
  (let [pct (or pct 0)
        bar-w (max 1 (- width 5))
        filled (int (/ (* pct bar-w) 100))]
    (str (apply str (repeat filled \u2588))
         (apply str (repeat (- bar-w filled) \u2591))
         (format " %3d%%" pct))))

(defn- readiness-bar [score width]
  (let [pct (int (* 100 (or score 0)))
        bar-w (max 1 (- width 5))
        filled (int (/ (* pct bar-w) 100))]
    (str (apply str (repeat filled \u2588))
         (apply str (repeat (- bar-w filled) \u2591))
         (format " %3d%%" pct))))

(defn- risk-label [level]
  (case level :critical "CRIT" :high "high" :medium "med" :low "low" "?"))

;------------------------------------------------------------------------------ Layer 0b
;; Readiness + risk derivation (pure, from provider signals)

(defn derive-readiness
  "Derive N9 readiness state from provider signals.
   Returns {:readiness/state kw :readiness/score float :readiness/blockers [...]
            :readiness/factors [{:factor kw :weight float :score float} ...]}"
  [pr]
  (let [status (:pr/status pr)
        ci     (:pr/ci-status pr)
        ci-ok? (= :passed ci)
        ci-fail? (= :failed ci)
        ;; Determine readiness state
        [state score]
        (cond
          (and (#{:merge-ready :approved} status) ci-ok?)  [:merge-ready 1.0]
          (and (= :approved status) ci-fail?)               [:ci-failing 0.5]
          (and (= :approved status) (not ci-ok?))           [:needs-review 0.7]
          (= :changes-requested status)                     [:changes-requested 0.25]
          (= :reviewing status)                             [:needs-review 0.5]
          (and (= :open status) ci-fail?)                   [:ci-failing 0.25]
          (= :open status)                                  [:needs-review 0.4]
          (= :draft status)                                 [:draft 0.1]
          :else                                             [:unknown 0.0])
        ;; Compute blockers
        blockers (cond-> []
                   ci-fail?
                   (conj {:blocker/type :ci
                          :blocker/message "CI checks failing"
                          :blocker/source "provider"})
                   (#{:open :reviewing :needs-review} status)
                   (conj {:blocker/type :review
                          :blocker/message "Needs review approval"
                          :blocker/source "provider"})
                   (= :changes-requested status)
                   (conj {:blocker/type :review
                          :blocker/message "Reviewer requested changes"
                          :blocker/source "provider"})
                   (= :draft status)
                   (conj {:blocker/type :review
                          :blocker/message "PR is in draft"
                          :blocker/source "author"}))
        ;; Compute factors for detail view
        ci-score    (if ci-ok? 1.0 (if ci-fail? 0.0 0.5))
        review-score (case status
                       (:merge-ready :approved) 1.0
                       :reviewing 0.5
                       :changes-requested 0.0
                       :open 0.3
                       :draft 0.0
                       0.0)]
    {:readiness/state    state
     :readiness/score    score
     :readiness/blockers blockers
     :readiness/factors  [{:factor :ci     :weight 0.4 :score ci-score}
                          {:factor :review :weight 0.3 :score review-score}
                          {:factor :policy :weight 0.3 :score (if (= :merge-ready state) 1.0 0.5)}]}))

(defn derive-risk
  "Derive N9 risk assessment from provider signals.
   Returns {:risk/level kw :risk/factors [{:factor kw :explanation str} ...]}"
  [pr]
  (let [status (:pr/status pr)
        ci     (:pr/ci-status pr)
        ci-fail? (= :failed ci)
        changes? (= :changes-requested status)
        [level factors]
        (cond
          ci-fail?  [:medium [{:factor :ci-health
                               :explanation "CI checks are failing"}]]
          changes?  [:medium [{:factor :review-concerns
                               :explanation "Reviewer requested changes"}]]
          :else     [:low    [{:factor :signal-check
                               :explanation "All available signals nominal"}]])]
    {:risk/level   level
     :risk/factors factors}))

;------------------------------------------------------------------------------ Layer 1
;; Projection functions: model -> widget data

(defn- project-workflows
  "Project workflow list for the table widget."
  [model]
  (let [wfs (:workflows model)
        filtered (if-let [fi (:filtered-indices model)]
                   (vec (keep-indexed (fn [i wf] (when (contains? fi i) wf)) wfs))
                   wfs)]
    (mapv (fn [wf]
            {:_id (:id wf)
             :status-char (status-char (:status wf))
             :name (:name wf)
             :phase (some-> (:phase wf) name)
             :progress-str (format-progress-bar (:progress wf 0) 20)
             :agent-msg (when-let [agent (first (vals (:agents wf)))]
                          (when-let [msg (:message agent)]
                            (subs msg 0 (min 16 (count msg)))))})
          filtered)))

(defn- readiness-indicator
  "Readiness state → status string with indicator character."
  [state]
  (case state
    :merge-ready       "✓ merge-ready"
    :ci-failing        "● ci-failing"
    :needs-review      "○ needs-review"
    :changes-requested "◐ changes-req"
    :draft             "◑ draft"
    :merge-conflicts   "✗ conflicts"
    :policy-failing    "✗ policy-fail"
    :unknown           "? unknown"
    "? unknown"))

;------------------------------------------------------------------------------ Layer 0c
;; Recommendation constructors

(defn- recommend
  "Build a recommendation map. Single constructor for all recommendation types."
  [action label reason]
  {:action action :label label :reason reason})

(def ^:private labels
  "Action → label mapping."
  {:remediate "\u26a1 remediate"
   :review    "\u2299 review"
   :evaluate  "\u25c7 evaluate"
   :wait      "\u25cc wait"
   :decompose "\u25c7 decompose"
   :approve   "\u2298 approve"
   :merge     "\u2192 merge"})

(defn- recommend-action
  "Build recommendation for a known action keyword."
  [action reason]
  (recommend action (labels action) reason))

;------------------------------------------------------------------------------ Layer 0d
;; Recommendation signal extraction

(defn- extract-pr-signals
  "Extract enriched signals from a PR for recommendation.
   Returns a flat map of booleans/keywords for predicate use."
  [pr]
  (let [readiness   (or (:pr/readiness pr) (derive-readiness pr))
        risk        (or (:pr/risk pr) (derive-risk pr))
        policy      (:pr/policy pr)
        violations  (:evaluation/violations policy)
        changes     (+ (get-in pr [:change-size :additions] 0)
                       (get-in pr [:change-size :deletions] 0))]
    {:ready?          (:readiness/ready? readiness)
     :state           (or (:readiness/state readiness) :unknown)
     :risk-level      (or (:risk/level risk) :low)
     :policy-pass?    (:evaluation/passed? policy)
     :policy-unknown? (nil? policy)
     :has-violations? (boolean (seq violations))
     :auto-fixable?   (boolean (some :auto-fixable? violations))
     :large?          (> changes 500)}))

(defn derive-recommendation
  "Derive recommended next action from enriched PR data.
   Returns {:action kw :label str :reason str}.

   Ordering (policy-first to avoid unsafe merge on unknown data):
   1. Policy violations → remediate or review
   2. Policy unknown → evaluate first
   3. Not ready → wait/review
   4. Elevated risk → human approval
   5. All clear → merge"
  [pr]
  (let [{:keys [ready? state risk-level policy-pass? policy-unknown?
                has-violations? auto-fixable? large?]} (extract-pr-signals pr)]
    (cond
      (and has-violations? auto-fixable?)              (recommend-action :remediate "Auto-fixable policy violations")
      (and has-violations? (not auto-fixable?))        (recommend-action :review "Policy violations need review")
      (and (not policy-unknown?) (false? policy-pass?)) (recommend-action :review "Policy evaluation failed")
      (and policy-unknown? ready?)                     (recommend-action :evaluate "Policy not yet evaluated")
      (= :draft state)                                 (recommend-action :wait "Draft PR")
      (= :ci-failing state)                            (recommend-action :wait "CI failing")
      (= :changes-requested state)                     (recommend-action :review "Changes requested")
      (and large? (#{:needs-review :open} state))      (recommend-action :decompose "Large PR \u2014 consider splitting")
      (= :needs-review state)                          (recommend-action :review "Awaiting review")
      (and ready? (#{:medium :high :critical} risk-level)) (recommend-action :approve (str "Ready but " (name risk-level) " risk"))
      (and ready? (= :low risk-level) (true? policy-pass?)) (recommend-action :merge "All gates green, low risk")
      (and ready? policy-unknown?)                     (recommend-action :evaluate "Policy not yet evaluated")
      :else                                            (recommend-action :wait "Awaiting signals"))))

;------------------------------------------------------------------------------ Layer 0e
;; Enrichment resolution — single fn for readiness/risk/policy lookup

(defn- resolve-enrichment
  "Resolve enriched readiness/risk/policy for a PR.
   Prefers pr-train/policy-pack data, falls back to naive derivation."
  [pr]
  {:readiness (or (:pr/readiness pr) (derive-readiness pr))
   :risk      (or (:pr/risk pr) (derive-risk pr))
   :policy    (:pr/policy pr)
   :recommend (derive-recommendation pr)})

(defn- policy-label
  "Policy pass/fail/unknown → display label."
  [policy]
  (case (:evaluation/passed? policy) true "pass" false "FAIL" "?"))

;------------------------------------------------------------------------------ Layer 1
;; Tree node constructors

(defn- tree-node
  "Build a tree node for the tree widget."
  ([label depth] {:label label :depth depth :expandable? false})
  ([label depth expandable?] {:label label :depth depth :expandable? expandable?}))

(defn- factor-label
  "Format a readiness/risk factor for display."
  [{:keys [factor weight score contribution]}]
  (str (name factor) ": "
       (int (* 100 (or score 0))) "%"
       " (w=" (int (* 100 (or weight 0))) "%"
       (when contribution (str ", c=" (format "%.2f" (double contribution))))
       ")"))

(defn- project-pr-row
  "Project a single PR into a table row map."
  [pr]
  (let [{:keys [readiness risk policy recommend]} (resolve-enrichment pr)]
    {:_id [(:pr/repo pr) (:pr/number pr)]
     :repo (or (:pr/repo pr) "")
     :number (str "#" (:pr/number pr))
     :title (or (:pr/title pr) "")
     :status (readiness-indicator (or (:readiness/state readiness) :unknown))
     :ready (readiness-bar (or (:readiness/score readiness) 0) 15)
     :risk (risk-label (or (:risk/level risk) :low))
     :policy (policy-label policy)
     :recommend (:label recommend)}))

(defn- project-pr-items
  "Project PR items for the fleet table widget.
   Respects :filtered-indices from search/filter modes."
  [model]
  (let [prs (:pr-items model [])
        filtered (if-let [fi (:filtered-indices model)]
                   (vec (keep-indexed (fn [i pr] (when (contains? fi i) pr)) prs))
                   prs)]
    (mapv project-pr-row filtered)))

(defn- resolve-detail-enrichment
  "Resolve enrichment data for the detail view's selected PR."
  [model]
  (let [pr-data (get-in model [:detail :selected-pr])]
    {:pr        pr-data
     :readiness (:pr/readiness pr-data)
     :risk      (:pr/risk pr-data)
     :policy    (:pr/policy pr-data)
     :gates     (get pr-data :pr/gate-results [])}))

(defn- project-readiness-tree
  "Build readiness tree nodes for the tree widget."
  [model]
  (let [{:keys [pr readiness]} (resolve-detail-enrichment model)
        score     (or (:readiness/score readiness) 0)
        ready?    (:readiness/ready? readiness)
        recommend (when pr (derive-recommendation pr))]
    (into (cond-> [(tree-node (str "Readiness: " (int (* 100 score)) "%"
                                   (when ready? " \u2714 ready"))
                              0 true)]
            recommend
            (conj (tree-node (str "Recommend: " (:label recommend) " \u2014 " (:reason recommend)) 0)))
          (mapv #(tree-node (factor-label %) 1)
                (:readiness/factors readiness [])))))

(defn- risk-factor-label
  "Format a risk factor for display."
  [{:keys [factor explanation weight score]}]
  (str (name factor) ": " (or explanation "")
       (when weight
         (str " (w=" (int (* 100 weight)) "%, s=" (int (* 100 (or score 0))) "%)"))))

(defn- project-risk-tree
  "Build risk tree nodes for the tree widget."
  [model]
  (let [{:keys [risk]} (resolve-detail-enrichment model)
        level   (or (:risk/level risk) :unknown)
        score   (:risk/score risk)]
    (into [(tree-node (str "Risk: " (name level)
                           (when score (str " (" (format "%.2f" (double score)) ")")))
                      0 true)]
          (mapv #(tree-node (risk-factor-label %) 1)
                (:risk/factors risk [])))))

(defn- severity-prefix [severity]
  (case severity
    :critical "\u2718 CRIT " :major "\u2718 MAJR "
    :minor    "\u26a0 MINR " :info  "\u2139 INFO " "\u26a0 "))

(defn- policy-tree [policy]
  (let [summary    (:evaluation/summary policy)
        violations (:evaluation/violations policy [])]
    (into [(tree-node (str "Policy: "
                           (if (:evaluation/passed? policy) "\u2714 passed" "\u2718 FAILED")
                           " (" (:total summary 0) " violations)")
                      0 true)]
          (concat
           (when-let [packs (seq (:evaluation/packs-applied policy))]
             [(tree-node (str "Packs: " (str/join ", " packs)) 1)])
           (mapv #(tree-node (str (severity-prefix (:severity %))
                                  (or (:message %) (name (:rule-id % "")))) 1)
                 violations)))))

(defn- gates-tree [gates]
  (mapv #(tree-node (str (if (:gate/passed? %) "pass " "FAIL ") (name (:gate/id %))) 0) gates))

(defn- project-gate-list
  "Build gate/policy result list for the tree widget."
  [model]
  (let [{:keys [policy gates]} (resolve-detail-enrichment model)]
    (cond
      policy     (policy-tree policy)
      (seq gates) (gates-tree gates)
      :else       [(tree-node "No policy/gate results" 0)])))

(defn- project-train-prs
  "Project train PRs for the table widget."
  [model]
  (let [train (get-in model [:detail :selected-train])
        prs (:train/prs train [])]
    (mapv (fn [pr]
            {:order (str (:pr/merge-order pr))
             :repo (or (:pr/repo pr) "")
             :pr (str "#" (:pr/number pr))
             :title (or (:pr/title pr) "")
             :status (some-> (:pr/status pr) name)
             :ci (some-> (:pr/ci-status pr) name)})
          prs)))

(defn- project-evidence-tree
  "Build evidence tree nodes."
  [model]
  (let [detail (:detail model)
        evidence (:evidence detail)
        phases (:phases detail)]
    (into []
      (concat
       [{:label "Intent" :depth 0 :expandable? true}
        {:label (or (get-in evidence [:intent :description])
                    "No intent data available")
         :depth 1 :expandable? false}]
       [{:label "Phases" :depth 0 :expandable? true}]
       (mapv (fn [{:keys [phase status]}]
               {:label (str (name phase)
                            (case status
                              :running  " ● running"
                              :success  " ✓ passed"
                              :failed   " ✗ failed"
                              ""))
                :depth 1 :expandable? false})
             phases)
       [{:label "Validation" :depth 0 :expandable? true}
        {:label (if (get-in evidence [:validation :passed?])
                  "✓ All gates passed"
                  (str "✗ " (count (get-in evidence [:validation :errors] [])) " error(s)"))
         :depth 1 :expandable? false}]
       [{:label "Policy" :depth 0 :expandable? true}
        {:label (if (get-in evidence [:policy :compliant?])
                  "✓ Policy compliant"
                  "✗ Policy violations detected")
         :depth 1 :expandable? false}]))))

(defn- project-artifacts
  "Project artifacts for the table widget."
  [model]
  (let [artifacts (get-in model [:detail :artifacts] [])]
    (mapv (fn [a]
            {:_id [:artifact (.indexOf ^java.util.List artifacts a)]
             :type (some-> (:type a) name)
             :name (or (:name a) (:path a) "unnamed")
             :phase (some-> (:phase a) name)
             :size (or (:size a) "-")
             :status (some-> (:status a) name)
             :time (or (safe-format-time (:created-at a)) "")})
          artifacts)))

(defn- project-kanban-columns
  "Project workflows into kanban columns."
  [model]
  (let [all-wfs (or (:workflows model) [])
        blocked   (filterv #(= :blocked (:status %)) all-wfs)
        ready     (filterv #(#{:ready :pending} (:status %)) all-wfs)
        active    (filterv #(#{:running :implementing :pr-opening :responding} (:status %)) all-wfs)
        in-review (filterv #(#{:ci-running :review-pending} (:status %)) all-wfs)
        merging   (filterv #(#{:ready-to-merge :merging} (:status %)) all-wfs)
        done      (filterv #(#{:merged :success :completed :failed :skipped} (:status %)) all-wfs)]
    [{:title "BLOCKED" :color :red
      :cards (mapv (fn [wf] {:label (:name wf) :status :blocked}) blocked)}
     {:title "READY" :color :yellow
      :cards (mapv (fn [wf] {:label (:name wf) :status :ready}) ready)}
     {:title "ACTIVE" :color :cyan
      :cards (mapv (fn [wf] {:label (:name wf) :status :running}) active)}
     {:title "IN REVIEW" :color :magenta
      :cards (mapv (fn [wf] {:label (:name wf) :status :review}) in-review)}
     {:title "MERGING" :color :blue
      :cards (mapv (fn [wf] {:label (:name wf) :status :merging}) merging)}
     {:title "DONE" :color :green
      :cards (mapv (fn [wf] {:label (:name wf) :status (or (:status wf) :success)}) done)}]))

(defn- project-phase-tree
  "Project workflow phases as tree nodes for the detail view."
  [model]
  (let [detail (:detail model)
        phases (:phases detail)
        wf-id (:workflow-id detail)
        wf (some #(when (= (:id %) wf-id) %) (:workflows model []))]
    (if (empty? phases)
      [{:label (str "Workflow " (or (:name wf) (some-> wf-id str (subs 0 8))) " — no phases")
        :depth 0 :expandable? false}]
      (mapv (fn [{:keys [phase status]}]
              {:label (str (name (or phase "?"))
                           (case status
                             :running  " ● running"
                             :success  " ✓ passed"
                             :failed   " ✗ failed"
                             :skipped  " – skipped"
                             ""))
               :depth 0 :expandable? false})
            phases))))

(defn- project-agent-output
  "Project agent output text as a single-row data vector for a text widget."
  [model]
  (let [detail (:detail model)
        agent (:current-agent detail)
        output (or (:agent-output detail) "")]
    [{:label (if agent
               (str "[" (name (or (:type agent) :agent)) "] " output)
               (if (seq output) output "No agent output"))}]))

(defn- project-repo-list
  "Project repo manager data for the table widget."
  [model]
  (let [source (or (:repo-manager-source model) :fleet)
        fleet-repos (set (or (:fleet-repos model) []))
        browse-repos (or (:browse-repos model) [])]
    (if (= source :browse)
      ;; Browse mode: show remote repos with fleet membership
      (mapv (fn [repo]
              {:_id repo
               :name repo
               :source (if (contains? fleet-repos repo) "fleet" "remote")
               :pr-count ""
               :status (if (contains? fleet-repos repo) "added" "available")})
            browse-repos)
      ;; Fleet mode: show configured repos
      (mapv (fn [repo]
              {:_id repo
               :name repo
               :source "fleet"
               :pr-count (str (count (filter #(= repo (:pr/repo %))
                                             (:pr-items model []))))
               :status "active"})
            (vec fleet-repos)))))

;------------------------------------------------------------------------------ Layer 2
;; Projection registry

(def ^:private projections
  "Registry of data projection functions: keyword -> (model -> data)."
  {:project/workflows      project-workflows
   :project/pr-items       project-pr-items
   :project/readiness-tree project-readiness-tree
   :project/risk-tree      project-risk-tree
   :project/gate-list      project-gate-list
   :project/train-prs      project-train-prs
   :project/evidence-tree  project-evidence-tree
   :project/artifacts      project-artifacts
   :project/kanban-columns project-kanban-columns
   :project/repo-list      project-repo-list
   :project/phase-tree     project-phase-tree
   :project/agent-output   project-agent-output})

(defn get-projection
  "Look up a projection function by keyword. Returns identity fn if not found."
  [kw]
  (get projections kw (fn [_] [])))

;------------------------------------------------------------------------------ Layer 2
;; Context functions (for tab-bar / title-bar text)

(defn- ctx-workflow-count [model]
  (let [wfs (:workflows model)
        ts (:last-updated model)]
    (str "[" (count wfs) "]"
         (when ts
           (str " " (safe-format-time ts))))))

(defn- ctx-pr-fleet-summary [model]
  (let [prs (:pr-items model [])
        repo-count (count (distinct (map :pr/repo prs)))
        merge-ready (count (filter #(= :merge-ready (:pr/status %)) prs))]
    (str "Repos: " repo-count
         " | PRs: " (count prs)
         " | Ready: " merge-ready)))

(defn- ctx-pr-detail-title [model]
  (let [pr-data (get-in model [:detail :selected-pr])]
    (str " MINIFORGE │ PR "
         (when (:pr/repo pr-data) (str (:pr/repo pr-data) " "))
         "#" (:pr/number pr-data "?")
         " " (:pr/title pr-data ""))))

(defn- ctx-train-title [model]
  (let [train (get-in model [:detail :selected-train])
        name (or (:train/name train) "Merge Train")
        progress (:train/progress train)]
    (str " MINIFORGE │ Train: " name
         (when progress
           (str " (" (:merged progress 0) "/" (:total progress 0) ")")))))

(defn- ctx-evidence-title [model]
  (let [wf-id (get-in model [:detail :workflow-id])
        wf-name (some #(when (= (:id %) wf-id) (:name %))
                      (:workflows model))]
    (or wf-name "Evidence")))

(defn- ctx-artifact-title [model]
  (let [wf-id (get-in model [:detail :workflow-id])
        wf-name (some #(when (= (:id %) wf-id) (:name %))
                      (:workflows model))]
    (or wf-name "Artifacts")))

(defn- ctx-artifact-box-title [model]
  (let [artifacts (get-in model [:detail :artifacts] [])]
    (str "Artifacts (" (count artifacts) ")")))

(defn- ctx-workflow-detail-title [model]
  (let [wf-id (get-in model [:detail :workflow-id])
        wf (some #(when (= (:id %) wf-id) %) (:workflows model []))
        phase (get-in model [:detail :current-phase])]
    (str " MINIFORGE │ "
         (or (:name wf) (some-> wf-id str (subs 0 8)) "Workflow")
         (when phase (str " │ " (name phase))))))

(defn- ctx-repo-manager-title [model]
  (let [idx (.indexOf ^java.util.List model/top-level-views :repo-manager)
        repos (or (:fleet-repos model) [])]
    (str "Repos (" (count repos) ") [" (inc idx) "]")))

(def ^:private contexts
  "Registry of context functions: keyword -> (model -> string)."
  {:ctx/workflow-count         ctx-workflow-count
   :ctx/pr-fleet-summary       ctx-pr-fleet-summary
   :ctx/pr-detail-title        ctx-pr-detail-title
   :ctx/train-title            ctx-train-title
   :ctx/evidence-title         ctx-evidence-title
   :ctx/artifact-title         ctx-artifact-title
   :ctx/artifact-box-title     ctx-artifact-box-title
   :ctx/repo-manager-title     ctx-repo-manager-title
   :ctx/workflow-detail-title  ctx-workflow-detail-title})

(defn get-context
  "Look up a context function by keyword. Returns a constant fn if not found."
  [kw]
  (get contexts kw (fn [_] "")))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (keys projections)
  (keys contexts)
  :leave-this-here)
