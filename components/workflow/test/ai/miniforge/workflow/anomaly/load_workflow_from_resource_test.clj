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

(ns ai.miniforge.workflow.anomaly.load-workflow-from-resource-test
  "Coverage for `registry/load-workflow-from-resource` (anomaly-returning)
   and the `discover-workflows-from-resources` consumer that filters
   anomalies out of its sequence rather than letting one bad resource
   abort discovery."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.registry :as registry]))

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest load-workflow-from-resource-returns-map-on-success
  (testing "existing workflow EDN resource parses to a non-anomaly map"
    (let [result (registry/load-workflow-from-resource "workflows/canonical-sdlc-v2.0.0.edn")]
      (is (some? result))
      (is (not (anomaly/anomaly? result)))
      (is (map? result)))))

(deftest load-workflow-from-resource-returns-nil-when-resource-absent
  (testing "absent resource returns nil (no anomaly)"
    (is (nil? (registry/load-workflow-from-resource "workflows/no-such-workflow-9001.edn")))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest load-workflow-from-resource-returns-anomaly-on-parse-failure
  (testing "EDN parse failure yields a :fault anomaly"
    (with-redefs [edn/read-string (fn [_] (throw (RuntimeException. "edn boom")))]
      (let [result (registry/load-workflow-from-resource "workflows/canonical-sdlc-v2.0.0.edn")]
        (is (anomaly/anomaly? result))
        (is (= :fault (:anomaly/type result)))
        (let [data (:anomaly/data result)]
          (is (= "workflows/canonical-sdlc-v2.0.0.edn" (:resource-path data)))
          (is (= "edn boom" (:error data))))))))

;------------------------------------------------------------------------------ Discovery filters out anomalies

(deftest discover-workflows-skips-anomalies
  (testing "discover-workflows-from-resources removes anomaly results from its seq"
    (let [bad-anomaly (anomaly/anomaly :fault "broken" {:resource-path "x"})]
      (with-redefs [registry/list-resource-names (fn [_] ["one.edn" "two.edn"])
                    registry/load-workflow-from-resource
                    (fn [path]
                      (case path
                        "workflows/one.edn" {:workflow/id :one}
                        "workflows/two.edn" bad-anomaly))]
        (let [result (registry/discover-workflows-from-resources)]
          (is (= 1 (count result)))
          (is (= :one (:workflow/id (first result))))
          (is (every? (complement anomaly/anomaly?) result)))))))
