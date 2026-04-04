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

(ns ai.miniforge.phase.phase-result
  "Factory functions and predicates for environment-model phase results.

   Phase results in the environment model carry a lightweight reference to the
   execution environment rather than serialized code artifacts. All factories
   here produce maps in the canonical shape:

     {:status         :success | :error
      :environment-id uuid
      :summary        string
      :metrics        {...}}

   The `skipped` factory produces a response-builder-compatible shape used
   by phases that short-circuit without invoking an executor.")

;------------------------------------------------------------------------------ Layer 0
;; Predicates

(defn succeeded?
  "True when a phase result has :status :success."
  [result]
  (= :success (:status result)))

(defn errored?
  "True when a phase result has :status :error."
  [result]
  (= :error (:status result)))

;------------------------------------------------------------------------------ Layer 1
;; Metric factories

(defn test-metrics
  "Build a standard test-phase metrics map.

   Pass-count and fail-count are counts of test cases.
   Test-output is the raw string output from the test runner."
  [pass-count fail-count test-output]
  {:tokens      0
   :duration-ms 0
   :pass-count  pass-count
   :fail-count  fail-count
   :test-output test-output})

;------------------------------------------------------------------------------ Layer 2
;; Context factory

(defn enter-context
  "Build the standard phase enter context.

   Sets the canonical [:phase ...] keys every enter-* interceptor needs:
   :name, :agent, :gates, :budget, :started-at, :status (:running), :result.

   Phases with additional keys (e.g. :rules-manifest, :workflow/pr-info) chain
   further assoc-in calls after this."
  [ctx phase-name agent gates budget start-time result]
  (-> ctx
      (assoc-in [:phase :name] phase-name)
      (assoc-in [:phase :agent] agent)
      (assoc-in [:phase :gates] gates)
      (assoc-in [:phase :budget] budget)
      (assoc-in [:phase :started-at] start-time)
      (assoc-in [:phase :status] :running)
      (assoc-in [:phase :result] result)))

;------------------------------------------------------------------------------ Layer 3
;; Result factories

(defn success
  "Build a successful environment-model phase result.

   2-arity: without metrics (implement phase — code is in the environment).
   3-arity: with metrics (verify phase — test counts travel with the result)."
  ([environment-id summary]
   {:status         :success
    :environment-id environment-id
    :summary        summary})
  ([environment-id summary metrics]
   (assoc (success environment-id summary) :metrics metrics)))

(defn error
  "Build an error environment-model phase result."
  [environment-id summary error-message metrics]
  {:status         :error
   :environment-id environment-id
   :summary        summary
   :error          {:message error-message}
   :metrics        metrics})

(defn skipped
  "Build a skipped-phase result.

   Used when a phase short-circuits because its work was already done.
   Produces a response-builder-compatible shape (has :output/:metrics)
   so it is handled correctly by leave-* handlers."
  [reason]
  {:status  :success
   :output  {:skipped true :reason reason}
   :metrics {:tokens 0 :duration-ms 0 :cost-usd 0.0}})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (succeeded? {:status :success})   ;; => true
  (succeeded? {:status :error})     ;; => false
  (errored?   {:status :error})       ;; => true

  (success "env-123" "Implementation complete")
  ;; => {:status :success :environment-id "env-123" :summary "Implementation complete"}

  (success "env-123" "All 5 test(s) passed" (test-metrics 5 0 "..."))
  ;; => {:status :success :environment-id "env-123" :summary "..."
  ;;     :metrics {:tokens 0 :duration-ms 0 :pass-count 5 :fail-count 0 :test-output "..."}}

  (error "env-123" "Tests failed: 2 failures" "Tests failed: 2 failures, 0 errors"
         (test-metrics 5 2 "..."))

  (skipped :already-implemented)
  ;; => {:status :success :output {:skipped true :reason :already-implemented}
  ;;     :metrics {:tokens 0 :duration-ms 0 :cost-usd 0.0}}

  :leave-this-here)
