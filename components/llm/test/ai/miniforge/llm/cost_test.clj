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

(ns ai.miniforge.llm.cost-test
  "Pins the contract for the cost-estimate fallback used when the
   upstream CLI doesn't surface total_cost_usd in its streaming
   result. Two surfaces: pricing-for-model (table lookup) and
   estimate-cost (arithmetic on usage + pricing)."
  (:require
   [ai.miniforge.llm.cost :as sut]
   [clojure.test :refer [deftest is testing]]))

;; Cost tolerance — pricing literals in the EDN table (`3.00`,
;; `0.25`, …) read as BigDecimal, so the intermediate arithmetic
;; mixes BigDecimal with int tokens and produces BigDecimal; the
;; final `(double ...)` cast in estimate-cost normalises to a
;; primitive double, but a tight epsilon keeps the assertions
;; stable across any cast-order surprises (and matches the
;; tolerance pattern used in dag-orchestrator-test).
(def ^:private cost-usd-epsilon 1.0e-9)

(defn- approx=
  [expected actual]
  (< (Math/abs (double (- expected actual))) cost-usd-epsilon))

;------------------------------------------------------------------------------ pricing-for-model

(deftest pricing-for-model-returns-entry-for-known-model-test
  (testing "Models in the cost table return their {:input-per-1m
            :output-per-1m} pair so the estimate-cost arithmetic
            can multiply through."
    (let [p (sut/pricing-for-model "claude-sonnet-4-6")]
      (is (= 3.0  (:input-per-1m p)))
      (is (= 15.0 (:output-per-1m p))))))

(deftest pricing-for-model-nil-for-unknown-model-test
  (testing "Unknown / unpriced models return nil so estimate-cost
            can fold to a $0.00 estimate rather than throwing."
    (is (nil? (sut/pricing-for-model "not-a-real-model")))
    (is (nil? (sut/pricing-for-model "free-local-llama"))
        "free / local models simply aren't in the table — caller
         interprets nil as 'no pricing, no estimate'")))

(deftest pricing-for-model-nil-input-safe-test
  (testing "nil / non-string input returns nil rather than NPE-ing
            on the get lookup — callers may pass through a config
            map's :model value without coercion."
    (is (nil? (sut/pricing-for-model nil)))
    (is (nil? (sut/pricing-for-model :keyword-not-string)))
    (is (nil? (sut/pricing-for-model 42)))))

;------------------------------------------------------------------------------ estimate-cost

(deftest estimate-cost-priced-model-test
  (testing "Known model + usage → USD computed via
            (in-tokens × in-per-1M + out-tokens × out-per-1M)
            / 1,000,000. sonnet-4-6 at 3.0/15.0 with 10k input +
            2k output = (10000 * 3 + 2000 * 15) / 1M = 60000/1M = 0.06"
    (is (approx= 0.06
                 (sut/estimate-cost {:input-tokens 10000
                                     :output-tokens 2000}
                                    "claude-sonnet-4-6")))))

(deftest estimate-cost-unpriced-model-returns-zero-test
  (testing "Unpriced model → 0.0 rather than nil or throw. Lets the
            caller assoc the value into the streaming response
            unconditionally without a nil-guard."
    (is (= 0.0 (sut/estimate-cost {:input-tokens 100000
                                   :output-tokens 50000}
                                  "free-local-model")))))

(deftest estimate-cost-nil-usage-returns-zero-test
  (testing "nil / empty usage with a priced model still returns 0.0 —
            zero tokens means zero cost, no surprises."
    (is (= 0.0 (sut/estimate-cost nil "claude-sonnet-4-6")))
    (is (= 0.0 (sut/estimate-cost {} "claude-sonnet-4-6")))))

(deftest estimate-cost-missing-input-or-output-tokens-test
  (testing "Partial usage maps (only :input-tokens, only
            :output-tokens) default the missing side to 0."
    (is (approx= 0.03
                 (sut/estimate-cost {:input-tokens 10000}
                                    "claude-sonnet-4-6"))
        "10000 * 3/1M = 0.03 — :output-tokens default 0 contributes 0")
    (is (approx= 0.03
                 (sut/estimate-cost {:output-tokens 2000}
                                    "claude-sonnet-4-6"))
        "2000 * 15/1M = 0.03 — :input-tokens default 0 contributes 0")))

(deftest estimate-cost-haiku-cheap-pricing-test
  (testing "Haiku's much cheaper pricing demonstrates the table
            is per-model (not a constant rate). 10k+2k tokens at
            $0.25/$1.25 per 1M = (10000*0.25 + 2000*1.25)/1M =
            (2500 + 2500)/1M = 0.005"
    (is (approx= 0.005
                 (sut/estimate-cost {:input-tokens 10000
                                     :output-tokens 2000}
                                    "claude-haiku-4-5-20251001")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.llm.cost-test)

  :leave-this-here)
