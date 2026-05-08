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

(ns ai.miniforge.agent.anomaly.create-meta-config-test
  "Coverage for `meta-protocol/create-meta-config` after the
   exceptions-as-data migration. The constructor now returns an
   `:invalid-input` anomaly when required fields are missing instead
   of throwing — callers branch on `anomaly/anomaly?`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.agent.meta-protocol :as mp]
            [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Happy path

(deftest create-meta-config-builds-record-when-id-and-name-present
  (testing "valid opts produce a MetaAgentConfig record (no anomaly)"
    (let [cfg (mp/create-meta-config {:id :progress-monitor
                                      :name "Progress Monitor"})]
      (is (not (anomaly/anomaly? cfg)))
      (is (= :progress-monitor (:id cfg)))
      (is (= "Progress Monitor" (:name cfg)))
      (is (true? (:can-halt? cfg)))
      (is (= 30000 (:check-interval-ms cfg)))
      (is (= :medium (:priority cfg)))
      (is (true? (:enabled? cfg))))))

(deftest create-meta-config-honors-overrides
  (testing "explicit overrides take precedence over defaults"
    (let [cfg (mp/create-meta-config {:id :test-quality
                                      :name "Test Quality"
                                      :can-halt? false
                                      :check-interval-ms 5000
                                      :priority :high
                                      :enabled? false})]
      (is (false? (:can-halt? cfg)))
      (is (= 5000 (:check-interval-ms cfg)))
      (is (= :high (:priority cfg)))
      (is (false? (:enabled? cfg))))))

;------------------------------------------------------------------------------ Failure path

(deftest create-meta-config-missing-id-returns-anomaly
  (testing "missing :id yields an :invalid-input anomaly"
    (let [result (mp/create-meta-config {:name "no id"})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= "Meta-agent config requires :id and :name"
             (:anomaly/message result)))
      (is (= {:name "no id"}
             (:provided (:anomaly/data result)))))))

(deftest create-meta-config-missing-name-returns-anomaly
  (testing "missing :name yields an :invalid-input anomaly"
    (let [result (mp/create-meta-config {:id :foo})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= {:id :foo}
             (:provided (:anomaly/data result)))))))

(deftest create-meta-config-empty-opts-returns-anomaly
  (testing "empty opts yield an :invalid-input anomaly"
    (let [result (mp/create-meta-config {})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))
