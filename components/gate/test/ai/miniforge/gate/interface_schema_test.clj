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
(ns ai.miniforge.gate.interface-schema-test
  "Under instrumentation, the gate interface rejects a gate result that
   is in neither accepted shape, instead of reading it as failed."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.gate.registry :as registry]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.schema.interface :as schema]))

(use-fixtures :once (schema/instrument-fixture 'ai.miniforge.gate.interface))

;------------------------------------------------------------------------------ Layer 0

(defmethod ^{:stratum 0} registry/get-gate ::shapeless
  [_]
  {:name ::shapeless
   :check (fn [_artifact _ctx] {:verdict :ok})
   :repair (fn [_artifact _errors _ctx] {:repaired true})})

(defmethod ^{:stratum 0} registry/get-gate ::response-shaped
  [_]
  {:name ::response-shaped
   :check (fn [artifact _ctx] (response/success artifact))
   :repair (fn [artifact _errors _ctx] (response/success artifact))})

(defn ^{:stratum 0} thrown-type
  "The ex-data :type of the ex-info `f` throws."
  [f]
  (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (f))))))

(defmethod ^{:stratum 0} registry/get-gate ::failure-shaped
  [_]
  {:name ::failure-shaped
   :check (fn [_artifact _ctx] (response/failure "not this time"))
   :repair (fn [_artifact _errors _ctx] (response/failure "cannot repair"))})

(deftest ^{:stratum 0} response-shaped-results-are-accepted-test
  (testing "response/success maps are an accepted result shape"
    (is (gate/passed? (gate/check-gate ::response-shaped {:content "x"} {})))
    (is (true? (:passed? (gate/check-gates [::response-shaped] {:content "x"} {}))))
    (is (response/success? (gate/repair-gate ::response-shaped {} [] {})))))

(deftest ^{:stratum 0} response-failure-results-are-accepted-test
  (testing "response/failure maps ({:success false}, no :status) are an accepted result shape"
    (is (gate/failed? (gate/check-gate ::failure-shaped {} {})))
    (is (false? (:success (gate/repair-gate ::failure-shaped {} [] {})))))
  (testing "the behavioral gate's repair, which returns response/failure, passes the schema"
    (let [result (gate/repair-gate :behavioral {:event-stream/events []}
                                   [{:type :behavioral-violation :message "bad"}]
                                   {:phase :observe})]
      (is (false? (:success result)))
      (is (= :behavioral (:gate result))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} shapeless-check-result-is-rejected-test
  (testing "a check result with neither :passed? nor :status breaks check-gate's schema"
    (is (= :malli.core/invalid-output (thrown-type #(gate/check-gate ::shapeless {} {})))))
  (testing "check-gates surfaces the same break instead of a failed verdict"
    (is (= :malli.core/invalid-output (thrown-type #(gate/check-gates [::shapeless] {} {}))))))

(deftest ^{:stratum 1} shapeless-repair-result-is-rejected-test
  (testing "a repair result with neither :success? nor :status breaks repair-gate's schema"
    (is (= :malli.core/invalid-output (thrown-type #(gate/repair-gate ::shapeless {} [] {}))))))

(deftest ^{:stratum 1} gate-keyword-is-checked-test
  (testing "a gate named by string instead of keyword breaks check-gate's schema"
    (is (= :malli.core/invalid-input (thrown-type #(gate/check-gate "syntax" {} {}))))))
