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
   [java.text SimpleDateFormat]
   [java.time LocalDate ZoneId]
   [java.time.temporal ChronoUnit]))

;------------------------------------------------------------------------------ Layer 0
;; Projection memoization (re-frame style subscription caching)

(defn memoize-by
  "Memoize a projection function by extracting input signals from the model.
   extract-fn: (model -> inputs) — extracts the subset of model data that
   the projection depends on. If inputs are identical? to the cached inputs,
   the cached result is returned without recomputation.

   Uses identical? for fast pointer-equality checks. Model updates via assoc
   create new map nodes only for changed paths, so unchanged subtrees keep
   the same identity."
  [proj-fn extract-fn]
  (let [cache (volatile! {:inputs ::init :result nil})]
    (fn [model]
      (let [inputs (extract-fn model)
            cached @cache]
        (if (identical? inputs (:inputs cached))
          (:result cached)
          ;; Fallback to value equality for cases where structure is rebuilt
          (if (= inputs (:inputs cached))
            (do (vreset! cache (assoc cached :inputs inputs))
                (:result cached))
            (let [result (proj-fn model)]
              (vreset! cache {:inputs inputs :result result})
              result)))))))

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

(defn readiness-state
  "Classify PR into [state score] from status and CI signals."
  [status ci-ok? ci-fail? behind?]
  (cond
    (= :merged status)
    [:merged 1.0]

    (= :closed status)
    [:closed 0.0]

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
    [:unknown 0.0]))

(defn readiness-blockers
  "Compute blocker list from PR status and CI signals."
  [status ci-fail? behind?]
  (cond-> []
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
           :blocker/source "author"})))

(defn readiness-factors
  "Compute weighted readiness factors and score.
   Weights: deps=0.25 ci=0.25 approved=0.20 gates=0.15 behind-main=0.15.
   Deps and gates default to 1.0 in naive derivation (no train context).
   Returns {:weighted float :factors [...]
            :ci-score float :review-score float :behind-score float}."
  [status ci-ok? ci-fail? behind?]
  (let [ci-score     (if ci-ok? 1.0 (if ci-fail? 0.0 0.5))
        review-score (case status
                       (:merged :merge-ready :approved) 1.0
                       :reviewing 0.5
                       :changes-requested 0.25
                       :open 0.3
                       :draft 0.0
                       :closed 0.0
                       0.0)
        behind-score (if behind? 0.0 1.0)
        weighted     (+ (* 0.25 1.0)
                       (* 0.25 ci-score)
                       (* 0.20 review-score)
                       (* 0.15 1.0)
                       (* 0.15 behind-score))]
    {:weighted     weighted
     :ci-score     ci-score
     :review-score review-score
     :behind-score behind-score
     :factors      [{:factor :deps-merged  :weight 0.25 :score 1.0}
                    {:factor :ci-passed    :weight 0.25 :score ci-score}
                    {:factor :approved     :weight 0.20 :score review-score}
                    {:factor :gates-passed :weight 0.15 :score 1.0}
                    {:factor :behind-main  :weight 0.15 :score behind-score}]}))

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
        [state _]    (readiness-state status ci-ok? ci-fail? behind?)
        blockers     (readiness-blockers status ci-fail? behind?)
        {:keys [weighted factors]} (readiness-factors status ci-ok? ci-fail? behind?)]
    {:readiness/state    state
     :readiness/score    weighted
     :readiness/ready?   (>= weighted 0.85)
     :readiness/blockers blockers
     :readiness/factors  factors}))

(defn derive-risk
  "Derive N9 risk assessment from provider signals and change size.
   Returns {:risk/level kw :risk/score float :risk/factors [{:factor kw :explanation str :value any} ...]}"
  [pr]
  (let [status    (:pr/status pr)
        ci        (:pr/ci-status pr)
        ci-fail?  (= :failed ci)
        changes?  (= :changes-requested status)
        additions (get pr :pr/additions 0)
        deletions (get pr :pr/deletions 0)
        total     (+ additions deletions)
        changed-files (get pr :pr/changed-files-count 0)
        size-risk (cond (> total 1000) :high (> total 500) :medium (> total 200) :low :else :minimal)
        factors   (cond-> []
                    (pos? total)
                    (conj {:factor :change-size
                           :explanation (str total " lines changed (+" additions "/-" deletions ")")
                           :value {:additions additions :deletions deletions :total total}
                           :score (cond (> total 1000) 1.0 (> total 500) 0.75 (> total 200) 0.5 :else 0.25)})
                    (pos? changed-files)
                    (conj {:factor :files-changed
                           :explanation (str changed-files " files modified")
                           :value changed-files
                           :score (cond (> changed-files 50) 1.0 (> changed-files 20) 0.7 (> changed-files 10) 0.4 :else 0.2)})
                    ci-fail?
                    (conj {:factor :ci-health
                           :explanation "CI checks are failing"
                           :score 0.8})
                    changes?
                    (conj {:factor :review-concerns
                           :explanation "Reviewer requested changes"
                           :score 0.6})
                    (and (not ci-fail?) (not changes?) (zero? total))
                    (conj {:factor :signal-check
                           :explanation "All available signals nominal"
                           :score 0.0}))
        score     (if (seq factors)
                    (/ (reduce + 0.0 (map #(get % :score 0.0) factors)) (count factors))
                    0.0)
        level     (cond
                    (or ci-fail? changes? (= size-risk :high)) :medium
                    (> score 0.6) :medium
                    (> score 0.3) :low
                    :else :low)]
    {:risk/level   level
     :risk/score   score
     :risk/factors factors}))

;------------------------------------------------------------------------------ Layer 0g
;; Temporal grouping

(defn- to-local-date
  "Convert a started-at value to LocalDate. Returns nil on failure."
  [started]
  (try
    (-> (if (instance? java.util.Date started)
          (.toInstant ^java.util.Date started)
          (java.time.Instant/parse (str started)))
        (.atZone (ZoneId/systemDefault))
        .toLocalDate)
    (catch Exception _ nil)))

(defn temporal-bucket
  "Classify a workflow's started-at into a temporal group.
   Accepts pre-computed `today` to avoid repeated LocalDate/now calls."
  [wf ^LocalDate today]
  (if-let [started (:started-at wf)]
    (if-let [wf-date (to-local-date started)]
      (let [days-ago (.between ChronoUnit/DAYS wf-date today)]
        (cond
          (zero? days-ago)  :today
          (= 1 days-ago)    :yesterday
          (<= days-ago 7)   :this-week
          (<= days-ago 30)  :this-month
          :else             :older))
      :unknown)
    :unknown))

(def ^:private bucket-labels
  {:today "Today" :yesterday "Yesterday" :this-week "This Week"
   :this-month "This Month" :older "Older" :unknown "Unknown"})

(def ^:private bucket-order
  [:today :yesterday :this-week :this-month :older :unknown])

(defn- format-started-time
  "Format started-at for display. Shows HH:mm for today/yesterday, MM-dd HH:mm otherwise."
  [started bucket]
  (when started
    (try
      (let [inst (if (instance? java.util.Date started)
                   (.toInstant ^java.util.Date started)
                   (java.time.Instant/parse (str started)))
            zdt (.atZone inst (ZoneId/systemDefault))]
        (if (#{:today :yesterday} bucket)
          (format "%02d:%02d" (.getHour zdt) (.getMinute zdt))
          (format "%02d-%02d %02d:%02d"
                  (.getMonthValue zdt) (.getDayOfMonth zdt)
                  (.getHour zdt) (.getMinute zdt))))
      (catch Exception _ ""))))

(defn group-workflows-with-headers
  "Group workflows into temporal buckets and interleave section header rows.
   Returns [flat-rows mapped-selected-idx].
   flat-rows: vector of maps, header rows marked with :_header? true.
   mapped-selected-idx: the visual row index corresponding to selected-idx
   (which counts only non-header rows)."
  [workflows selected-idx]
  (let [today (LocalDate/now)
        grouped (group-by #(temporal-bucket % today) workflows)
        buckets (filterv #(contains? grouped %) bucket-order)]
    (loop [bs buckets
           rows []
           wf-counter 0
           mapped nil]
      (if (empty? bs)
        [rows (or mapped 0)]
        (let [bucket (first bs)
              wfs (get grouped bucket)
              header {:_header? true
                      :status-char ""
                      :name (str "── " (get bucket-labels bucket) " (" (count wfs) ") ")
                      :name-fg :cyan
                      :phase ""
                      :time ""
                      :progress-str ""
                      :agent-msg ""}
              wf-rows (mapv (fn [wf]
                              {:_id (:id wf)
                               :status-char (status-char (:status wf))
                               :name (:name wf)
                               :phase (some-> (:phase wf) name)
                               :time (or (format-started-time (:started-at wf) bucket) "")
                               :progress-str (format-progress-bar (:progress wf 0) 20)
                               :agent-msg (when-let [agent (first (vals (:agents wf)))]
                                            (when-let [msg (:message agent)]
                                              (subs msg 0 (min 16 (count msg)))))})
                            wfs)
              new-rows (into (conj rows header) wf-rows)
              new-mapped (if (and (nil? mapped)
                                  (some? selected-idx)
                                  (< selected-idx (+ wf-counter (count wfs))))
                           (+ (count rows) 1 (- selected-idx wf-counter))
                           mapped)]
          (recur (rest bs)
                 new-rows
                 (+ wf-counter (count wfs))
                 new-mapped))))))

;------------------------------------------------------------------------------ Layer 1
;; Projection functions: model -> widget data

(defn- compute-workflow-rows
  "Expensive: filter, group, and format workflow data into table rows with headers.
   Returns the grouped row vector (without selection metadata)."
  [model]
  (let [wfs (vec (remove #(= :archived (:status %)) (:workflows model)))
        filtered (if-let [fi (:filtered-indices model)]
                   (vec (keep-indexed (fn [i wf] (when (contains? fi i) wf)) wfs))
                   wfs)
        [rows _] (group-workflows-with-headers filtered nil)]
    rows))

(def ^:private compute-workflow-rows-memo
  "Memoized workflow row computation. Only recomputes when workflows or filter changes."
  (memoize-by compute-workflow-rows
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

(defn readiness-indicator
  "Readiness state → status string with indicator character."
  [state]
  (case state
    :merged            "✓ merged"
    :closed            "─ closed"
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
     :state           (get readiness :readiness/state :unknown)
     :risk-level      (get risk :risk/level :low)
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
   Prefers pr-train/policy-pack data, falls back to naive derivation.
   Always ensures :readiness/state is set (explain-readiness doesn't provide it)."
  [pr]
  (let [derived  (derive-readiness pr)
        enriched (:pr/readiness pr)
        readiness (if enriched
                    ;; Merge derived state into enriched (explain-readiness lacks :readiness/state)
                    (merge derived enriched)
                    derived)]
    {:readiness readiness
     :risk      (or (:pr/risk pr) (derive-risk pr))
     :policy    (:pr/policy pr)
     :recommend (derive-recommendation pr)}))

(defn policy-label
  "Policy pass/fail/unknown → display label."
  [policy]
  (case (:evaluation/passed? policy) true "pass" false "FAIL" "?"))

;------------------------------------------------------------------------------ Layer 0f
;; Workflow-PR linkage helpers

(defn find-workflow-by-id
  "Find a workflow in the list by its UUID."
  [workflows wf-id]
  (some #(when (= (:id %) wf-id) %) workflows))

(defn workflow-matches-branch?
  "True when a workflow name partially matches a branch name (bidirectional)."
  [wf branch]
  (and (:name wf)
       (or (str/includes? (str branch) (str (:name wf)))
           (str/includes? (str (:name wf)) (str branch)))))

(defn find-linked-workflow
  "Find the workflow linked to a PR. Tries direct ID lookup first,
   then falls back to branch name matching."
  [workflows wf-id branch]
  (or (when wf-id (find-workflow-by-id workflows wf-id))
      (when branch (some #(when (workflow-matches-branch? % branch) %) workflows))))

;------------------------------------------------------------------------------ Layer 0g
;; Tree node primitives and pure node builders

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
    :merged       status-pass
    :closed       nil
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

(defn ci-check-node
  "Build a tree node for a single CI check result."
  [{:keys [name conclusion]}]
  (let [icon (case conclusion :success "\u2713" :failure "\u2718" :neutral "\u2500" "\u25cb")
        fg   (case conclusion :success status-pass :failure status-fail nil)]
    (tree-node (str icon " " name) 2 false fg)))

(defn ci-section-nodes
  "Build CI status header + individual check nodes."
  [ci-status ci-checks]
  (let [ci-fg (case ci-status :passed status-pass :failed status-fail status-warning)]
    (into [(tree-node (str "CI: " (case ci-status
                                    :passed "\u2713 passed" :failed "\u2718 failed"
                                    :running "\u25cb running" "\u25cb pending"))
                      1 true ci-fg)]
          (mapv ci-check-node ci-checks))))

(defn behind-main-node
  "Build the behind-main indicator node."
  [behind? merge-st]
  (tree-node (str "Behind main: " (if behind?
                                    (str "yes (" (or merge-st "BEHIND") ")")
                                    "no"))
             1 false (if behind? status-fail status-pass)))

(defn review-node
  "Build the review/approval status node."
  [pr-status]
  (let [[review-label review-fg]
        (case pr-status
          :approved           ["\u2713 approved"           status-pass]
          :changes-requested  ["\u25d0 changes requested"  status-fail]
          :reviewing          ["\u25cb review required"    status-warning]
          :draft              ["\u25d1 draft"              nil]
                              ["\u25cb pending"            status-warning])]
    (tree-node (str "Review: " review-label) 1 false review-fg)))

(defn gates-section-nodes
  "Build gate status header + individual gate nodes."
  [gates]
  (if (seq gates)
    (let [passed (count (filter :gate/passed? gates))
          all?   (= passed (count gates))]
      (into [(tree-node (str "Gates: " passed "/" (count gates) " passed")
                        1 true (if all? status-pass status-warning))]
            (mapv #(tree-node (str (if (:gate/passed? %) "\u2713 " "\u2718 ")
                                   (name (:gate/id %)))
                              2 false (if (:gate/passed? %) status-pass status-fail))
                  gates)))
    [(tree-node "Gates: none" 1)]))

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

(defn severity-prefix [severity]
  (case severity
    :critical "\u2718 CRIT " :major "\u2718 MAJR "
    :minor    "\u26a0 MINR " :info  "\u2139 INFO " "\u26a0 "))

(defn severity-color [severity]
  (case severity :critical status-fail :major status-fail :minor status-warning :info status-info nil))

(defn packs-applied-nodes
  "Build tree nodes for policy packs applied."
  [packs]
  (when (seq packs)
    (into [(tree-node (str "Packs applied (" (count packs) "):") 1 true)]
          (mapv #(tree-node (str "  " %) 2) packs))))

(defn severity-summary-nodes
  "Build a summary node listing violation counts by severity."
  [summary]
  (when summary
    (let [parts (cond-> []
                  (pos? (:critical summary 0)) (conj (str (:critical summary) " critical"))
                  (pos? (:major summary 0))    (conj (str (:major summary) " major"))
                  (pos? (:minor summary 0))    (conj (str (:minor summary) " minor"))
                  (pos? (:info summary 0))     (conj (str (:info summary) " info")))]
      (when (seq parts)
        [(tree-node (str "Summary: " (str/join ", " parts)) 1)]))))

(defn violation-nodes
  "Build tree nodes for individual policy violations."
  [violations]
  (when (seq violations)
    (into [(tree-node (str "Violations (" (count violations) "):") 1 true)]
          (mapv (fn [v]
                  (tree-node (str (severity-prefix (:severity v))
                                  (or (:message v) (name (get v :rule-id "")))
                                  (when (:auto-fixable? v) " [auto-fix]"))
                             2 false (severity-color (:severity v))))
                violations))))

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
           (packs-applied-nodes packs)
           (severity-summary-nodes summary)
           (violation-nodes violations)))))

(defn gates-tree [gates]
  (mapv #(tree-node (str (if (:gate/passed? %) "pass " "FAIL ") (name (:gate/id %)))
                    0 false (if (:gate/passed? %) status-pass status-fail))
        gates))

(defn intent-nodes
  "Build intent section nodes for evidence tree."
  [evidence]
  [{:label "Intent" :depth 0 :expandable? true}
   {:label (or (get-in evidence [:intent :description])
               "No intent data available")
    :depth 1 :expandable? false}])

(defn phase-nodes
  "Build phase section nodes for evidence tree."
  [phases]
  (into [{:label "Phases" :depth 0 :expandable? true}]
        (mapv (fn [{:keys [phase status]}]
                {:label (str (name phase)
                             (case status
                               :running  " ● running"
                               :success  " ✓ passed"
                               :failed   " ✗ failed"
                               ""))
                 :depth 1 :expandable? false})
              phases)))

(defn validation-nodes
  "Build validation section nodes for evidence tree."
  [evidence]
  [{:label "Validation" :depth 0 :expandable? true}
   {:label (if (get-in evidence [:validation :passed?])
             "✓ All gates passed"
             (str "✗ " (count (get-in evidence [:validation :errors] [])) " error(s)"))
    :depth 1 :expandable? false}])

(defn policy-evidence-nodes
  "Build policy section nodes for evidence tree."
  [evidence]
  [{:label "Policy" :depth 0 :expandable? true}
   {:label (if (get-in evidence [:policy :compliant?])
             "✓ Policy compliant"
             "✗ Policy violations detected")
    :depth 1 :expandable? false}])

;------------------------------------------------------------------------------ Layer 1
;; Model projections: (model) -> widget data

(defn project-pr-row
  "Project a single PR into a table row map.
   Includes :<key>-fg entries for per-cell status coloring.
   agent-risk-map is {[repo num] {:level kw :reason str}} from fleet triage."
  [pr agent-risk-map]
  (let [{:keys [readiness risk policy recommend]} (resolve-enrichment pr)
        r-state   (get readiness :readiness/state :unknown)
        risk-lvl  (get risk :risk/level :low)
        pol-pass? (:evaluation/passed? policy)
        pr-id     [(:pr/repo pr) (:pr/number pr)]
        agent-r   (get agent-risk-map pr-id)
        ;; Use agent risk when available, fall back to mechanical
        display-risk (if agent-r (:level agent-r) risk-lvl)]
    {:_id pr-id
     :repo (str (get pr :pr/repo "")
                (when (:pr/workflow-id pr) " [mf]"))
     :number (str "#" (:pr/number pr))
     :title (get pr :pr/title "")
     :state (pr-state-label (:pr/status pr))
     :status      (readiness-indicator r-state)
     :status-fg   (readiness-state-color r-state)
     :ready       (readiness-bar (get readiness :readiness/score 0) 15)
     :risk        (risk-label display-risk)
     :risk-fg     (risk-level-color display-risk)
     :policy      (policy-label policy)
     :policy-fg   (case pol-pass? true status-pass false status-fail nil)
     :recommend   (:label recommend)
     :recommend-fg (recommend-action-color (:action recommend))}))

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

(defn resolve-detail-enrichment
  "Resolve enrichment data for the detail view's selected PR.
   Falls back to naive derivation when enrichment is absent."
  [model]
  (let [pr-data (get-in model [:detail :selected-pr])]
    {:pr        pr-data
     :readiness (or (:pr/readiness pr-data) (when pr-data (derive-readiness pr-data)))
     :risk      (or (:pr/risk pr-data) (when pr-data (derive-risk pr-data)))
     :policy    (:pr/policy pr-data)
     :gates     (get pr-data :pr/gate-results [])}))

(defn project-readiness-tree
  "Build readiness tree nodes for the tree widget.
   Each factor is expandable with detail nodes at depth 1+."
  [model]
  (let [{:keys [pr readiness]} (resolve-detail-enrichment model)
        score     (get readiness :readiness/score 0)
        ready?    (:readiness/ready? readiness)
        recommend (when pr (derive-recommendation pr))]
    (into
     (cond-> [(tree-node (str "Readiness: " (int (* 100 score)) "%"
                               (when ready? " \u2714 ready"))
                          0 true (if ready? status-pass status-warning))]
       recommend
       (conj (tree-node (str "Recommend: " (:label recommend) " \u2014 " (:reason recommend))
                         0 false (recommend-action-color (:action recommend)))))
     (concat
      (ci-section-nodes (:pr/ci-status pr) (get pr :pr/ci-checks []))
      [(behind-main-node (:pr/behind-main? pr) (:pr/merge-state pr))]
      [(review-node (:pr/status pr))]
      (when (seq (get pr :pr/depends-on []))
        [(tree-node (str "Dependent PRs: " (count (get pr :pr/depends-on))) 1 true)])
      (gates-section-nodes (get pr :pr/gate-results []))))))

(defn project-risk-tree
  "Build risk tree nodes for the tree widget.
   Shows agent risk assessment (when available) and mechanical risk factors."
  [model]
  (let [{:keys [risk]} (resolve-detail-enrichment model)
        pr      (get-in model [:detail :selected-pr])
        pr-id   (when pr [(:pr/repo pr) (:pr/number pr)])
        agent-r (when pr-id (get-in model [:agent-risk pr-id]))
        wf-id   (:pr/workflow-id pr)
        level   (get risk :risk/level :unknown)
        score   (:risk/score risk)
        factors (:risk/factors risk [])]
    (concat
      ;; Provenance indicator
      (when wf-id
        [(tree-node "Miniforge-sourced PR" 0 false :cyan)])
      ;; Agent risk assessment (if available)
      (when agent-r
        [(tree-node (str "Agent risk: " (name (:level agent-r)))
                    0 true (risk-level-color (:level agent-r)))
         (tree-node (str "  " (:reason agent-r)) 1)])
      ;; Mechanical risk with factors
      [(tree-node (str "Mechanical risk: " (name level)
                       (when score (str " (" (format "%.2f" (double score)) ")")))
                  0 true (risk-level-color level))]
      (mapcat risk-factor-detail-nodes factors))))

(defn project-gate-list
  "Build gate/policy result list for the tree widget."
  [model]
  (let [{:keys [policy gates]} (resolve-detail-enrichment model)]
    (cond
      policy      (policy-tree policy)
      (seq gates) (gates-tree gates)
      :else       [(tree-node "Policy not yet evaluated" 0)
                   (tree-node "Use :review to evaluate policy packs" 1)])))

(defn project-pr-summary
  "Build summary tree nodes for the PR detail top pane.
   Shows PR metadata, status, and linked workflow at a glance."
  [model]
  (let [{:keys [pr readiness risk]} (resolve-detail-enrichment model)
        r-state  (get readiness :readiness/state :unknown)
        risk-lvl (get risk :risk/level :unknown)
        recommend (when pr (derive-recommendation pr))
        ;; Find linked workflow: direct lookup via workflow-id, fallback to branch name match
        wf-id    (:pr/workflow-id pr)
        branch   (:pr/branch pr)
        wfs      (get model :workflows [])
        linked-wf (find-linked-workflow wfs wf-id branch)]
    (let [additions (get pr :pr/additions 0)
          deletions (get pr :pr/deletions 0)
          total     (+ additions deletions)
          files     (get pr :pr/changed-files-count 0)]
      (cond-> [(tree-node (str (:pr/repo pr "") " #" (:pr/number pr "?"))
                          0 false status-info)
               (tree-node (str "  " (:pr/title pr "")) 0)
               (tree-node (str "Branch: " (or branch "?")
                               (when-let [author (:pr/author pr)]
                                 (when (seq author) (str " by " author))))
                          0)
               (tree-node (str "State: " (pr-state-label (:pr/status pr))
                               " │ Status: " (readiness-indicator r-state))
                          0 false (readiness-state-color r-state))
               (tree-node (str "Risk: " (name risk-lvl)
                               " │ Score: " (int (* 100 (get readiness :readiness/score 0))) "%"
                               (when (pos? total)
                                 (str " │ +" additions "/-" deletions
                                      (when (pos? files) (str " " files " files")))))
                          0 false (risk-level-color risk-lvl))]
        recommend
        (conj (tree-node (str "Action: " (:label recommend) " — " (:reason recommend))
                         0 false (recommend-action-color (:action recommend))))
        linked-wf
        (conj (tree-node (str "Workflow: " (:name linked-wf)
                              " (" (name (get linked-wf :status :unknown)) ")")
                         0 false status-info))
        (not linked-wf)
        (conj (tree-node "Workflow: not linked" 0))))))

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
       (intent-nodes evidence)
       (phase-nodes phases)
       (validation-nodes evidence)
       (policy-evidence-nodes evidence)))))

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

(defn- wrap-text
  "Word-wrap a string to fit within max-width characters.
   Returns a vector of wrapped lines."
  [text max-width]
  (if (<= (count text) max-width)
    [text]
    (loop [remaining text
           lines []]
      (if (<= (count remaining) max-width)
        (conj lines remaining)
        ;; Find last space within max-width
        (let [break-at (let [idx (str/last-index-of remaining " " max-width)]
                         (if (and idx (pos? idx)) idx max-width))]
          (recur (subs remaining (min (count remaining)
                                      (if (= break-at max-width)
                                        break-at
                                        (inc break-at))))
                 (conj lines (subs remaining 0 break-at))))))))

(defn project-chat-messages
  "Project chat messages as tree nodes for the agent panel.
   Shows conversation history with role-based styling and numbered actions.
   Uses :_panel-cols (injected by interpreter) for word-wrapping."
  [model]
  (let [messages (get-in model [:chat :messages] [])
        pending? (get-in model [:chat :pending?] false)
        actions  (get-in model [:chat :suggested-actions] [])
        ;; Panel cols minus tree indent overhead (depth*2 + icon "  " + label prefix "  ")
        panel-cols (or (:_panel-cols model) 60)
        wrap-width (max 20 (- panel-cols 6))]
    (if (empty? messages)
      [(tree-node "Press c to start a conversation" 0 false status-info)
       (tree-node "Ask about this PR, request analysis," 1)
       (tree-node "or take actions." 1)]
      (let [msg-nodes (mapcat
                        (fn [{:keys [role content]}]
                          (let [prefix (if (= :user role) "You" "Agent")
                                fg     (if (= :user role) :cyan :green)
                                lines  (str/split-lines (or content ""))]
                            (into [(tree-node (str prefix ":") 0 false fg)]
                                  (mapcat (fn [line]
                                            (mapv #(tree-node (str "  " %) 1)
                                                  (wrap-text line wrap-width)))
                                          lines))))
                        messages)
            action-nodes (when (and (seq actions) (not pending?))
                           (into [(tree-node "" 0)
                                  (tree-node "Actions (press number to run):" 0 false :yellow)]
                                 (mapcat
                                   (fn [i {:keys [label description]}]
                                     (let [text (str (inc i) ") " label
                                                     (when description
                                                       (str " — " description)))]
                                       (mapv #(tree-node (str "  " %) 1 false :cyan)
                                             (wrap-text text wrap-width))))
                                   (range) actions)))]
        (let [nodes (cond-> (vec msg-nodes)
                      (seq action-nodes) (into action-nodes))]
          (if pending?
            (let [since   (get-in model [:chat :pending-since])
                  elapsed (if since
                            (quot (- (System/currentTimeMillis) since) 1000)
                            0)
                  spinner (get ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"]
                               (mod elapsed 10))
                  label   (str spinner " Agent thinking... (" elapsed "s)")]
              (conj nodes (tree-node label 0 false :yellow)))
            nodes))))))

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
   :project/pr-items       (memoize-by project-pr-items
                             (fn [m] [(:pr-items m) (:filtered-indices m) (:agent-risk m)]))
   :project/pr-summary     (memoize-by project-pr-summary
                             (fn [m] [(get-in m [:detail :selected-pr]) (:workflows m)]))
   :project/readiness-tree (memoize-by project-readiness-tree
                             (fn [m] (get-in m [:detail :selected-pr])))
   :project/risk-tree      (memoize-by project-risk-tree
                             (fn [m] [(get-in m [:detail :selected-pr])
                                      (:agent-risk m)]))
   :project/gate-list      (memoize-by project-gate-list
                             (fn [m] (get-in m [:detail :selected-pr])))
   :project/train-prs      (memoize-by project-train-prs
                             (fn [m] (get-in m [:detail :selected-train])))
   :project/evidence-tree  (memoize-by project-evidence-tree
                             (fn [m] (:detail m)))
   :project/artifacts      (memoize-by project-artifacts
                             (fn [m] (get-in m [:detail :artifacts])))
   :project/kanban-columns (memoize-by project-kanban-columns
                             (fn [m] (:workflows m)))
   :project/repo-list      (let [cached (memoize-by project-repo-list
                                         (fn [m] [(:repo-manager-source m)
                                                  (:fleet-repos m)
                                                  (:browse-repos m)
                                                  (:pr-items m)]))]
                             (fn [m]
                               ;; Bypass cache during loading so sayings rotate
                               (if (:browse-repos-loading? m)
                                 (project-repo-list m)
                                 (cached m))))
   :project/phase-tree     (memoize-by project-phase-tree
                             (fn [m] [(:detail m) (:workflows m)]))
   :project/agent-output   (memoize-by project-agent-output
                             (fn [m] (:detail m)))
   :project/chat-messages  (let [cached (memoize-by project-chat-messages
                                        (fn [m] [(:chat m) (:chat-active-key m) (:_panel-cols m)]))]
                             (fn [m]
                               ;; Bypass cache while pending so spinner/elapsed updates
                               (if (get-in m [:chat :pending?])
                                 (project-chat-messages m)
                                 (cached m))))})

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
  "Registry of context functions: keyword -> (model -> string).
   Memoized by input signals — only recompute when relevant data changes."
  {:ctx/workflow-count         (memoize-by ctx-workflow-count
                                 (fn [m] [(:workflows m) (:last-updated m)]))
   :ctx/pr-fleet-summary       (memoize-by ctx-pr-fleet-summary
                                 (fn [m] [(:pr-items m) (:pr-filter-state m)]))
   :ctx/pr-detail-title        (memoize-by ctx-pr-detail-title
                                 (fn [m] (get-in m [:detail :selected-pr])))
   :ctx/train-title            (memoize-by ctx-train-title
                                 (fn [m] (get-in m [:detail :selected-train])))
   :ctx/evidence-title         (memoize-by ctx-evidence-title
                                 (fn [m] [(:detail m) (:workflows m)]))
   :ctx/artifact-title         (memoize-by ctx-artifact-title
                                 (fn [m] [(:detail m) (:workflows m)]))
   :ctx/artifact-box-title     (memoize-by ctx-artifact-box-title
                                 (fn [m] (get-in m [:detail :artifacts])))
   :ctx/repo-manager-title     (memoize-by ctx-repo-manager-title
                                 (fn [m] (:fleet-repos m)))
   :ctx/workflow-detail-title  (memoize-by ctx-workflow-detail-title
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
