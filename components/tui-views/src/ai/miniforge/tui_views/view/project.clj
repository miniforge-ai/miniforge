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

   Delegates to:
     .project.helpers — pure formatting, readiness/risk derivation, recommendations
     .project.trees   — tree node builders, composite tree projections

   Layer 0: Re-exports from sub-namespaces for backward compatibility.
   Layer 1: Model projections (workflow rows, PR rows, artifacts, kanban, repos).
   Layer 2: Projection and context registries."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.view.project.helpers :as helpers]
   [ai.miniforge.tui-views.view.project.trees :as trees]))

;------------------------------------------------------------------------------ Layer 0
;; Re-exports — external code refers to these via project/<fn>

;; helpers re-exports
(def memoize-by              helpers/memoize-by)
(def safe-format-time        helpers/safe-format-time)
(def status-char             helpers/status-char)
(def format-progress-bar     helpers/format-progress-bar)
(def readiness-bar           helpers/readiness-bar)
(def risk-label              helpers/risk-label)
(def readiness-state         helpers/readiness-state)
(def readiness-blockers      helpers/readiness-blockers)
(def readiness-factors       helpers/readiness-factors)
(def derive-readiness        helpers/derive-readiness)
(def derive-risk             helpers/derive-risk)
(def group-workflows-with-headers helpers/group-workflows-with-headers)
(def readiness-indicator     helpers/readiness-indicator)
(def recommend               helpers/recommend)
(def labels                  helpers/labels)
(def recommend-action        helpers/recommend-action)
(def extract-pr-signals      helpers/extract-pr-signals)
(def derive-recommendation   helpers/derive-recommendation)
(def resolve-enrichment      helpers/resolve-enrichment)
(def policy-label            helpers/policy-label)
(def find-workflow-by-id     helpers/find-workflow-by-id)
(def workflow-matches-branch? helpers/workflow-matches-branch?)
(def find-linked-workflow    helpers/find-linked-workflow)
(def pr-state-label          helpers/pr-state-label)

;; trees re-exports
(def tree-node               trees/tree-node)
(def status-pass             trees/status-pass)
(def status-fail             trees/status-fail)
(def status-warning          trees/status-warning)
(def status-info             trees/status-info)
(def readiness-state-color   trees/readiness-state-color)
(def risk-level-color        trees/risk-level-color)
(def recommend-action-color  trees/recommend-action-color)
(def factor-label            trees/factor-label)
(def ci-check-node           trees/ci-check-node)
(def ci-section-nodes        trees/ci-section-nodes)
(def behind-main-node        trees/behind-main-node)
(def review-node             trees/review-node)
(def gates-section-nodes     trees/gates-section-nodes)
(def risk-factor-label       trees/risk-factor-label)
(def risk-factor-detail-nodes trees/risk-factor-detail-nodes)
(def severity-prefix         trees/severity-prefix)
(def severity-color          trees/severity-color)
(def packs-applied-nodes     trees/packs-applied-nodes)
(def severity-summary-nodes  trees/severity-summary-nodes)
(def violation-nodes         trees/violation-nodes)
(def policy-tree             trees/policy-tree)
(def gates-tree              trees/gates-tree)
(def intent-nodes            trees/intent-nodes)
(def phase-nodes             trees/phase-nodes)
(def validation-nodes        trees/validation-nodes)
(def policy-evidence-nodes   trees/policy-evidence-nodes)
(def resolve-detail-enrichment trees/resolve-detail-enrichment)
(def project-readiness-tree  trees/project-readiness-tree)
(def project-risk-tree       trees/project-risk-tree)
(def project-gate-list       trees/project-gate-list)
(def project-pr-summary      trees/project-pr-summary)
(def project-evidence-tree   trees/project-evidence-tree)
(def project-phase-tree      trees/project-phase-tree)
(def project-chat-messages   trees/project-chat-messages)

;------------------------------------------------------------------------------ Layer 1
;; Model projections: workflow rows, PR rows, artifacts, kanban, repos

(defn- compute-workflow-rows
  "Expensive: filter, group, and format workflow data into table rows with headers.
   Returns the grouped row vector (without selection metadata)."
  [model]
  (let [wfs (vec (remove #(= :archived (:status %)) (:workflows model)))
        filtered (if-let [fi (:filtered-indices model)]
                   (vec (keep-indexed (fn [i wf] (when (contains? fi i) wf)) wfs))
                   wfs)
        [rows _] (helpers/group-workflows-with-headers filtered nil)]
    rows))

(def ^:private compute-workflow-rows-memo
  "Memoized workflow row computation. Only recomputes when workflows or filter changes."
  (helpers/memoize-by compute-workflow-rows
              (fn [m] [(:workflows m) (:filtered-indices m)])))

(defn- map-selected-to-visual
  "Cheap: map a logical selected-idx to the visual row index in grouped rows
   (skipping header rows). O(n) scan but n is small (number of rows on screen)."
  [rows selected-idx]
  (let [sel (or selected-idx 0)]
    (loop [entries rows wf-idx 0 row-idx 0]
      (if (empty? entries)
        row-idx
        (let [entry (first entries)]
          (if (:_header? entry)
            (recur (rest entries) wf-idx (inc row-idx))
            (if (= wf-idx sel)
              row-idx
              (recur (rest entries) (inc wf-idx) (inc row-idx)))))))))

(defn project-workflows
  "Project workflow list for the table widget.
   Data rows are memoized — only recomputed when workflows/filter change.
   Selection mapping is cheap and computed fresh."
  [model]
  (let [rows (compute-workflow-rows-memo model)
        mapped (map-selected-to-visual rows (:selected-idx model))]
    (with-meta rows {:mapped-selected mapped})))

(defn project-pr-row
  "Project a single PR into a table row map.
   Includes :<key>-fg entries for per-cell status coloring.
   agent-risk-map is {[repo num] {:level kw :reason str}} from fleet triage."
  [pr agent-risk-map]
  (let [{:keys [readiness risk policy recommend]} (helpers/resolve-enrichment pr)
        r-state   (get readiness :readiness/state :unknown)
        risk-lvl  (get risk :risk/level :unevaluated)
        pol-pass? (:evaluation/passed? policy)
        pr-id     [(:pr/repo pr) (:pr/number pr)]
        agent-r   (get agent-risk-map pr-id)
        ;; Use agent risk when available, fall back to mechanical
        display-risk (if agent-r (:level agent-r) risk-lvl)]
    (let [;; Fold GitHub PR state into the readiness indicator when it adds info.
          ;; The readiness indicator already covers: merged, closed, draft, ci-failing,
          ;; merge-ready, needs-review, changes-requested, behind-main, conflicts, policy-fail.
          ;; GitHub states that add new info: :approved, :reviewing.
          pr-status (:pr/status pr)
          status-str (str (helpers/readiness-indicator r-state)
                          (case pr-status
                            :approved  " ✓"
                            :reviewing " ⊙"
                            ""))]
      {:_id pr-id
       :repo (str (get pr :pr/repo "")
                  (when (:pr/workflow-id pr) " [mf]"))
       :number (str "#" (:pr/number pr))
       :title (get pr :pr/title "")
       :status      status-str
       :status-fg   (trees/readiness-state-color r-state)
       :ready       (helpers/readiness-bar (get readiness :readiness/score 0) 15)
       :risk        (helpers/risk-label display-risk)
       :risk-fg     (trees/risk-level-color display-risk)
       :policy      (helpers/policy-label policy)
       :policy-fg   (case pol-pass? true trees/status-pass false trees/status-fail nil)
       :recommend   (:label recommend)
       :recommend-fg (trees/recommend-action-color (:action recommend))})))

(defn project-pr-items
  "Project PR items for the fleet table widget.
   Respects :filtered-indices from search/filter modes."
  [model]
  (let [prs (:pr-items model [])
        agent-risk (or (:agent-risk model) {})
        filtered (if-let [fi (:filtered-indices model)]
                   (vec (keep-indexed (fn [i pr] (when (contains? fi i) pr)) prs))
                   prs)]
    (mapv #(project-pr-row % agent-risk) filtered)))

(defn project-train-prs
  "Project train PRs for the table widget."
  [model]
  (let [train (get-in model [:detail :selected-train])
        prs (:train/prs train [])]
    (mapv (fn [pr]
            {:order (str (:pr/merge-order pr))
             :repo (get pr :pr/repo "")
             :pr (str "#" (:pr/number pr))
             :title (get pr :pr/title "")
             :status (some-> (:pr/status pr) name)
             :ci (some-> (:pr/ci-status pr) name)})
          prs)))

(defn project-artifacts
  "Project artifacts for the table widget."
  [model]
  (let [artifacts (get-in model [:detail :artifacts] [])]
    (mapv (fn [a]
            {:_id [:artifact (.indexOf ^java.util.List artifacts a)]
             :type (some-> (:type a) name)
             :name (or (:name a) (:path a) "unnamed")
             :phase (some-> (:phase a) name)
             :size (get a :size "-")
             :status (some-> (:status a) name)
             :time (or (helpers/safe-format-time (:created-at a)) "")})
          artifacts)))

(defn project-kanban-columns
  "Project workflows into kanban columns."
  [model]
  (let [all-wfs (vec (remove #(= :archived (:status %)) (get model :workflows [])))
        blocked   (filterv #(= :blocked (:status %)) all-wfs)
        ready     (filterv #(#{:ready :pending} (:status %)) all-wfs)
        active    (filterv #(#{:running :implementing :pr-opening :responding} (:status %)) all-wfs)
        in-review (filterv #(#{:ci-running :review-pending} (:status %)) all-wfs)
        merging   (filterv #(#{:ready-to-merge :merging} (:status %)) all-wfs)
        done      (filterv #(#{:merged :success :completed :failed :skipped} (:status %)) all-wfs)]
    [{:title "BLOCKED" :color [220 50 40]
      :cards (mapv (fn [wf] {:label (:name wf) :status :blocked}) blocked)}
     {:title "READY" :color [200 160 0]
      :cards (mapv (fn [wf] {:label (:name wf) :status :ready}) ready)}
     {:title "ACTIVE" :color [0 150 180]
      :cards (mapv (fn [wf] {:label (:name wf) :status :running}) active)}
     {:title "IN REVIEW" :color :magenta
      :cards (mapv (fn [wf] {:label (:name wf) :status :review}) in-review)}
     {:title "MERGING" :color :blue
      :cards (mapv (fn [wf] {:label (:name wf) :status :merging}) merging)}
     {:title "DONE" :color [0 180 80]
      :cards (mapv (fn [wf] {:label (:name wf) :status (get wf :status :success)}) done)}]))

(defn project-agent-output
  "Project agent output text as a single-row data vector for a text widget."
  [model]
  (let [detail (:detail model)
        agent (:current-agent detail)
        output (get detail :agent-output "")]
    [{:label (if agent
               (str "[" (name (get agent :type :agent)) "] " output)
               (if (seq output) output "No agent output"))}]))

(def ^:private browse-sayings
  ["Rummaging through repos..."
   "Consulting the git elders..."
   "Herding repos into a list..."
   "Asking GitHub nicely..."
   "Scanning the multiverse of repos..."
   "Bribing the API rate limiter..."
   "Polishing repo metadata..."
   "Untangling git spaghetti..."
   "Warming up the repo cannon..."
   "Teaching repos to sit and stay..."])

(defn- browse-loading-message []
  (let [idx (mod (quot (System/currentTimeMillis) 2000)
                 (count browse-sayings))]
    (nth browse-sayings idx)))

(defn project-repo-list
  "Project repo manager data for the table widget."
  [model]
  (let [source (get model :repo-manager-source :fleet)
        fleet-vec (vec (get model :fleet-repos []))
        fleet-set (set fleet-vec)
        browse-repos (get model :browse-repos [])
        loading? (get model :browse-repos-loading? false)]
    (if (= source :browse)
      (if (and loading? (empty? browse-repos))
        ;; Loading: show spinner row with a fun saying
        [{:_id :loading :name (str "⏳ " (browse-loading-message))
          :source "" :pr-count "" :status "loading"}]
        ;; Browse mode: show remote repos with fleet membership
        (mapv (fn [repo]
                {:_id repo
                 :name repo
                 :source (if (contains? fleet-set repo) "fleet" "remote")
                 :pr-count ""
                 :status (if (contains? fleet-set repo) "added" "available")})
              browse-repos))
      ;; Fleet mode: show configured repos (preserve vector order for selection)
      (mapv (fn [repo]
              {:_id repo
               :name repo
               :source "fleet"
               :pr-count (str (count (filter #(= repo (:pr/repo %))
                                             (:pr-items model []))))
               :status "active"})
            fleet-vec))))

;------------------------------------------------------------------------------ Layer 2
;; Projection registry

(def projections
  "Registry of data projection functions: keyword -> (model -> data).
   Projections are memoized by their input signals — they only recompute
   when the model keys they depend on actually change (re-frame style)."
  {:project/workflows      project-workflows ;; already memoized internally
   :project/pr-items       (helpers/memoize-by project-pr-items
                             (fn [m] [(:pr-items m) (:filtered-indices m) (:agent-risk m)]))
   :project/pr-summary     (helpers/memoize-by trees/project-pr-summary
                             (fn [m] [(get-in m [:detail :selected-pr]) (:workflows m)]))
   :project/readiness-tree (helpers/memoize-by trees/project-readiness-tree
                             (fn [m] (get-in m [:detail :selected-pr])))
   :project/risk-tree      (helpers/memoize-by trees/project-risk-tree
                             (fn [m] [(get-in m [:detail :selected-pr])
                                      (:agent-risk m)]))
   :project/gate-list      (helpers/memoize-by trees/project-gate-list
                             (fn [m] (get-in m [:detail :selected-pr])))
   :project/train-prs      (helpers/memoize-by project-train-prs
                             (fn [m] (get-in m [:detail :selected-train])))
   :project/evidence-tree  (helpers/memoize-by trees/project-evidence-tree
                             (fn [m] (:detail m)))
   :project/artifacts      (helpers/memoize-by project-artifacts
                             (fn [m] (get-in m [:detail :artifacts])))
   :project/kanban-columns (helpers/memoize-by project-kanban-columns
                             (fn [m] (:workflows m)))
   :project/repo-list      (let [cached (helpers/memoize-by project-repo-list
                                         (fn [m] [(:repo-manager-source m)
                                                  (:fleet-repos m)
                                                  (:browse-repos m)
                                                  (:pr-items m)]))]
                             (fn [m]
                               ;; Bypass cache during loading so sayings rotate
                               (if (:browse-repos-loading? m)
                                 (project-repo-list m)
                                 (cached m))))
   :project/phase-tree     (helpers/memoize-by trees/project-phase-tree
                             (fn [m] [(:detail m) (:workflows m)]))
   :project/agent-output   (helpers/memoize-by project-agent-output
                             (fn [m] (:detail m)))
   :project/chat-messages  (let [cached (helpers/memoize-by trees/project-chat-messages
                                        (fn [m] [(:chat m) (:chat-active-key m) (:_panel-cols m)]))]
                             (fn [m]
                               ;; Bypass cache while pending so spinner/elapsed updates
                               (if (get-in m [:chat :pending?])
                                 (trees/project-chat-messages m)
                                 (cached m))))})

(defn get-projection
  "Look up a projection function by keyword. Returns identity fn if not found."
  [kw]
  (get projections kw (fn [_] [])))

;------------------------------------------------------------------------------ Layer 2b
;; Context functions (for tab-bar / title-bar text)

(defn ctx-workflow-count [model]
  (let [wfs (:workflows model)
        ts (:last-updated model)]
    (str "[" (count wfs) "]"
         (when ts
           (str " " (helpers/safe-format-time ts))))))

(defn ctx-pr-fleet-summary [model]
  (let [prs (:pr-items model [])
        filter-state (get model :pr-filter-state :open)
        repo-count (count (distinct (map :pr/repo prs)))
        merge-ready (count (filter (fn [pr]
                                     (let [r (or (:pr/readiness pr) (helpers/derive-readiness pr))]
                                       (:readiness/ready? r)))
                                   prs))]
    (str (str/upper-case (name filter-state))
         " | Repos: " repo-count
         " | PRs: " (count prs)
         " | Ready: " merge-ready)))

(defn ctx-pr-detail-title [model]
  (let [pr-data (get-in model [:detail :selected-pr])]
    (str " MINIFORGE │ PR "
         (when (:pr/repo pr-data) (str (:pr/repo pr-data) " "))
         "#" (:pr/number pr-data "?")
         " " (:pr/title pr-data ""))))

(defn ctx-train-title [model]
  (let [train (get-in model [:detail :selected-train])
        name (or (:train/name train) "Merge Train")
        progress (:train/progress train)]
    (str " MINIFORGE │ Train: " name
         (when progress
           (str " (" (:merged progress 0) "/" (:total progress 0) ")")))))

(defn ctx-evidence-title [model]
  (let [wf-id (get-in model [:detail :workflow-id])
        wf-name (some #(when (= (:id %) wf-id) (:name %))
                      (:workflows model))]
    (or wf-name "Evidence")))

(defn ctx-artifact-title [model]
  (let [wf-id (get-in model [:detail :workflow-id])
        wf-name (some #(when (= (:id %) wf-id) (:name %))
                      (:workflows model))]
    (or wf-name "Artifacts")))

(defn ctx-artifact-box-title [model]
  (let [artifacts (get-in model [:detail :artifacts] [])]
    (str "Artifacts (" (count artifacts) ")")))

(defn ctx-workflow-detail-title [model]
  (let [wf-id (get-in model [:detail :workflow-id])
        wf (some #(when (= (:id %) wf-id) %) (:workflows model []))
        phase (get-in model [:detail :current-phase])]
    (str " MINIFORGE │ "
         (or (:name wf) (some-> wf-id str (subs 0 8)) "Workflow")
         (when phase (str " │ " (name phase))))))

(defn ctx-repo-manager-title [model]
  (let [idx (.indexOf ^java.util.List model/top-level-views :repo-manager)
        repos (get model :fleet-repos [])]
    (str "Repos (" (count repos) ") [" (inc idx) "]")))

(def contexts
  "Registry of context functions: keyword -> (model -> string).
   Memoized by input signals — only recompute when relevant data changes."
  {:ctx/workflow-count         (helpers/memoize-by ctx-workflow-count
                                 (fn [m] [(:workflows m) (:last-updated m)]))
   :ctx/pr-fleet-summary       (helpers/memoize-by ctx-pr-fleet-summary
                                 (fn [m] [(:pr-items m) (:pr-filter-state m)]))
   :ctx/pr-detail-title        (helpers/memoize-by ctx-pr-detail-title
                                 (fn [m] (get-in m [:detail :selected-pr])))
   :ctx/train-title            (helpers/memoize-by ctx-train-title
                                 (fn [m] (get-in m [:detail :selected-train])))
   :ctx/evidence-title         (helpers/memoize-by ctx-evidence-title
                                 (fn [m] [(:detail m) (:workflows m)]))
   :ctx/artifact-title         (helpers/memoize-by ctx-artifact-title
                                 (fn [m] [(:detail m) (:workflows m)]))
   :ctx/artifact-box-title     (helpers/memoize-by ctx-artifact-box-title
                                 (fn [m] (get-in m [:detail :artifacts])))
   :ctx/repo-manager-title     (helpers/memoize-by ctx-repo-manager-title
                                 (fn [m] (:fleet-repos m)))
   :ctx/workflow-detail-title  (helpers/memoize-by ctx-workflow-detail-title
                                 (fn [m] [(:detail m) (:workflows m)]))})

(defn get-context
  "Look up a context function by keyword. Returns a constant fn if not found."
  [kw]
  (get contexts kw (fn [_] "")))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (keys projections)
  (keys contexts)
  :leave-this-here)
