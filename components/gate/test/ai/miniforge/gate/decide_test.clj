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
(ns ai.miniforge.gate.decide-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.gate.decide :as decide]
   [ai.miniforge.policy-pack.interface :as policy-pack]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pins {:pins/pack-revision "test@1"
           :pins/rule-ids [:r/a]
           :pins/event-watermark nil})

(defn- ^{:stratum 0} v [action & [severity]]
  (cond-> {:rule {:rule/id :r/a :rule/enforcement {:action action}}
           :message "m"}
    severity (assoc-in [:rule :rule/severity] severity)))

(deftest ^{:stratum 0} missing-artifact-reason-test
  (is (= :reason/missing-artifact (:reason/code (decide/missing-artifact-reason)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} classify+decide [violations]
  (decide/decide (policy-pack/classify-violations violations) pins))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} decision-table-test
  (testing ":hard-halt denies"
    (is (= :deny (:envelope/decision (classify+decide [(v :hard-halt)])))))
  (testing ":require-approval denies with an approval obligation (1c behavior change)"
    (let [e (classify+decide [(v :require-approval)])]
      (is (= :deny (:envelope/decision e)))
      (is (= [:obligation/approval-required]
             (mapv :obligation/type (:envelope/obligations e))))))
  (testing ":warn allows with obligation"
    (let [e (classify+decide [(v :warn)])]
      (is (= :allow-with-obligations (:envelope/decision e)))
      (is (= [:obligation/warn-recorded]
             (mapv :obligation/type (:envelope/obligations e))))))
  (testing ":audit allows with obligation"
    (is (= :allow-with-obligations
           (:envelope/decision (classify+decide [(v :audit)])))))
  (testing "an off-vocabulary action DENIES - no fall-through"
    (let [e (classify+decide [(v :block)])]
      (is (= :deny (:envelope/decision e)))
      (is (= [:reason/unknown-enforcement]
             (mapv :reason/code (:envelope/reasons e))))))
  (testing "an off-scale severity DENIES"
    (let [e (classify+decide [(v :warn :wobbly)])]
      (is (= :deny (:envelope/decision e)))
      (is (= [:reason/unknown-severity]
             (mapv :reason/code (:envelope/reasons e))))))
  (testing "no violations: allow, pins carried"
    (let [e (classify+decide [])]
      (is (= :allow (:envelope/decision e)))
      (is (= "test@1" (get-in e [:envelope/pins :pins/pack-revision])))))
  (testing "worst wins across a mixed set"
    (is (= :deny (:envelope/decision
                  (classify+decide [(v :audit) (v :warn) (v :hard-halt)]))))))

(deftest ^{:stratum 2} allowed?-test
  (is (decide/allowed? (classify+decide [(v :warn)])))
  (is (not (decide/allowed? (classify+decide [(v :hard-halt)])))))
