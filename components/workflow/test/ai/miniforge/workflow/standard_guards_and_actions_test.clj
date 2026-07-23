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

(ns ai.miniforge.workflow.standard-guards-and-actions-test
  "Verdict taxonomy (Fable §2.4). PR-A: the three classes name the existing
   terminal set; the union must stay identical so routing is unchanged."
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.standard-guards-and-actions :as sut]))

(deftest terminal-verdicts-union-is-unchanged
  (testing "terminal-verdicts is exactly the union of the three classes —
            membership identical to the pre-taxonomy flat set, so
            verdict-terminal? routing does not change in PR-A"
    (is (= sut/terminal-verdicts
           (set/union sut/infrastructure-verdicts
                      sut/no-op-verdicts
                      sut/work-terminal-verdicts)))
    (is (= #{:stagnated :needs-decomposition :exhausted
             :verify/timeout :verify/rate-limited :release/zero-files
             :implement/rate-limited :implement/empty-diff
             :implement/already-implemented-invalid :implement/network-dropped
             :review/backend-timeout :implement/backend-timeout
             :phase/timeout}
           sut/terminal-verdicts)
        "pins the exact terminal set so any future change is deliberate")))

(deftest verdict-classes-are-disjoint
  (testing "no verdict belongs to more than one terminal class"
    (is (empty? (set/intersection sut/infrastructure-verdicts sut/no-op-verdicts)))
    (is (empty? (set/intersection sut/infrastructure-verdicts sut/work-terminal-verdicts)))
    (is (empty? (set/intersection sut/no-op-verdicts sut/work-terminal-verdicts)))))

(deftest verdict-class-classification
  (testing "each terminal verdict classifies into its class"
    (is (= :infrastructure (sut/verdict-class :verify/timeout)))
    (is (= :infrastructure (sut/verdict-class :review/backend-timeout)))
    (is (= :infrastructure (sut/verdict-class :phase/timeout)))
    (is (= :no-op (sut/verdict-class :implement/empty-diff)))
    (is (= :no-op (sut/verdict-class :release/zero-files)))
    (is (= :work-terminal (sut/verdict-class :stagnated)))
    (is (= :work-terminal (sut/verdict-class :exhausted))))
  (testing "anything else (ordinary actionable failure) is :work"
    (is (= :work (sut/verdict-class :repair-requested)))
    (is (= :work (sut/verdict-class :rejected)))
    (is (= :work (sut/verdict-class nil)))))

(deftest verdict-terminal?-unchanged
  (testing "guard still fires for every terminal verdict, not for :work ones"
    (is (true? (sut/verdict-terminal? {} {:phase/verdict :stagnated})))
    (is (true? (sut/verdict-terminal? {} {:phase/verdict :verify/timeout})))
    (is (true? (sut/verdict-terminal? {} {:phase/verdict :phase/timeout})))
    (is (false? (sut/verdict-terminal? {} {:phase/verdict :repair-requested})))
    (is (false? (sut/verdict-terminal? {} {:phase/verdict nil})))))

(deftest verdict-infra-retriable?-test
  (testing "infra verdict + infra budget left → retriable (retry same phase)"
    ;; max-infra-retries is 1 (from EDN). count 0 < 1 → retriable.
    (is (true? (sut/verdict-infra-retriable? {:_state :p :infra-retry-count 0}
                                             {:phase/verdict :verify/timeout})))
    (is (true? (sut/verdict-infra-retriable? {:_state :p :infra-retry-count 0}
                                             {:phase/verdict :implement/rate-limited})))
    (is (true? (sut/verdict-infra-retriable? {:_state :p}
                                             {:phase/verdict :review/backend-timeout}))
        "absent counter treated as 0")
    (is (true? (sut/verdict-infra-retriable? {:_state :p :infra-retry-count 0}
                                             {:phase/verdict :phase/timeout}))
        "phase/timeout is an infra verdict — retriable when budget allows"))
  (testing "infra verdict + budget spent (>= max) → not retriable → falls to terminal"
    ;; max-infra-retries is 1 from EDN; fallback is 3 if EDN absent.
    ;; count 1 >= 1 (EDN) or count 3 >= 3 (fallback) — both spent.
    (is (false? (sut/verdict-infra-retriable? {:_state :p :infra-retry-count 3}
                                              {:phase/verdict :verify/timeout}))
        "count 3 is >= max in both EDN (1) and fallback (3) configurations")
    (is (false? (sut/verdict-infra-retriable? {:_state :p :infra-retry-count 3}
                                              {:phase/verdict :phase/timeout}))
        "phase/timeout also falls to terminal once budget is spent"))
  (testing "non-infra verdict is never infra-retriable, regardless of budget"
    (is (false? (sut/verdict-infra-retriable? {:_state :p :infra-retry-count 0}
                                              {:phase/verdict :stagnated})))
    (is (false? (sut/verdict-infra-retriable? {:_state :p :infra-retry-count 0}
                                              {:phase/verdict :implement/empty-diff})))
    (is (false? (sut/verdict-infra-retriable? {:_state :p}
                                              {:phase/verdict :repair-requested})))))
