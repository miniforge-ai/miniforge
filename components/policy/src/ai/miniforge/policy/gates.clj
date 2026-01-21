;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.policy.gates
  "Gate evaluation logic for validation checkpoints.

   Gates are validation rules that artifacts must pass before
   progressing to the next phase.

   Layer 0: Pure gate evaluation functions
   Layer 1: Coordinated gate evaluation across multiple gates"
  (:require
   [clojure.string :as str]))

;; ============================================================================
;; Layer 0 - Pure Gate Evaluators
;; ============================================================================

(defn evaluate-syntax-gate
  "Evaluate syntax validity for code artifacts.

   Returns nil if valid, failure map if invalid."
  [artifact config]
  (let [content (:artifact/content artifact)
        lang (:language config :clojure)]
    (when (and content (= lang :clojure))
      ;; Simple heuristic: check for balanced parens
      (let [open-count (count (re-seq #"\(" content))
            close-count (count (re-seq #"\)" content))]
        (when (not= open-count close-count)
          {:gate :syntax
           :reason "Unbalanced parentheses"
           :details {:open open-count
                     :close close-count
                     :language lang}})))))

(defn evaluate-lint-gate
  "Evaluate linting rules for code artifacts.

   Returns nil if valid, failure map if invalid."
  [artifact config]
  ;; Placeholder - real implementation would call clj-kondo
  ;; For now, check for basic issues
  (let [content (:artifact/content artifact)]
    (cond
      (nil? content)
      {:gate :lint
       :reason "Artifact has no content"
       :details {:config config}}

      (str/includes? content "println")
      {:gate :lint
       :reason "Contains debug println statements"
       :details {:pattern "println"}}

      :else
      nil)))

(defn evaluate-test-gate
  "Evaluate test pass/fail status.

   Returns nil if tests pass, failure map if tests fail."
  [artifact _config]
  (let [test-results (get-in artifact [:artifact/metadata :test-results])]
    (when (and test-results (not (:passed? test-results)))
      {:gate :test
       :reason "Tests failed"
       :details {:failures (:failures test-results)
                 :total (:total test-results)}})))

(defn evaluate-coverage-gate
  "Evaluate test coverage requirements.

   Returns nil if coverage meets threshold, failure map otherwise."
  [artifact config]
  (let [coverage (get-in artifact [:artifact/metadata :test-coverage] 0)
        threshold (:threshold config 0.8)]
    (when (< coverage threshold)
      {:gate :coverage
       :reason (str "Coverage below threshold: " coverage " < " threshold)
       :details {:coverage coverage
                 :threshold threshold}})))

;; ============================================================================
;; Layer 1 - Coordinated Gate Evaluation
;; ============================================================================

(defn evaluate-single-gate
  "Evaluate a single gate against an artifact.

   Dispatches to appropriate gate evaluator based on gate type."
  [artifact gate-config]
  (let [{:keys [type config]} gate-config]
    (case type
      :syntax   (evaluate-syntax-gate artifact config)
      :lint     (evaluate-lint-gate artifact config)
      :test     (evaluate-test-gate artifact config)
      :coverage (evaluate-coverage-gate artifact config)

      ;; Unknown gate type
      {:gate type
       :reason "Unknown gate type"
       :details {:type type}})))

(defn evaluate
  "Evaluate all gates for an artifact at a given phase.

   Returns evaluation result with pass/fail status and any failures."
  [artifact phase gate-configs]
  (let [failures (keep #(evaluate-single-gate artifact %) gate-configs)]
    {:passed? (empty? failures)
     :phase phase
     :gates-evaluated (count gate-configs)
     :failures failures}))

(defn create-gate
  "Create a gate configuration."
  [gate-type config]
  {:gate/type gate-type
   :gate/config config
   :gate/created-at (java.time.Instant/now)})

;; ============================================================================
;; Phase-Specific Gate Sets
;; ============================================================================

(def default-gates
  "Default gate configurations for each phase."
  {:implement
   [{:type :syntax :config {:language :clojure}}
    {:type :lint :config {}}]

   :verify
   [{:type :test :config {}}
    {:type :coverage :config {:threshold 0.7}}]

   :review
   [{:type :lint :config {}}
    {:type :coverage :config {:threshold 0.8}}]})

(defn get-phase-gates
  "Get default gates for a given phase."
  [phase]
  (get default-gates phase []))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Test syntax gate
  (evaluate-syntax-gate {:artifact/content "(defn foo [] 42)"}
                        {:language :clojure})
  ;; => nil (valid)

  (evaluate-syntax-gate {:artifact/content "(defn foo [] 42"}
                        {:language :clojure})
  ;; => {:gate :syntax, :reason "Unbalanced parentheses", ...}

  ;; Test gate evaluation
  (evaluate {:artifact/content "(defn foo [] 42)"
             :artifact/metadata {:test-coverage 0.9
                                 :test-results {:passed? true}}}
            :verify
            [{:type :test :config {}}
             {:type :coverage :config {:threshold 0.8}}])

  ;; Get default gates for a phase
  (get-phase-gates :implement)

  :end)
