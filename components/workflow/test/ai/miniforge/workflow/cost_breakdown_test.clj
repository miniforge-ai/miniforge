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

(ns ai.miniforge.workflow.cost-breakdown-test
  "Tests for the canonical cost-breakdown shape (spec §3.5).
   Pure-data tests; no test fixtures needed beyond clojure.test."
  (:require
   [ai.miniforge.workflow.cost-breakdown :as cb]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; empty-breakdown / valid?

(deftest empty-breakdown-is-canonical-zero-test
  (testing "empty-breakdown produces an all-zero canonical map that
            validates against the schema. Used as the identity element
            when reducing phase-results into a single breakdown."
    (let [b (cb/empty-breakdown)]
      (is (cb/valid? b))
      (is (= 0 (:cost/total b)))
      (is (= {} (:cost/breakdown b)))
      (is (= {} (:cost/iterations b)))
      (is (= 0 (:cost/duration-ms b)))
      (is (= 0.0 (:cost/usd b))))))

(deftest schema-rejects-unknown-phase-test
  (testing "The closed-map / enum schema rejects a phase key that isn't
            in `phase-keys`. This is the boundary contract: typos in
            telemetry-emitting code surface as schema violations rather
            than silently flowing into the dashboard."
    (let [bad {:cost/total       0
               :cost/breakdown   {:task/imagined-phase 100}
               :cost/iterations  {}
               :cost/duration-ms 0
               :cost/usd         0.0}]
      (is (false? (cb/valid? bad)))
      (is (some? (cb/explain bad))))))

;------------------------------------------------------------------------------ Layer 1
;; add-phase-cost

(deftest add-phase-cost-rolls-tokens-into-total-and-breakdown-test
  (testing "Tokens add to :cost/total (sum) AND :cost/breakdown[phase]
            (per-phase). Both views must stay in sync — the dashboard
            uses :cost/total for the run-level number and
            :cost/breakdown for the placement-of-attention drill-in."
    (let [b (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement :tokens 1200})
                (cb/add-phase-cost {:phase :task/verify    :tokens 200}))]
      (is (cb/valid? b))
      (is (= 1400 (:cost/total b)))
      (is (= 1200 (cb/phase-tokens b :task/implement)))
      (is (= 200  (cb/phase-tokens b :task/verify)))
      (is (= 0    (cb/phase-tokens b :task/release))
          "phase-tokens returns 0 for unseen phases (no nil in the API)"))))

(deftest add-phase-cost-accumulates-iterations-test
  (testing "Iterations are per-phase only; cross-phase sums aren't
            meaningful. Within a phase, accumulating iterations across
            calls (e.g., an implement loop running across multiple
            invocations) sums correctly."
    (let [b (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement :iterations 3})
                (cb/add-phase-cost {:phase :task/implement :iterations 2})
                (cb/add-phase-cost {:phase :task/merge-resolution :iterations 1}))]
      (is (= 5 (cb/phase-iterations b :task/implement)))
      (is (= 1 (cb/phase-iterations b :task/merge-resolution)))
      (is (not (contains? (:cost/iterations b) :task/verify))
          "phases that didn't iterate don't appear in :cost/iterations"))))

(deftest add-phase-cost-omits-zero-iterations-test
  (testing "Zero-iteration calls don't pollute the :cost/iterations map.
            Otherwise every phase that ran a single shot would show
            `:phase 0`, which is noise on the dashboard."
    (let [b (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/release :tokens 100}))]
      (is (= {} (:cost/iterations b))
          "release ran but had no iteration-loop counter; map stays empty"))))

(deftest add-phase-cost-rejects-unknown-phase-test
  (testing "Adding a phase key that isn't in `phase-keys` throws — the
            error names what was passed and what was acceptable. Catches
            telemetry-emitter typos at the call site rather than at
            schema-validation time later."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unknown phase"
         (cb/add-phase-cost (cb/empty-breakdown)
                            {:phase :task/imagined :tokens 10})))))

(deftest add-phase-cost-defaults-zero-test
  (testing "Missing keys default to zero. A telemetry emitter that knows
            only the iteration count (e.g., merge-resolution loop) can
            add it without inventing dummy token / duration values."
    (let [b (cb/add-phase-cost (cb/empty-breakdown)
                               {:phase :task/merge-resolution :iterations 2})]
      (is (= 0 (:cost/total b)))
      (is (= 2 (cb/phase-iterations b :task/merge-resolution)))
      (is (= 0 (:cost/duration-ms b))))))

;------------------------------------------------------------------------------ Layer 1
;; merge-breakdowns

(deftest merge-breakdowns-sums-corresponding-fields-test
  (testing "Merging two breakdowns sums totals, per-phase tokens,
            iterations, duration, and dollars. Used at sub-workflow
            boundaries to roll a child's cost into a parent's report."
    (let [a (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement :tokens 1000 :iterations 2}))
          b (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement :tokens 500  :iterations 1})
                (cb/add-phase-cost {:phase :task/verify    :tokens 200}))
          merged (cb/merge-breakdowns a b)]
      (is (= 1700 (:cost/total merged)))
      (is (= 1500 (cb/phase-tokens merged :task/implement)))
      (is (= 200  (cb/phase-tokens merged :task/verify)))
      (is (= 3    (cb/phase-iterations merged :task/implement))
          "iteration counts sum — total implement iterations across the run"))))

(deftest merge-breakdowns-empty-is-identity-test
  (testing "merge-breakdowns with empty-breakdown is the identity. Lets
            callers safely fold a (possibly empty) sequence of
            breakdowns without special-casing the empty case."
    (let [b (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement :tokens 1000}))]
      (is (= b (cb/merge-breakdowns (cb/empty-breakdown) b)))
      (is (= b (cb/merge-breakdowns b (cb/empty-breakdown)))))))

;------------------------------------------------------------------------------ Layer 2
;; Read accessors

(deftest dominant-phase-test
  (testing "dominant-phase identifies where the most cost is. Lets the
            dashboard answer 'what should I optimize first?' without
            walking the breakdown map at every consumer."
    (is (= :task/implement
           (cb/dominant-phase
            {:cost/breakdown {:task/implement 1200
                              :task/verify     200
                              :task/merge-resolution 600}})))
    (is (nil? (cb/dominant-phase {:cost/breakdown {}}))
        "no phases recorded → no dominant — caller branches on nil")
    (is (nil? (cb/dominant-phase (cb/empty-breakdown))))))

(deftest accessors-default-to-zero-test
  (testing "phase-tokens / phase-iterations / total-tokens default to 0
            for unseen phases / empty breakdowns. Consumers don't need
            nil-handling — they get a meaningful number to render."
    (let [b (cb/empty-breakdown)]
      (is (= 0 (cb/total-tokens b)))
      (is (= 0 (cb/phase-tokens b :task/implement)))
      (is (= 0 (cb/phase-iterations b :task/merge-resolution))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.cost-breakdown-test)

  :leave-this-here)
