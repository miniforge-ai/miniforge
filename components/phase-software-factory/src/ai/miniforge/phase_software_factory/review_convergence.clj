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

(ns ai.miniforge.phase-software-factory.review-convergence
  "Review-phase convergence engine: the pure decision logic that turns a
   completed review into a verdict. No ctx mutation, no IO — `review.clj`
   owns the phase interceptor and applies the decision this namespace
   returns. Operational config values live in
   resources/config/phase/defaults.edn (`:review/warning-churn-policy`,
   `:review/max-warning-only-cycles`); the defs here are fallbacks used only
   when that file is absent."
  (:require
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase-software-factory.messages :as messages]
   [clojure.string :as str]
   [malli.core :as m]))

;; ----------------------------------------------------------------------------
;; Config fallbacks + schema (operational values + rationale: defaults.edn)

(def default-warning-churn-policy
  "Fallback :review/warning-churn-policy when defaults.edn is absent."
  :accept-with-warnings)

(def default-max-warning-only-cycles
  "Fallback :review/max-warning-only-cycles when defaults.edn is absent."
  2)

(def default-max-no-progress-attempts
  "Fallback :review/max-no-progress-attempts — consecutive non-shrinking
   review cycles before :needs-decomposition fires."
  3)

(def default-absolute-max-redirect-attempts
  "Fallback :review/absolute-max-redirect-attempts — hard ceiling on
   review→implement cycles regardless of progress."
  6)

(def ^:private ReviewConvergenceConfigSchema
  "Malli schema for the convergence config keys. Open map — callers pass a
   select-keys subset before validating (see `validate-convergence-config!`)."
  [:map
   [:review/warning-churn-policy
    {:default default-warning-churn-policy}
    [:enum :accept-with-warnings :needs-decomposition]]
   [:review/max-warning-only-cycles
    {:default default-max-warning-only-cycles}
    [:int {:min 1}]]])

(defn- convergence-config-candidate
  [config]
  (merge {:review/warning-churn-policy    default-warning-churn-policy
          :review/max-warning-only-cycles default-max-warning-only-cycles}
         (select-keys config [:review/warning-churn-policy
                              :review/max-warning-only-cycles])))

(defn validate-convergence-config-result
  "Validate `config`'s convergence keys. Returns config on success or a
   canonical invalid-input anomaly on failure. Merges the fallbacks UNDER
   config first, so a partial override is accepted while an invalid VALUE
   remains explicit data."
  [config]
  (let [merged (convergence-config-candidate config)]
    (if (m/validate ReviewConvergenceConfigSchema merged)
      (merge config merged)
      (anomaly/validation-anomaly
       (messages/ts :review/invalid-convergence-config)
       :review-convergence/config
       merged
       (m/explain ReviewConvergenceConfigSchema merged)))))

(defn validate-convergence-config!
  "Boundary wrapper around the canonical anomaly-returning
   `validate-convergence-config-result`. Prefer the result function; this
   wrapper preserves fail-fast startup semantics for invalid defaults.edn."
  [config]
  (let [result (validate-convergence-config-result config)]
    (when (anomaly/anomaly? result)
      (throw (IllegalArgumentException.
              (str (:anomaly/message result)
                   " "
                   (pr-str (:anomaly/data result))))))))

;; ----------------------------------------------------------------------------
;; Decision primitives (pure)

(def blocking-decisions
  "Reviewer decisions meaning 'don't ship — fix it.' Both route through the
   :failed branch; the verdict decides redirect vs terminate."
  #{:rejected :changes-requested})

(def terminal-review-verdicts
  "Verdicts that end the repair loop without redirecting. Only these emit a
   :review/cause in the phase-completed payload."
  #{:accept-with-warnings :needs-decomposition :stagnated :exhausted})

(def verdicts
  "Phase 2b verdict tag set, flowing via [:phase :result :output
   :phase/verdict] to the FSM (read by :verdict/terminal?)."
  #{:approved :accept-with-warnings :repair-requested :stagnated :needs-decomposition
    :exhausted :review/backend-timeout})

(defn classify-rejection-cause
  "Classify a blocked review: :warning-churn (warnings only, no blocking)
   increments the warning-only count; :blocking-defect resets it to 0."
  [review-artifact prior-warning-only-count]
  (if (agent/rejection-warnings-only? review-artifact)
    {:cause/type :warning-churn  :warning-only-count (inc prior-warning-only-count)}
    {:cause/type :blocking-defect :warning-only-count 0}))

(defn compute-warning-churn?
  "True when the cause is :warning-churn and the count has reached the cap."
  [cause-type warning-only-count max-warning-only-cycles]
  (and (= :warning-churn cause-type)
       (>= warning-only-count max-warning-only-cycles)))

(defn count-blocking-issues
  "Blocking-issue count, tolerant of both :review/blocking-issues (real
   reviewer output) and :review/issues filtered by :severity :blocking."
  [review-artifact]
  (let [enumerated (count (agent/get-blocking-issues review-artifact))]
    (if (pos? enumerated)
      enumerated
      (count (filter #(= :blocking (:severity %)) (:review/issues review-artifact))))))

(defn compute-stagnated?
  "True when blocked AND the new fingerprint matches the prior cycle's —
   the repair loop produced no movement on the blocking complaints."
  [reviewer-blocked? prior-history current-fp]
  (and reviewer-blocked?
       (agent/review-stagnated? (peek prior-history) current-fp)))

(defn no-progress-streak
  "Consecutive trailing cycles whose blocking count did NOT strictly shrink,
   given the full per-cycle count sequence (oldest first, incl. current). A
   shrink resets the streak; the first cycle counts as no-progress.
   [3 2 1]→0, [3 2 2]→1, [1 1 1]→3."
  [counts]
  (reduce (fn [streak [prev cur]]
            (if (and prev (< cur prev)) 0 (inc streak)))
          0
          (map vector (cons nil counts) counts)))

(defn compute-needs-decomposition?
  "True when blocked + not stagnated + the loop is NOT converging — the
   blocking count failed to shrink for `max-no-progress-attempts` consecutive
   cycles, OR the absolute ceiling is hit. A shrinking loop runs to the
   ceiling (converging, just not in one turn)."
  [reviewer-blocked? stagnated? no-progress-streak
   review-cycle-count max-no-progress-attempts absolute-max-attempts]
  (and reviewer-blocked?
       (not stagnated?)
       (or (>= review-cycle-count absolute-max-attempts)
           (>= no-progress-streak max-no-progress-attempts))))

(defn compute-phase-status
  [reviewer-blocked? gate-failed?]
  (cond
    reviewer-blocked? :failed
    gate-failed?      :failed
    :else             :completed))

(defn compute-verdict
  "Pick the verdict. Priority: backend-timeout → stagnated →
   needs-decomposition → warning-churn (policy-resolved) → repair-requested
   → exhausted → approved."
  [{:keys [backend-timeout? reviewer-blocked? stagnated? needs-decomposition?
           warning-churn? warning-churn-policy within-budget? actionable-feedback?]}]
  (cond
    backend-timeout?     :review/backend-timeout
    stagnated?           :stagnated
    needs-decomposition? :needs-decomposition

    (and reviewer-blocked? warning-churn?)
    (case warning-churn-policy
      :accept-with-warnings :accept-with-warnings
      :needs-decomposition  :needs-decomposition
      ;; Programmer-error guard — invalid policy should have failed at config
      ;; load (ReviewConvergenceConfigSchema). Throw rather than mis-route (rule 005).
      (throw (IllegalArgumentException.
              (str "Unknown warning-churn-policy: " (pr-str warning-churn-policy)))))

    (and reviewer-blocked? within-budget? actionable-feedback?) :repair-requested
    reviewer-blocked?                                           :exhausted
    :else                                                       :approved))

(defn build-review-cause
  "Build the :review/cause map for a terminal verdict, else nil."
  [verdict cause-map warning-only-count review-cycle-count review-artifact]
  (when (contains? terminal-review-verdicts verdict)
    (cond-> {:cause/type                 (get cause-map :cause/type :blocking-defect)
             :review/warning-only-cycles warning-only-count
             :review/total-cycles        review-cycle-count
             :review/verdict             verdict}
      (= :accept-with-warnings verdict)
      (assoc :review/unresolved-warning-count
             (count (get review-artifact :review/warnings))))))

(defn review-feedback
  "Feedback the implementer consumes on a repair redirect. Falls back to
   :review/warnings (warnings ARE actionable when there are no blocking issues)."
  [result]
  (or (get-in result [:output :review/feedback])
      (get-in result [:output :review/issues])
      (not-empty (get-in result [:output :review/warnings]))))

(defn actionable-feedback?
  "True when feedback gives implement something concrete to repair."
  [feedback]
  (cond
    (string? feedback)     (not (str/blank? feedback))
    (sequential? feedback) (seq feedback)
    :else                  (some? feedback)))

;; ----------------------------------------------------------------------------
;; Orchestrator (pure): completed-review inputs → verdict + supporting data

(defn compute-review-decision
  "Resolve a completed review into a verdict and the data the caller applies
   to ctx. Pure: takes ctx-derived values, returns a decision map. Keeps
   leave-review a thin gather → decide → apply → emit orchestration.

   Returns {:verdict :review-cause :phase-status :reviewer-blocked?
            :current-fp :current-blocking-count :warning-only-count
            :review-cycle-count :feedback}."
  [{:keys [review-artifact review-decision result
           prior-fingerprints prior-blocking-counts prior-warning-only-count
           within-budget? gate-failed?
           max-no-progress-attempts absolute-max-attempts
           max-warning-only-cycles warning-churn-policy]}]
  (let [backend-timeout?       (= :reviewer/backend-timeout (get-in result [:error :data :code]))
        reviewer-blocked?      (contains? blocking-decisions review-decision)
        current-fp             (agent/review-fingerprint review-artifact)
        stagnated?             (compute-stagnated? reviewer-blocked? prior-fingerprints current-fp)
        phase-status           (compute-phase-status reviewer-blocked? gate-failed?)
        feedback               (review-feedback result)
        review-cycle-count     (inc (count prior-fingerprints))
        current-blocking-count (count-blocking-issues review-artifact)
        np-streak              (no-progress-streak
                                (conj (vec prior-blocking-counts) current-blocking-count))
        needs-decomposition?   (compute-needs-decomposition?
                                reviewer-blocked? stagnated? np-streak
                                review-cycle-count max-no-progress-attempts absolute-max-attempts)
        cause-map              (when reviewer-blocked?
                                 (classify-rejection-cause review-artifact prior-warning-only-count))
        warning-only-count     (get cause-map :warning-only-count prior-warning-only-count)
        warning-churn?         (boolean (when reviewer-blocked?
                                          (compute-warning-churn? (:cause/type cause-map)
                                                                  warning-only-count
                                                                  max-warning-only-cycles)))
        verdict                (compute-verdict {:backend-timeout?     backend-timeout?
                                                 :reviewer-blocked?    reviewer-blocked?
                                                 :stagnated?           stagnated?
                                                 :needs-decomposition? needs-decomposition?
                                                 :warning-churn?       warning-churn?
                                                 :warning-churn-policy warning-churn-policy
                                                 :within-budget?       within-budget?
                                                 :actionable-feedback? (actionable-feedback? feedback)})]
    {:verdict                verdict
     :review-cause           (build-review-cause verdict cause-map warning-only-count
                                                 review-cycle-count review-artifact)
     :phase-status           phase-status
     :reviewer-blocked?      reviewer-blocked?
     :current-fp             current-fp
     :current-blocking-count current-blocking-count
     :warning-only-count     warning-only-count
     :review-cycle-count     review-cycle-count
     :feedback               feedback}))
