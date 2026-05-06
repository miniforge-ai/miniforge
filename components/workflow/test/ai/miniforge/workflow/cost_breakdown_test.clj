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
            :cost/breakdown for the placement-of-attention drill-in.
            Duration and USD also accumulate, asserted alongside
            tokens so the four cumulative fields don't drift."
    (let [b (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement
                                    :tokens 1200 :duration-ms 8500 :usd 0.024})
                (cb/add-phase-cost {:phase :task/verify
                                    :tokens 200  :duration-ms 2200 :usd 0.004}))]
      (is (cb/valid? b))
      (is (= 1400 (:cost/total b)))
      (is (= 1200 (cb/phase-tokens b :task/implement)))
      (is (= 200  (cb/phase-tokens b :task/verify)))
      (is (= 0    (cb/phase-tokens b :task/release))
          "phase-tokens returns 0 for unseen phases (no nil in the API)")
      (is (= 10700 (:cost/duration-ms b))
          "duration-ms is the wall-clock sum across phases")
      (is (= 0.028 (:cost/usd b))
          "usd is the dollar sum across phases"))))

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
            boundaries to roll a child's cost into a parent's report.
            All four cumulative fields are asserted so a regression
            in any one of them surfaces here."
    (let [a (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement
                                    :tokens 1000 :iterations 2
                                    :duration-ms 5000 :usd 0.020}))
          b (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement
                                    :tokens 500  :iterations 1
                                    :duration-ms 2500 :usd 0.010})
                (cb/add-phase-cost {:phase :task/verify
                                    :tokens 200 :duration-ms 1000 :usd 0.004}))
          merged (cb/merge-breakdowns a b)]
      (is (= 1700 (:cost/total merged)))
      (is (= 1500 (cb/phase-tokens merged :task/implement)))
      (is (= 200  (cb/phase-tokens merged :task/verify)))
      (is (= 3    (cb/phase-iterations merged :task/implement))
          "iteration counts sum — total implement iterations across the run")
      (is (= 8500 (:cost/duration-ms merged))
          "duration-ms sums across child and parent")
      (is (= 0.034 (:cost/usd merged))
          "usd sums across child and parent"))))

(deftest merge-breakdowns-empty-is-identity-test
  (testing "merge-breakdowns with empty-breakdown is the identity. Lets
            callers safely fold a (possibly empty) sequence of
            breakdowns without special-casing the empty case."
    (let [b (-> (cb/empty-breakdown)
                (cb/add-phase-cost {:phase :task/implement :tokens 1000}))]
      (is (= b (cb/merge-breakdowns (cb/empty-breakdown) b)))
      (is (= b (cb/merge-breakdowns b (cb/empty-breakdown)))))))

(deftest add-phase-cost-rejects-iterations-on-non-iteration-phase-test
  (testing "Per spec §3.5, :cost/iterations is keyed only by phases
            that run an iteration loop (`:task/implement`,
            `:task/merge-resolution`). Setting :iterations on any
            other phase throws — programming-error catch at the
            telemetry-emitter site rather than letting bogus
            iteration counts flow into the dashboard."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not iterate"
         (cb/add-phase-cost (cb/empty-breakdown)
                            {:phase :task/release :iterations 3}))
        ":task/release runs once; iterations on it is meaningless")
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not iterate"
         (cb/add-phase-cost (cb/empty-breakdown)
                            {:phase :task/explore :iterations 1}))))
  (testing "Zero iterations on a non-iterating phase is fine — the
            check fires on positive counts only, so callers passing
            an explicit zero (e.g. uniform call shape from a metrics
            collector) don't get spurious throws."
    (let [b (cb/add-phase-cost (cb/empty-breakdown)
                               {:phase :task/release :iterations 0
                                :tokens 50})]
      (is (= 50 (cb/phase-tokens b :task/release))
          "tokens still accumulate; just no iteration entry"))))

(deftest schema-rejects-negative-or-non-finite-usd-test
  (testing "Cost in USD is non-negative and finite; the schema rejects
            negative numbers, NaN, and Infinity. Without this guard a
            corrupted upstream calculation could pollute dashboard
            totals (NaN propagates) or display nonsense ('-$3.14
            for the run')."
    (let [base (cb/empty-breakdown)]
      (is (false? (cb/valid? (assoc base :cost/usd -1.0)))
          "negative USD rejected")
      (is (false? (cb/valid? (assoc base :cost/usd Double/NaN)))
          "NaN USD rejected")
      (is (false? (cb/valid? (assoc base :cost/usd Double/POSITIVE_INFINITY)))
          "Infinity USD rejected")
      (is (true? (cb/valid? (assoc base :cost/usd 0.0)))
          "zero USD accepted")
      (is (true? (cb/valid? (assoc base :cost/usd 12.34)))
          "positive USD accepted"))))

(deftest schema-rejects-iterations-for-non-iteration-phases-test
  (testing "Even if a caller bypasses add-phase-cost and constructs a
            map directly, the schema rejects :cost/iterations entries
            for non-iteration phases. Belt-and-suspenders defense
            against telemetry-pipeline mistakes."
    (let [bad {:cost/total       0
               :cost/breakdown   {}
               ;; :task/release is in phase-keys but NOT in iteration-phase-keys
               :cost/iterations  {:task/release 1}
               :cost/duration-ms 0
               :cost/usd         0.0}]
      (is (false? (cb/valid? bad)))
      (is (some? (cb/explain bad))))))

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
