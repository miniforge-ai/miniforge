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

(ns ai.miniforge.tui-views.view.project.helpers
  "Pure formatting helpers, data extraction, readiness/risk derivation,
   recommendation logic, enrichment resolution, workflow-PR linkage,
   and temporal grouping.

   Layer 0: Pure functions with no model dependency."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.palette :as palette])
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

;------------------------------------------------------------------------------ Layer 0b
;; Formatting helpers

(defn safe-format-time [ts]
  (when ts
    (try
      (.format (SimpleDateFormat. "HH:mm:ss") ts)
      (catch Exception _ ""))))

(defn status-char [status]
  (case status
    :running   "●"
    :success   "✓"
    :completed "✓"
    :failed    "✗"
    :blocked   "◐"
    :stale     "⊘"
    :archived  "⊘"
    "○"))

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

(defn readiness-blockers-summary
  "Compact blocker summary for fleet table. Shows what's needed for merge."
  [readiness]
  (let [blockers (:readiness/blockers readiness [])
        types    (set (map :blocker/type blockers))]
    (if (empty? blockers)
      "ready"
      (str/join ", "
        (cond-> []
          (:ci types)           (conj "CI")
          (:review types)       (conj "review")
          (:behind-main types)  (conj "rebase")
          (:changes types)      (conj "changes")
          (:draft types)        (conj "draft")
          (:conflicts types)    (conj "conflicts")
          (:policy types)       (conj "policy"))))))

(defn risk-label [level]
  (case level :critical "CRIT" :high "high" :medium "med" :low "low" :unevaluated "?" "?"))

;------------------------------------------------------------------------------ Layer 0c
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

    (and (#{:merge-ready :approved} status) ci-fail?)
    [:ci-failing 0.5]

    (and (#{:merge-ready :approved} status) (not ci-ok?))
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
  "Derive mechanical risk assessment from provider signals and change size.
   Returns {:risk/level kw :risk/score float :risk/factors [{:factor kw :explanation str :value any} ...]}
   Uses max-of-factors scoring (not averaging) so a single high-risk signal
   isn't diluted by low-risk ones."
  [pr]
  (let [status    (:pr/status pr)
        ci        (:pr/ci-status pr)
        ci-fail?  (= :failed ci)
        changes?  (= :changes-requested status)
        additions (get pr :pr/additions 0)
        deletions (get pr :pr/deletions 0)
        total     (+ additions deletions)
        changed-files (get pr :pr/changed-files-count 0)
        factors   (cond-> []
                    (pos? total)
                    (conj {:factor :change-size
                           :explanation (str total " lines changed (+" additions "/-" deletions ")")
                           :value {:additions additions :deletions deletions :total total}
                           :score (cond (> total 1000) 1.0 (> total 500) 0.75 (> total 200) 0.5 :else 0.2)})
                    (pos? changed-files)
                    (conj {:factor :files-changed
                           :explanation (str changed-files " files modified")
                           :value changed-files
                           :score (cond (> changed-files 50) 1.0 (> changed-files 20) 0.75 (> changed-files 10) 0.5 :else 0.2)})
                    ci-fail?
                    (conj {:factor :ci-health
                           :explanation "CI checks are failing"
                           :score 0.9})
                    changes?
                    (conj {:factor :review-concerns
                           :explanation "Reviewer requested changes"
                           :score 0.8})
                    (and (not ci-fail?) (not changes?) (zero? total))
                    (conj {:factor :signal-check
                           :explanation "All available signals nominal"
                           :score 0.0}))
        ;; Use max-of-factors, not average — one high signal shouldn't be diluted
        score     (if (seq factors)
                    (reduce max 0.0 (map #(get % :score 0.0) factors))
                    0.0)
        level     (cond
                    (and ci-fail? changes?)          :critical
                    (or ci-fail? changes?)            :high
                    (>= score 0.9)                    :high
                    (>= score 0.65)                   :medium
                    (>= score 0.4)                    :low
                    :else                             :low)]
    {:risk/level   level
     :risk/score   score
     :risk/factors factors}))

;------------------------------------------------------------------------------ Layer 0d
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

(defn- days-ago-bucket
  "Map a days-ago integer to a temporal bucket keyword."
  [days-ago]
  (cond
    (zero? days-ago)  :today
    (= 1 days-ago)    :yesterday
    (<= days-ago 7)   :this-week
    (<= days-ago 30)  :this-month
    :else             :older))

(defn temporal-bucket
  "Classify a workflow's started-at into a temporal group.
   Accepts pre-computed `today` to avoid repeated LocalDate/now calls."
  [wf ^LocalDate today]
  (if-let [wf-date (some-> (:started-at wf) to-local-date)]
    (days-ago-bucket (.between ChronoUnit/DAYS wf-date today))
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
                      :name-fg palette/status-info
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
;; Recommendation constructors and signal extraction

(defn readiness-indicator
  "Readiness state -> status string with indicator character."
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

(defn recommend
  "Build a recommendation map. Single constructor for all recommendation types."
  [action label reason]
  {:action action :label label :reason reason})

(def labels
  "Action -> label mapping."
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

(defn- normalize-readiness
  "Normalize readiness enrichment into the map shape expected by the view layer."
  [pr readiness]
  (cond
    (map? readiness)
    (merge (derive-readiness pr) readiness)

    (number? readiness)
    (let [derived (derive-readiness pr)]
      (assoc derived
             :readiness/score readiness
             :readiness/ready? (>= readiness 0.85)))

    :else
    (derive-readiness pr)))

(defn- normalize-risk
  "Normalize risk enrichment into the map shape expected by the view layer."
  [pr risk]
  (cond
    (map? risk)
    risk

    (keyword? risk)
    {:risk/level risk
     :risk/score nil
     :risk/factors []}

    :else
    (derive-risk pr)))

(defn extract-pr-signals
  "Extract enriched signals from a PR for recommendation.
   Returns a flat map of booleans/keywords for predicate use.
   Merges derived state into enriched readiness (explain-readiness lacks :readiness/state)."
  [pr]
  (let [readiness  (normalize-readiness pr (:pr/readiness pr))
        risk       (normalize-risk pr (:pr/risk pr))
        policy     (:pr/policy pr)
        violations (:evaluation/violations policy)
        changes    (+ (get pr :pr/additions 0)
                      (get pr :pr/deletions 0))]
    {:ready?          (:readiness/ready? readiness)
     :state           (get readiness :readiness/state :unknown)
     :risk-level      (get risk :risk/level :unevaluated)
     :policy-pass?    (:evaluation/passed? policy)
     :policy-unknown? (nil? policy)
     :has-violations? (boolean (seq violations))
     :auto-fixable?   (boolean (some :auto-fixable? violations))
     :large?          (> changes 500)}))

;; Layer 0 — recommendation branch helpers (called by derive-recommendation)

(defn- policy-violation-recommendation
  "Recommendation when policy violations are present."
  [{:keys [auto-fixable?]}]
  (if auto-fixable?
    (recommend-action :remediate "Auto-fixable policy violations")
    (recommend-action :review "Policy violations need review")))

(defn- policy-failed-recommendation []
  (recommend-action :do-not-merge "Policy evaluation failed"))

(defn- policy-unknown-ready-recommendation []
  (recommend-action :evaluate "Policy not yet evaluated"))

(defn- hard-blocker-recommendation
  "Recommendation for hard-blocker states (CI failing, changes requested, etc.)."
  [state]
  (case state
    :ci-failing        (recommend-action :do-not-merge "CI failing")
    :changes-requested (recommend-action :do-not-merge "Changes requested by reviewer")
    :behind-main       (recommend-action :do-not-merge "Branch behind main — rebase required")
    :draft             (recommend-action :do-not-merge "Draft PR — not ready for merge")
    nil))

(defn- soft-blocker-recommendation
  "Recommendation for soft-blocker states (review needed, large PR)."
  [{:keys [large? state]}]
  (cond
    (and large? (#{:needs-review :open} state)) (recommend-action :decompose "Large PR — consider splitting")
    (= :needs-review state)                     (recommend-action :review "Awaiting review")
    :else                                       nil))

(defn- ready-recommendation
  "Recommendation when the PR is ready — gated by risk and policy."
  [{:keys [risk-level policy-pass? policy-unknown?]}]
  (cond
    (= :unevaluated risk-level)                  (recommend-action :evaluate "Risk not yet assessed")
    (#{:medium :high :critical} risk-level)      (recommend-action :approve (str "Ready but " (name risk-level) " risk"))
    (and (= :low risk-level) (true? policy-pass?)) (recommend-action :merge "All gates green, low risk")
    policy-unknown?                              (recommend-action :evaluate "Policy not yet evaluated")
    :else                                        nil))

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
   1. Policy violations -> remediate or review
   2. Policy unknown -> evaluate first
   3. Hard blockers -> do not merge
   4. Soft blockers -> review/decompose
   5. Elevated risk -> human approval
   6. All clear -> merge"
  [pr]
  (let [{:keys [ready? state policy-pass? policy-unknown?
                has-violations?] :as signals} (extract-pr-signals pr)]
    (or (when has-violations?                              (policy-violation-recommendation signals))
        (when (and (not policy-unknown?) (false? policy-pass?)) (policy-failed-recommendation))
        (when (and policy-unknown? ready?)                 (policy-unknown-ready-recommendation))
        (hard-blocker-recommendation state)
        (soft-blocker-recommendation signals)
        (when ready?                                       (ready-recommendation signals))
        (recommend-action :wait "Awaiting signals"))))

;------------------------------------------------------------------------------ Layer 1b
;; Enrichment resolution

(defn resolve-enrichment
  "Resolve enriched readiness/risk/policy for a PR.
   Prefers pr-train/policy-pack data, falls back to naive derivation.
   Always ensures :readiness/state is set (explain-readiness doesn't provide it)."
  [pr]
  {:readiness (normalize-readiness pr (:pr/readiness pr))
   :risk      (normalize-risk pr (:pr/risk pr))
     :policy    (:pr/policy pr)
   :recommend (derive-recommendation pr)})

(defn policy-label
  "Policy pass/fail/unknown -> display label."
  [policy]
  (case (:evaluation/passed? policy) true "pass" false "FAIL" "?"))

;------------------------------------------------------------------------------ Layer 1c
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

(defn pr-state-label
  "Map normalized PR status keyword to human-readable GitHub-level state."
  [status]
  (case status
    :closed             "closed"
    :draft              "draft"
    :merged             "merged"
    :open               "open"
    :approved           "approved"
    :reviewing          "in review"
    :changes-requested  "changes req"
    :merge-ready        "merge ready"
    (if (nil? status) "—" "open")))

(defn wrap-text
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

;------------------------------------------------------------------------------ Rich Comment
(comment
  (readiness-state :approved true false false)
  (derive-readiness {:pr/status :open :pr/ci-status :passed})
  (derive-risk {:pr/status :open :pr/ci-status :failed :pr/additions 100 :pr/deletions 50})
  :leave-this-here)
