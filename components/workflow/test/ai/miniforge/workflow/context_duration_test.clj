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

(ns ai.miniforge.workflow.context-duration-test
  "Pin the wall-clock semantic of :execution/metrics :duration-ms.
   Pre-fix the field summed per-phase :duration-ms which under DAG
   parallelism overcounted (3 parallel sub-workflows × ~50m each =
   150m sum) or undercounted (sub-workflow durations didn't propagate
   into parent metrics). Post-fix it's the workflow wall-clock
   (`ended-at` − `started-at`)."
  (:require
   [ai.miniforge.workflow.context :as context]
   [clojure.test :refer [deftest is testing]]))

(def ^:private synthetic-started-at
  "Fixed instant in epoch ms — keeps the assertion deterministic."
  1000000000)

;; transition-to-completed and transition-to-failed both stamp
;; :execution/ended-at with (System/currentTimeMillis) at call
;; time, so we never preset it in the input ctx. Pre-fix tests
;; were assoc'ing a synthetic ended-at that the transition
;; immediately overwrote — misleading to readers and didn't
;; tighten the assertion.

(deftest transition-to-completed-stamps-wall-clock-duration-test
  (testing "transition-to-completed sets :execution/metrics :duration-ms
            to (ended-at − started-at) — exact difference, not just
            a value larger than something. Pin the difference
            semantic by computing the expected delta from the
            ended-at the transition itself stamps. Tokens and cost
            stay summed across phases (independent per-phase
            resource consumption); duration is wall-clock so the
            runner banner shows the user how long the run took."
    (let [ctx {:execution/started-at synthetic-started-at
               :execution/metrics    {:tokens 25864
                                      :cost-usd 0.42
                                      :duration-ms 582000}}
          completed (with-redefs [context/transition-execution identity]
                      (context/transition-to-completed ctx))
          stamped-ended-at (:execution/ended-at completed)
          expected-duration (- stamped-ended-at synthetic-started-at)]
      (is (= expected-duration
             (get-in completed [:execution/metrics :duration-ms]))
          ":duration-ms is the exact (ended-at − started-at) delta —
           pins the difference semantic so a future regression that
           accidentally stamps the raw timestamp (or any other large
           value) fails this assertion")
      (is (= 25864 (get-in completed [:execution/metrics :tokens]))
          "tokens preserved")
      (is (= 0.42 (get-in completed [:execution/metrics :cost-usd]))
          "cost preserved"))))

(deftest transition-to-failed-stamps-wall-clock-duration-test
  (testing "Failed workflows also report wall-clock duration so the
            user can see how long the run ran before failing. Same
            difference-semantic assertion as the completed-path test
            (not just '>= some floor')."
    (let [ctx {:execution/started-at synthetic-started-at
               :execution/metrics    {:tokens 1000
                                      :cost-usd 0.01
                                      :duration-ms 30000}}
          failed (with-redefs [context/transition-execution identity]
                   (context/transition-to-failed ctx))
          stamped-ended-at (:execution/ended-at failed)
          expected-duration (- stamped-ended-at synthetic-started-at)]
      (is (= expected-duration
             (get-in failed [:execution/metrics :duration-ms]))
          ":duration-ms is the exact (ended-at − started-at) delta
           on the failure path too"))))

(deftest transition-without-started-at-leaves-duration-untouched-test
  (testing "When :execution/started-at is missing (older callers /
            partial state), the wall-clock stamp is a no-op rather
            than producing nonsense. The pre-existing per-phase sum
            is preserved as a fallback."
    (let [ctx {:execution/metrics {:tokens 100
                                   :cost-usd 0.001
                                   :duration-ms 5000}}
          completed (with-redefs [context/transition-execution identity]
                      (context/transition-to-completed ctx))]
      (is (= 5000 (get-in completed [:execution/metrics :duration-ms]))
          "no started-at → duration left as the pre-existing value")
      (is (some? (:execution/ended-at completed))
          "ended-at is still stamped"))))

(deftest transition-handles-instant-shape-started-at-test
  (testing "Pre-existing inconsistency: context.clj writes
            :execution/started-at as System/currentTimeMillis (Long),
            runner.clj writes it as java.time.Instant/now. The
            wall-clock stamp must coerce either shape to epoch
            millis before subtracting, otherwise it
            ClassCastException-s on Instant inputs and crashes the
            workflow's terminal transition.

            Surfaced by run-pipeline-empty-test + fsm-state-tracked-
            test in the pre-commit suite — both use the runner
            path and were ERROR-ing on the unguarded subtraction
            until ->epoch-millis was added.

            Same difference-semantic assertion as the Long-path
            test: :duration-ms must equal (ended-at − coerced-
            started-at), not just clear the no-throw bar."
    (let [started-instant (java.time.Instant/ofEpochMilli synthetic-started-at)
          ctx {:execution/started-at started-instant
               :execution/metrics    {:tokens 0 :cost-usd 0.0
                                      :duration-ms 0}}
          completed (with-redefs [context/transition-execution identity]
                      (context/transition-to-completed ctx))
          stamped-ended-at (:execution/ended-at completed)
          expected-duration (- stamped-ended-at
                               (.toEpochMilli started-instant))]
      (is (= expected-duration
             (get-in completed [:execution/metrics :duration-ms]))
          "Instant-shaped started-at coerces correctly AND stamps
           the right delta (not just survives without throwing)"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.context-duration-test)

  :leave-this-here)
