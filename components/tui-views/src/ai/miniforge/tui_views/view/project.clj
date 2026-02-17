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

(defn- project-pr-items
  "Project PR items for the fleet table widget."
  [model]
  (mapv (fn [pr]
          (let [readiness (derive-readiness pr)
                risk      (derive-risk pr)]
            {:_id [(:pr/repo pr) (:pr/number pr)]
             :repo (or (:pr/repo pr) "")
             :number (str "#" (:pr/number pr))
             :title (or (:pr/title pr) "")
             :status (readiness-indicator (:readiness/state readiness))
             :ready (readiness-bar (:readiness/score readiness) 15)
             :risk (risk-label (:risk/level risk))
             :policy (if (:pr/policy-passed? pr) "pass" "?")}))
        (:pr-items model [])))

(defn- project-readiness-tree
  "Build readiness tree nodes for the tree widget."
  [model]
  (let [readiness (get-in model [:detail :pr-readiness])
        score (or (:readiness/score readiness) 0)
        factors (:readiness/factors readiness [])]
    (into [{:label (str "Readiness: " (int (* 100 score)) "%")
            :depth 0 :expandable? true}]
          (mapv (fn [{:keys [factor weight score]}]
                  {:label (str (name factor) ": "
                               (int (* 100 (or score 0))) "%"
                               " (w=" (int (* 100 (or weight 0))) "%)")
                   :depth 1 :expandable? false})
                factors))))

(defn- project-risk-tree
  "Build risk tree nodes for the tree widget."
  [model]
  (let [risk (get-in model [:detail :pr-risk])
        level (or (:risk/level risk) :unknown)
        factors (:risk/factors risk [])]
    (into [{:label (str "Risk: " (name level))
            :depth 0 :expandable? true}]
          (mapv (fn [{:keys [factor explanation]}]
                  {:label (str (name factor) ": " explanation)
                   :depth 1 :expandable? false})
                factors))))

(defn- project-gate-list
  "Build gate result list for the tree widget."
  [model]
  (let [pr-data (get-in model [:detail :selected-pr])
        gates (get pr-data :pr/gate-results [])]
    (if (empty? gates)
      [{:label "No gate results" :depth 0 :expandable? false}]
      (mapv (fn [g]
              {:label (str (if (:gate/passed? g) "pass " "FAIL ")
                           (name (:gate/id g)))
               :depth 0 :expandable? false})
            gates))))

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
