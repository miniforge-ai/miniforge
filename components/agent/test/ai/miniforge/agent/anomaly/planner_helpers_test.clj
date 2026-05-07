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

(ns ai.miniforge.agent.anomaly.planner-helpers-test
  "Coverage for the planner's anomaly-returning helpers introduced
   during the exceptions-as-data migration. The boundary throws
   inside `create-planner` still escalate via slingshot under the
   agent taxonomy, but the underlying decisions are now testable as
   plain data."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.agent.planner :as planner]
            [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ parsed-plan-or-anomaly

(deftest parsed-plan-or-anomaly-returns-submitted-when-present
  (testing "submitted plan wins over parsed and anomaly"
    (let [submitted {:plan/id (random-uuid) :plan/name "submitted"}
          parsed    {:plan/id (random-uuid) :plan/name "parsed"}
          result    (planner/parsed-plan-or-anomaly submitted parsed
                                                    {:content "irrelevant"})]
      (is (= submitted result))
      (is (not (anomaly/anomaly? result))))))

(deftest parsed-plan-or-anomaly-falls-back-to-parsed
  (testing "parsed plan returned when submitted is nil"
    (let [parsed {:plan/id (random-uuid) :plan/name "parsed"}
          result (planner/parsed-plan-or-anomaly nil parsed
                                                 {:content "irrelevant"})]
      (is (= parsed result))
      (is (not (anomaly/anomaly? result))))))

(deftest parsed-plan-or-anomaly-returns-fault-when-both-nil
  (testing "neither submitted nor parsed → :fault anomaly with content preview"
    (let [llm-response {:content "this content describes a plan but cannot be parsed as EDN"
                        :stop-reason :max-turns
                        :num-turns 12}
          result (planner/parsed-plan-or-anomaly nil nil llm-response)]
      (is (anomaly/anomaly? result))
      (is (= :fault (:anomaly/type result)))
      (is (= "Plan generation failed: EDN parse did not succeed"
             (:anomaly/message result)))
      (let [data (:anomaly/data result)]
        (is (= :plan (:phase data)))
        (is (nil? (:parse-result data)))
        (is (= :max-turns (:stop-reason data)))
        (is (= 12 (:num-turns data)))
        (is (pos-int? (:llm-content-length data)))
        (is (string? (:llm-content-preview data)))))))

(deftest parsed-plan-or-anomaly-truncates-long-previews-to-500
  (testing "preview is bounded by 500 chars"
    (let [content (apply str (repeat 1000 \x))
          result  (planner/parsed-plan-or-anomaly nil nil {:content content})]
      (is (anomaly/anomaly? result))
      (is (= 500 (count (:llm-content-preview (:anomaly/data result))))))))

;------------------------------------------------------------------------------ require-llm-client-or-anomaly

(deftest require-llm-client-or-anomaly-returns-client-when-present
  (testing "non-nil client passes through"
    (let [client {:llm/backend :anthropic}
          result (planner/require-llm-client-or-anomaly client)]
      (is (= client result))
      (is (not (anomaly/anomaly? result))))))

(deftest require-llm-client-or-anomaly-returns-invalid-input-when-nil
  (testing "nil client → :invalid-input anomaly carrying :phase :plan"
    (let [result (planner/require-llm-client-or-anomaly nil)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= "No LLM backend provided for planner agent"
             (:anomaly/message result)))
      (is (= :plan (:phase (:anomaly/data result)))))))

(deftest require-llm-client-or-anomaly-treats-false-as-missing
  (testing "false (returned by some client resolvers) → anomaly"
    (let [result (planner/require-llm-client-or-anomaly false)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))
