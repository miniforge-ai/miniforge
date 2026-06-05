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

(ns ai.miniforge.agent.reviewer.gates
  "Reviewer gate plumbing.

   Extracted from `ai.miniforge.agent.reviewer` as PR-D of the
   decomposition stack started by PR #1039 (scope) and PR-C (issues).

   Holds the deterministic-gate side of the reviewer:
   - Running gates and converting their results to `GateFeedback`
     entries (`run-single-gate`, `run-gates-on-artifact`,
     `gate-result->feedback`, `create-exception-feedback`).
   - The custom `implementation-handoff-gate` that rejects degraded
     handoffs from the implementer before any LLM review runs.
   - Classifying errors as blocking / advisory and folding them into
     a deterministic decision (`error-blocking?`, `extract-*`,
     `decide-on-failures`, `make-review-decision`,
     `merge-gate-overrides`).
   - Summarizing gate outcomes (`generate-summary`,
     `generate-recommendations`, `calculate-gate-counts`).

   No dependencies on prompts, LLM-response infrastructure, or the
   review artifact builders — the gate path is the LLM-less fallback
   used when no LLM backend is wired."
  (:require [ai.miniforge.agent.messages :as msg]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.loop.interface :as loop]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Gate identity resolution
;;
;; Loop gate records (SyntaxGate / LintGate / TestGate / PolicyGate /
;; CustomGate) store their identifier in the plain `:id` field and their
;; type in the plain `:type-kw` field (CustomGate only — the others hard-
;; code their type at construction and pass it via loop/pass-result). The
;; gate map only carries `:gate/id` / `:gate/type` in forward-compat code
;; paths. Resolving from `(:gate/id gate)` alone surfaces every Loop
;; built-in as `:unknown` (2026-05-18 dogfood :failing-gate-ids
;; regression), so the resolution chain MUST also consult the plain
;; record fields.
;;
;; `gate-result->feedback` adds the result's `:gate/id` / `:gate/type` as
;; the highest-priority sources (those are populated by `loop/pass-result`
;; / `loop/fail-result` and are authoritative). The pre-execution sites
;; (`run-single-gate` start log, `create-exception-feedback`) have no
;; result yet, so the chain starts at the gate.

(defn- gate-id-from-gate
  "Resolve a gate's id from the gate record alone (no result available).

   Used at the pre-execution sites — the `run-single-gate` start-log and
   `create-exception-feedback` — that fire before or instead of
   `loop/pass-result`/`loop/fail-result`. Returns `:unknown` only when
   neither `:gate/id` (forward-compat) nor `:id` (the plain field on
   every shipping gate record) is set."
  [gate]
  (or (:gate/id gate) (:id gate) :unknown))

(defn- gate-type-from-gate
  "Resolve a gate's type from the gate record alone (no result available).

   Mirrors `gate-id-from-gate`. Only `CustomGate` carries `:type-kw` on
   the record; the other gate records pass their type through results
   only, so falling through to `:unknown` is the right answer at the
   pre-execution sites."
  [gate]
  (or (:gate/type gate) (:type-kw gate) :unknown))

;------------------------------------------------------------------------------ Layer 1
;; Gate result → feedback conversion

(defn gate-result->feedback
  "Convert a loop gate result to reviewer feedback format.

   Gate identity prefers the result's `:gate/id` (authoritative — set by
   `loop/pass-result`/`loop/fail-result`), then falls through to
   `gate-id-from-gate` for the pre-result fallback chain. Same shape for
   `:gate/type`."
  [gate result]
  (let [gate-id   (or (:gate/id result)   (gate-id-from-gate gate))
        gate-type (or (:gate/type result) (gate-type-from-gate gate))
        passed?   (:gate/passed? result true)
        errors    (:gate/errors result [])
        warnings  (:gate/warnings result [])]
    {:gate-id gate-id
     :gate-type gate-type
     :passed? passed?
     :errors errors
     :warnings warnings
     :duration-ms (get result :gate/duration-ms 0)}))

(defn create-exception-feedback
  "Create error feedback when a gate throws.

   `:gate-id` uses `gate-id-from-gate` so a crashed SyntaxGate / LintGate
   / PolicyGate surfaces its real identifier in artifacts and logs
   instead of falling back to the synthetic `:gate-N` name. The
   synthetic name is only used when the gate map has neither `:gate/id`
   nor `:id` (test scaffolding)."
  [gate idx exception duration]
  (let [resolved (gate-id-from-gate gate)]
    {:gate-id (if (= :unknown resolved)
                (keyword (str "gate-" idx))
                resolved)
     :gate-type :unknown
     :passed? false
     :errors [{:type :gate-exception
               :message (msg/t :reviewer.gates/exception
                               {:cause (ex-message exception)})}]
     :duration-ms duration}))

;------------------------------------------------------------------------------ Layer 2
;; Gate execution

(defn run-single-gate
  "Run a single gate and return feedback with timing.
   Handles exceptions gracefully."
  [gate idx artifact context logger]
  (let [gate-start (System/currentTimeMillis)
        gate-id (gate-id-from-gate gate)]
    (log/debug logger :reviewer :reviewer/gate-start
               {:data {:gate-idx idx :gate-id gate-id}})
    (try
      (let [result (loop/check-gate gate artifact context)
            duration (- (System/currentTimeMillis) gate-start)
            feedback (gate-result->feedback gate result)]
        (log/info logger :reviewer :reviewer/gate-complete
                  {:data {:gate-id (:gate-id feedback)
                          :passed? (:passed? feedback)
                          :duration-ms duration}})
        (assoc feedback :duration-ms duration))
      (catch Exception e
        (let [duration (- (System/currentTimeMillis) gate-start)]
          (log/error logger :reviewer :reviewer/gate-error
                     {:data {:gate-idx idx
                             :error (ex-message e)}})
          (create-exception-feedback gate idx e duration))))))

(defn run-gates-on-artifact
  "Run all gates on the artifact and collect results.
   Returns vector of GateFeedback maps."
  [gates artifact context logger]
  (log/info logger :reviewer :reviewer/running-gates
            {:data {:gate-count (count gates)}})
  (->> gates
       (map-indexed (fn [idx gate]
                      (run-single-gate gate idx artifact context logger)))
       vec))

;------------------------------------------------------------------------------ Layer 3
;; Custom gates

(defn implementation-handoff-gate
  "Reject degraded implement handoffs before semantic review.

   This catches environment-model cases where implement recovered a code artifact
   from disk after an agent-side failure, or where the curator flagged
   out-of-scope writes. Both are strong signals that review must not silently
   approve the handoff."
  []
  (loop/custom-gate
   :implementation-handoff
   :policy
   (fn [artifact _context]
     (let [degraded? (true? (:code/degraded-handoff? artifact))
           scope-deviations (vec (:code/scope-deviations artifact))
           errors (cond-> []
                    degraded?
                    (conj (loop/make-error
                           :degraded-implement-handoff
                           (msg/t :reviewer.gates/degraded-handoff)))
                    (seq scope-deviations)
                    (conj (loop/make-error
                           :scope-deviations
                           (msg/t :reviewer.gates/scope-deviations
                                  {:paths (str/join ", " scope-deviations)}))))]
       (if (seq errors)
         (loop/fail-result :implementation-handoff :policy errors)
         (loop/pass-result :implementation-handoff :policy))))))

;------------------------------------------------------------------------------ Layer 4
;; Error classification + decision

(def ^:private advisory-gate-types
  "Internal gate types whose failure is advisory — it must NOT override an
   LLM :approved verdict. Only :lint qualifies: a style/formatting failure on
   a build that already passed verify is not a ship-blocker, and treating it
   as one flipped verify-passed, LLM-:approved builds to :rejected, capping
   every dogfood spec at the :review-approved gate (observed 2026-05-24,
   workflow adhoc-807481487).

   Every other gate type blocks by default — fail-safe. That deliberately
   includes unresolved `:unknown` and gate-execution exceptions
   (`create-exception-feedback` produces `:unknown` errors with no
   `:severity`): a gate that crashed must block review, never silently
   approve."
  #{:lint})

(defn- error-blocking?
  "An internal-gate error is non-blocking only when it explicitly declares a
   non-:blocking severity, or — absent an explicit severity — comes from an
   advisory gate type. Everything else blocks (fail-safe), so an unresolved
   `:unknown` gate type or a gate crash cannot pass review open."
  [gate-type error]
  (if-let [severity (:severity error)]
    (= :blocking severity)
    (not (contains? advisory-gate-types gate-type))))

(defn extract-blocking-issues
  "Extract blocking errors from failed gates, honoring per-error severity and
   gate type (see `error-blocking?`). Errors from advisory gates such as
   :lint no longer force a rejection of an otherwise-approved review."
  [failed-gates]
  (->> failed-gates
       (mapcat (fn [{:keys [gate-type errors]}]
                 (filter #(error-blocking? gate-type %) errors)))
       vec))

(defn extract-warning-messages
  "Extract warning messages from all gates."
  [gate-feedbacks]
  (->> gate-feedbacks
       (mapcat :warnings)
       (map :message)
       vec))

(defn extract-error-messages
  "Extract error messages from failed gates."
  [failed-gates]
  (->> failed-gates
       (mapcat :errors)
       (map :message)
       vec))

(defn decide-on-failures
  "Determine decision when there are gate failures."
  [failed-gates blocking-issues config]
  (if (seq blocking-issues)
    {:decision :rejected
     :blocking-issues (mapv :message blocking-issues)
     :warnings []}
    {:decision (if (:strict config false) :rejected :conditionally-approved)
     :blocking-issues (if (:strict config)
                        (extract-error-messages failed-gates)
                        [])
     :warnings (extract-error-messages failed-gates)}))

(defn make-review-decision
  "Determine review decision based on gate results."
  [gate-feedbacks config]
  (let [failed (filter (complement :passed?) gate-feedbacks)
        failed-count (count failed)]
    (if (zero? failed-count)
      {:decision :approved
       :blocking-issues []
       :warnings (extract-warning-messages gate-feedbacks)}
      (let [blocking-issues (extract-blocking-issues failed)]
        (decide-on-failures failed blocking-issues config)))))

(defn merge-gate-overrides
  "If gates failed, override the LLM decision accordingly.

   `:conditionally-approved` is intentionally NOT in
   `issues/rejection-decisions` (it's an approval with nits to address
   later); guarding the LLM-approval downgrade by it keeps the gate from
   accidentally promoting a conditional approval back to plain approved."
  [llm-decision gate-decision config]
  (cond
    ;; Gate rejection always wins
    (= :rejected gate-decision)
    :rejected

    ;; Gate conditional-approval downgrades LLM approval
    (and (= :approved llm-decision) (= :conditionally-approved gate-decision))
    (if (:strict config) :rejected :conditionally-approved)

    ;; Otherwise use LLM decision
    :else
    llm-decision))

;------------------------------------------------------------------------------ Layer 5
;; Summaries + counts

(defn calculate-gate-counts
  "Calculate passed, failed, and total gate counts."
  [gate-feedbacks]
  (let [passed (count (filter :passed? gate-feedbacks))
        failed (count (filter (complement :passed?) gate-feedbacks))
        total (count gate-feedbacks)]
    {:passed passed :failed failed :total total}))

(defn generate-summary
  "Generate human-readable summary of review. Strings come from the agent
   message catalog so operator-visible copy stays localizable."
  [decision gate-feedbacks]
  (let [{:keys [passed failed total]} (calculate-gate-counts gate-feedbacks)]
    (case decision
      :approved
      (msg/t :reviewer.gates/summary-approved {:total total})

      :rejected
      (msg/t :reviewer.gates/summary-rejected {:failed failed :total total})

      :conditionally-approved
      (msg/t :reviewer.gates/summary-conditionally-approved
             {:passed passed :total total :failed failed})

      ;; default for :changes-requested or other LLM-sourced decisions
      (msg/t :reviewer.gates/summary-default
             {:decision (name decision) :total total}))))

(defn generate-recommendations
  "Generate recommendations based on gate results.

   Format: `[gate-id] error-message[ -> fix-suggestion]`. The bracketed
   gate id and arrow separator are operator-readable diagnostics, not
   localized copy — the underlying `:message` and `:fix-suggestion` from
   each gate carry whatever localization the gate itself supplies."
  [gate-feedbacks]
  (->> gate-feedbacks
       (filter (complement :passed?))
       (mapcat (fn [feedback]
                 (map (fn [error]
                        (str "[" (name (:gate-id feedback)) "] "
                             (:message error)
                             (when-let [fix (:fix-suggestion error)]
                               (str " -> " fix))))
                      (:errors feedback))))
       vec))
