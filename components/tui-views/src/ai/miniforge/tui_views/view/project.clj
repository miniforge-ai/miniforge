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

(defn safe-format-time [ts]
  (when ts
    (try
      (.format (SimpleDateFormat. "HH:mm:ss") ts)
      (catch Exception _ ""))))

(defn status-char [status]
  (case status
    :running "●" :success "✓" :failed "✗" :blocked "◐" :archived "⊘" "○"))

(defn format-progress-bar [pct width]
  (let [pct (or pct 0)
        bar-w (max 1 (- width 5))
        filled (int (/ (* pct bar-w) 100))]
    (str (apply str (repeat filled \u2588))
         (apply str (repeat (- bar-w filled) \u2591))
         (format " %3d%%" pct))))

(defn readiness-bar [score width]
  (let [pct (int (* 100 (or score 0)))
        bar-w (max 1 (- width 5))
        filled (int (/ (* pct bar-w) 100))]
    (str (apply str (repeat filled \u2588))
         (apply str (repeat (- bar-w filled) \u2591))
         (format " %3d%%" pct))))

(defn risk-label [level]
  (case level :critical "CRIT" :high "high" :medium "med" :low "low" "?"))

;------------------------------------------------------------------------------ Layer 0b
;; Readiness + risk derivation (pure, from provider signals)

(defn derive-readiness
  "Derive N9 readiness state from provider signals.
   Returns {:readiness/state kw :readiness/score float :readiness/ready? bool
            :readiness/blockers [...]
            :readiness/factors [{:factor kw :weight float :score float} ...]}"
  [pr]
  (let [status       (:pr/status pr)
        ci           (:pr/ci-status pr)
        ci-ok?       (= :passed ci)
        ci-fail?     (= :failed ci)
        behind?      (:pr/behind-main? pr false)
        ;; Determine readiness state
        [state score]
        (cond
          (and (#{:merge-ready :approved} status) ci-ok? (not behind?))
          [:merge-ready 1.0]

          (and (#{:merge-ready :approved} status) ci-ok? behind?)
          [:behind-main 0.85]

          (and (= :approved status) ci-fail?)
          [:ci-failing 0.5]

          (and (= :approved status) (not ci-ok?))
          [:needs-review 0.7]

          (= :changes-requested status)
          [:changes-requested 0.25]

          (= :reviewing status)
          [:needs-review 0.5]

          (and (= :open status) ci-fail?)
          [:ci-failing 0.25]

          (= :open status)
          [:needs-review 0.4]

          (= :draft status)
          [:draft 0.1]

          :else
          [:unknown 0.0])
        ;; Compute blockers
        blockers (cond-> []
                   ci-fail?
                   (conj {:blocker/type :ci
                          :blocker/message "CI checks failing"
                          :blocker/source "provider"})
                   behind?
                   (conj {:blocker/type :behind-main
                          :blocker/message "Branch is behind main"
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
        ;; Compute factors — aligned with pr-train readiness weights
        ci-score     (if ci-ok? 1.0 (if ci-fail? 0.0 0.5))
        review-score (case status
                       (:merge-ready :approved) 1.0
                       :reviewing 0.5
                       :changes-requested 0.25
                       :open 0.3
                       :draft 0.0
                       0.0)
        behind-score (if behind? 0.0 1.0)
        ;; Weighted: deps=0.25 ci=0.25 approved=0.20 gates=0.15 behind-main=0.15
        ;; Deps and gates default to 1.0 in naive derivation (no train context)
        weighted     (+ (* 0.25 1.0)      ;; deps — assumed ok without train context
                       (* 0.25 ci-score)
                       (* 0.20 review-score)
                       (* 0.15 1.0)        ;; gates — assumed ok without gate data
                       (* 0.15 behind-score))]
    {:readiness/state    state
     :readiness/score    weighted
     :readiness/ready?   (>= weighted 0.85)
     :readiness/blockers blockers
     :readiness/factors  [{:factor :deps-merged  :weight 0.25 :score 1.0}
                          {:factor :ci-passed    :weight 0.25 :score ci-score}
                          {:factor :approved     :weight 0.20 :score review-score}
                          {:factor :gates-passed :weight 0.15 :score 1.0}
                          {:factor :behind-main  :weight 0.15 :score behind-score}]}))

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

(defn project-workflows
  "Project workflow list for the table widget."
  [model]
  (let [wfs (vec (remove #(= :archived (:status %)) (:workflows model)))
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

(defn readiness-indicator
  "Readiness state → status string with indicator character."
  [state]
  (case state
    :merge-ready       "✓ merge-ready"
    :ci-failing        "● ci-failing"
    :needs-review      "○ needs-review"
    :changes-requested "◐ changes-req"
    :behind-main       "◐ behind-main"
    :draft             "◑ draft"
    :merge-conflicts   "✗ conflicts"
    :policy-failing    "✗ policy-fail"
    :unknown           "? unknown"
    "? unknown"))

;------------------------------------------------------------------------------ Layer 0c
;; Recommendation constructors

(defn recommend
  "Build a recommendation map. Single constructor for all recommendation types."
  [action label reason]
  {:action action :label label :reason reason})

(def labels
  "Action → label mapping."
  {:remediate     "⚡ remediate"
   :review        "⊙ review"
   :evaluate      "◇ evaluate"
   :wait          "◌ wait"
   :do-not-merge  "✗ do not merge"
   :decompose     "◇ decompose"
   :approve       "⊘ approve"
   :merge         "→ merge"})

(defn recommend-action
  "Build recommendation for a known action keyword."
  [action reason]
  (recommend action (labels action) reason))

;------------------------------------------------------------------------------ Layer 0d
;; Recommendation signal extraction

(defn extract-pr-signals
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

   State matrix:
     merge         — all gates green, low risk, policy passed
     approve       — ready but elevated risk, needs human sign-off
     evaluate      — ready but policy not yet evaluated
     review        — needs review or has policy violations
     remediate     — auto-fixable policy violations
     decompose     — large PR, consider splitting
     do-not-merge  — CI failing, changes requested, behind main, draft
     wait          — no clear signal yet

   Ordering (policy-first to avoid unsafe merge on unknown data):
   1. Policy violations → remediate or review
   2. Policy unknown → evaluate first
   3. Hard blockers → do not merge
   4. Soft blockers → review/decompose
   5. Elevated risk → human approval
   6. All clear → merge"
  [pr]
  (let [{:keys [ready? state risk-level policy-pass? policy-unknown?
                has-violations? auto-fixable? large?]} (extract-pr-signals pr)]
    (cond
      ;; Policy violations — actionable
      (and has-violations? auto-fixable?)
      (recommend-action :remediate "Auto-fixable policy violations")

      (and has-violations? (not auto-fixable?))
      (recommend-action :review "Policy violations need review")

      (and (not policy-unknown?) (false? policy-pass?))
      (recommend-action :do-not-merge "Policy evaluation failed")

      ;; Policy unknown but otherwise ready — evaluate first
      (and policy-unknown? ready?)
      (recommend-action :evaluate "Policy not yet evaluated")

      ;; Hard blockers — do not merge
      (= :ci-failing state)
      (recommend-action :do-not-merge "CI failing")

      (= :changes-requested state)
      (recommend-action :do-not-merge "Changes requested by reviewer")

      (= :behind-main state)
      (recommend-action :do-not-merge "Branch behind main — rebase required")

      (= :draft state)
      (recommend-action :do-not-merge "Draft PR — not ready for merge")

      ;; Soft blockers — review/decompose
      (and large? (#{:needs-review :open} state))
      (recommend-action :decompose "Large PR — consider splitting")

      (= :needs-review state)
      (recommend-action :review "Awaiting review")

      ;; Ready — risk-gated merge
      (and ready? (#{:medium :high :critical} risk-level))
      (recommend-action :approve (str "Ready but " (name risk-level) " risk"))

      (and ready? (= :low risk-level) (true? policy-pass?))
      (recommend-action :merge "All gates green, low risk")

      (and ready? policy-unknown?)
      (recommend-action :evaluate "Policy not yet evaluated")

      :else
      (recommend-action :wait "Awaiting signals"))))

;------------------------------------------------------------------------------ Layer 0e
;; Enrichment resolution — single fn for readiness/risk/policy lookup

(defn resolve-enrichment
  "Resolve enriched readiness/risk/policy for a PR.
   Prefers pr-train/policy-pack data, falls back to naive derivation."
  [pr]
  {:readiness (or (:pr/readiness pr) (derive-readiness pr))
   :risk      (or (:pr/risk pr) (derive-risk pr))
   :policy    (:pr/policy pr)
   :recommend (derive-recommendation pr)})

(defn policy-label
  "Policy pass/fail/unknown → display label."
  [policy]
  (case (:evaluation/passed? policy) true "pass" false "FAIL" "?"))

;------------------------------------------------------------------------------ Layer 1
;; Tree node constructors

(defn tree-node
  "Build a tree node for the tree widget.
   Optional :fg sets per-node color (theme-independent status color)."
  ([label depth] {:label label :depth depth :expandable? false})
  ([label depth expandable?] {:label label :depth depth :expandable? expandable?})
  ([label depth expandable? fg] {:label label :depth depth :expandable? expandable? :fg fg}))

;; Semantic status colors — fixed across all themes
(def status-pass    :green)
(def status-fail    :red)
(def status-warning :yellow)
(def status-info    :cyan)

(defn readiness-state-color
  "Map readiness state to fixed status color."
  [state]
  (case state
    :merge-ready  status-pass
    :ci-failing   status-fail
    :behind-main  status-warning
    :needs-review status-warning
    :changes-requested status-fail
    :draft        nil
    :policy-failing status-fail
    :merge-conflicts status-fail
    nil))

(defn risk-level-color
  "Map risk level to fixed status color."
  [level]
  (case level
    :low      status-pass
    :medium   status-warning
    :high     status-fail
    :critical status-fail
    nil))

(defn recommend-action-color
  "Map recommendation action to fixed status color."
  [action]
  (case action
    :merge        status-pass
    :approve      status-pass
    :do-not-merge status-fail
    :remediate    status-fail
    :review       status-warning
    :evaluate     status-info
    :decompose    status-warning
    :wait         status-warning
    nil))

(defn factor-label
  "Format a readiness/risk factor for display."
  [{:keys [factor weight score contribution]}]
  (str (name factor) ": "
       (int (* 100 (or score 0))) "%"
       " (w=" (int (* 100 (or weight 0))) "%"
       (when contribution (str ", c=" (format "%.2f" (double contribution))))
       ")"))

(defn pr-state-label
  "Map normalized PR status keyword to human-readable GitHub-level state."
  [status]
  (case status
    :closed  "closed"
    :draft   "draft"
    :merged  "merged"
    (:approved :reviewing :changes-requested :open :merge-ready) "open"
    "open"))

(defn project-pr-row
  "Project a single PR into a table row map.
   Includes :<key>-fg entries for per-cell status coloring."
  [pr]
  (let [{:keys [readiness risk policy recommend]} (resolve-enrichment pr)
        r-state   (or (:readiness/state readiness) :unknown)
        risk-lvl  (or (:risk/level risk) :low)
        pol-pass? (:evaluation/passed? policy)]
    {:_id [(:pr/repo pr) (:pr/number pr)]
     :repo (or (:pr/repo pr) "")
     :number (str "#" (:pr/number pr))
     :title (or (:pr/title pr) "")
     :state (pr-state-label (:pr/status pr))
     :status      (readiness-indicator r-state)
     :status-fg   (readiness-state-color r-state)
     :ready       (readiness-bar (or (:readiness/score readiness) 0) 15)
     :risk        (risk-label risk-lvl)
     :risk-fg     (risk-level-color risk-lvl)
     :policy      (policy-label policy)
     :policy-fg   (case pol-pass? true status-pass false status-fail nil)
     :recommend   (:label recommend)
     :recommend-fg (recommend-action-color (:action recommend))}))

(defn project-pr-items
  "Project PR items for the fleet table widget.
   Respects :filtered-indices from search/filter modes."
  [model]
  (let [prs (:pr-items model [])
        filtered (if-let [fi (:filtered-indices model)]
                   (vec (keep-indexed (fn [i pr] (when (contains? fi i) pr)) prs))
                   prs)]
    (mapv project-pr-row filtered)))

(defn resolve-detail-enrichment
  "Resolve enrichment data for the detail view's selected PR."
  [model]
  (let [pr-data (get-in model [:detail :selected-pr])]
    {:pr        pr-data
     :readiness (:pr/readiness pr-data)
     :risk      (:pr/risk pr-data)
     :policy    (:pr/policy pr-data)
     :gates     (get pr-data :pr/gate-results [])}))


(defn ci-check-node
  "Build a tree node for a single CI check result."
  [{:keys [name conclusion]}]
  (let [icon (case conclusion :success "\u2713" :failure "\u2718" :neutral "\u2500" "\u25cb")
        fg   (case conclusion :success status-pass :failure status-fail nil)]
    (tree-node (str icon " " name) 2 false fg)))

(defn project-readiness-tree
  "Build readiness tree nodes for the tree widget.
   Each factor is expandable with detail nodes at depth 1+."
  [model]
  (let [{:keys [pr readiness]} (resolve-detail-enrichment model)
        score     (or (:readiness/score readiness) 0)
        ready?    (:readiness/ready? readiness)
        recommend (when pr (derive-recommendation pr))
        ci-checks (get pr :pr/ci-checks [])
        ci-status (:pr/ci-status pr)
        behind?   (:pr/behind-main? pr)
        merge-st  (:pr/merge-state pr)
        pr-status (:pr/status pr)
        deps      (get pr :pr/depends-on [])
        gates     (get pr :pr/gate-results [])]
    (into
     ;; Header + recommendation
     (cond-> [(tree-node (str "Readiness: " (int (* 100 score)) "%"
                               (when ready? " \u2714 ready"))
                          0 true (if ready? status-pass status-warning))]
       recommend
       (conj (tree-node (str "Recommend: " (:label recommend) " \u2014 " (:reason recommend))
                         0 false (recommend-action-color (:action recommend)))))
     (concat
      ;; CI factor — boolean with individual check drill-down
      (let [ci-fg (case ci-status :passed status-pass :failed status-fail status-warning)]
        [(tree-node (str "CI: " (case ci-status
                                   :passed "\u2713 passed" :failed "\u2718 failed"
                                   :running "\u25cb running" "\u25cb pending"))
                    1 true ci-fg)])
      (mapv ci-check-node ci-checks)

      ;; Behind main — replaces age/staleness
      [(tree-node (str "Behind main: " (if behind?
                                          (str "yes (" (or merge-st "BEHIND") ")")
                                          "no"))
                  1 false (if behind? status-fail status-pass))]

      ;; Review/approval status
      (let [[review-label review-fg]
            (case pr-status
              :approved           ["\u2713 approved"        status-pass]
              :changes-requested  ["\u25d0 changes requested" status-fail]
              :reviewing          ["\u25cb review required"   status-warning]
              :draft              ["\u25d1 draft"             nil]
                                  ["\u25cb pending"           status-warning])]
        [(tree-node (str "Review: " review-label) 1 false review-fg)])

      ;; Dependent PRs
      (when (seq deps)
        [(tree-node (str "Dependent PRs: " (count deps)) 1 true)])

      ;; Gates
      (if (seq gates)
        (let [passed (count (filter :gate/passed? gates))
              all?   (= passed (count gates))]
          (into [(tree-node (str "Gates: " passed "/" (count gates) " passed")
                            1 true (if all? status-pass status-warning))]
                (mapv #(tree-node (str (if (:gate/passed? %) "\u2713 " "\u2718 ")
                                       (name (:gate/id %)))
                                  2 false (if (:gate/passed? %) status-pass status-fail))
                      gates)))
        [(tree-node "Gates: none" 1)])))))

(defn risk-factor-label
  "Format a risk factor for display."
  [{:keys [factor explanation weight score]}]
  (str (name factor) ": " (or explanation "")
       (when weight
         (str " (w=" (int (* 100 weight)) "%, s=" (int (* 100 (or score 0))) "%)"))))


(defn risk-factor-detail-nodes
  "Build expandable detail nodes for a risk factor."
  [{:keys [factor value explanation]}]
  (case factor
    :change-size
    (let [{:keys [additions deletions total]} (if (map? value) value {})]
      (cond-> [(tree-node (str "Change size: " (or total "?") " lines"
                                (when (and total (> total 500)) " (large)"))
                           1 true)]
        (and additions deletions)
        (conj (tree-node (str "+" additions " / -" deletions) 2))))

    :dependency-fanout
    [(tree-node (str "Fanout: " (or value 0) " downstream PRs") 1)]

    :test-coverage-delta
    [(tree-node (str "Coverage: " (when (and value (pos? value)) "+")
                      (or value "?") "% delta")
                1)]

    :author-experience
    (let [{:keys [total-commits recent-commits]} (if (map? value) value {})]
      [(tree-node (str "Author: " (or total-commits "?") " commits, "
                        (or recent-commits "?") " recent")
                  1)])

    :review-staleness
    [(tree-node (str "Last review: " (or value "?") "h ago") 1)]

    :complexity-delta
    [(tree-node (str "Complexity delta: " (if (and value (pos? value)) "+" "")
                      (or value "?"))
                1)]

    :critical-files
    (let [{:keys [critical-files count]} (if (map? value) value {})]
      (into [(tree-node (str "Critical files: " (or count 0) " modified") 1 true)]
            (mapv #(tree-node (str "  " %) 2) (or critical-files []))))

    ;; Default: show explanation
    [(tree-node (str (name factor) ": " (or explanation "")) 1)]))

(defn project-risk-tree
  "Build risk tree nodes for the tree widget.
   Each factor is expandable with concrete values."
  [model]
  (let [{:keys [risk]} (resolve-detail-enrichment model)
        level   (or (:risk/level risk) :unknown)
        score   (:risk/score risk)
        factors (:risk/factors risk [])]
    (into [(tree-node (str "Risk: " (name level)
                           (when score (str " (" (format "%.2f" (double score)) ")")))
                      0 true (risk-level-color level))]
          (mapcat risk-factor-detail-nodes factors))))

(defn severity-prefix [severity]
  (case severity
    :critical "\u2718 CRIT " :major "\u2718 MAJR "
    :minor    "\u26a0 MINR " :info  "\u2139 INFO " "\u26a0 "))

(defn severity-color [severity]
  (case severity :critical status-fail :major status-fail :minor status-warning :info status-info nil))

(defn policy-tree [policy]
  (let [summary    (:evaluation/summary policy)
        violations (:evaluation/violations policy [])
        packs      (:evaluation/packs-applied policy [])
        passed?    (:evaluation/passed? policy)]
    (into [(tree-node (str "Policy: "
                           (if passed? "\u2714 passed" "\u2718 FAILED")
                           " (" (:total summary 0) " violations)")
                      0 true (if passed? status-pass status-fail))]
          (concat
           ;; Packs applied
           (when (seq packs)
             (into [(tree-node (str "Packs applied (" (count packs) "):") 1 true)]
                   (mapv #(tree-node (str "  " %) 2) packs)))

           ;; Summary by severity
           (when summary
             (let [parts (cond-> []
                           (pos? (:critical summary 0)) (conj (str (:critical summary) " critical"))
                           (pos? (:major summary 0))    (conj (str (:major summary) " major"))
                           (pos? (:minor summary 0))    (conj (str (:minor summary) " minor"))
                           (pos? (:info summary 0))     (conj (str (:info summary) " info")))]
               (when (seq parts)
                 [(tree-node (str "Summary: " (str/join ", " parts)) 1)])))

           ;; Individual violations
           (when (seq violations)
             (into [(tree-node (str "Violations (" (count violations) "):") 1 true)]
                   (mapv (fn [v]
                           (tree-node (str (severity-prefix (:severity v))
                                           (or (:message v) (name (get v :rule-id "")))
                                           (when (:auto-fixable? v) " [auto-fix]"))
                                      2 false (severity-color (:severity v))))
                         violations)))))))

(defn gates-tree [gates]
  (mapv #(tree-node (str (if (:gate/passed? %) "pass " "FAIL ") (name (:gate/id %)))
                    0 false (if (:gate/passed? %) status-pass status-fail))
        gates))

(defn project-gate-list
  "Build gate/policy result list for the tree widget."
  [model]
  (let [{:keys [policy gates]} (resolve-detail-enrichment model)]
    (cond
      policy      (policy-tree policy)
      (seq gates) (gates-tree gates)
      :else       [(tree-node "Policy not yet evaluated" 0)
                   (tree-node "Use :review to evaluate policy packs" 1)])))

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

(defn project-evidence-tree
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
             :time (or (safe-format-time (:created-at a)) "")})
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
      :cards (mapv (fn [wf] {:label (:name wf) :status (get wf :status :success)}) done)}]))

(defn project-phase-tree
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

(defn project-agent-output
  "Project agent output text as a single-row data vector for a text widget."
  [model]
  (let [detail (:detail model)
        agent (:current-agent detail)
        output (get detail :agent-output "")]
    [{:label (if agent
               (str "[" (name (get agent :type :agent)) "] " output)
               (if (seq output) output "No agent output"))}]))

(defn project-repo-list
  "Project repo manager data for the table widget."
  [model]
  (let [source (get model :repo-manager-source :fleet)
        fleet-repos (set (get model :fleet-repos []))
        browse-repos (get model :browse-repos [])]
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

(def projections
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

(defn ctx-workflow-count [model]
  (let [wfs (:workflows model)
        ts (:last-updated model)]
    (str "[" (count wfs) "]"
         (when ts
           (str " " (safe-format-time ts))))))

(defn ctx-pr-fleet-summary [model]
  (let [prs (:pr-items model [])
        filter-state (get model :pr-filter-state :open)
        repo-count (count (distinct (map :pr/repo prs)))
        merge-ready (count (filter (fn [pr]
                                     (let [r (or (:pr/readiness pr) (derive-readiness pr))]
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
