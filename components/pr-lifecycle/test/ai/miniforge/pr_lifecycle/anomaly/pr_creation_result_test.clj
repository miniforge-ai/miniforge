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

(ns ai.miniforge.pr-lifecycle.anomaly.pr-creation-result-test
  "Coverage for `controller/pr-creation-result` (anomaly-returning).

   PR-creation failure (DAG err) returns a `:fault` anomaly. The
   boundary site `run-lifecycle!` consumes the anomaly and rethrows
   via `response/throw-anomaly!` with `:anomalies/fault`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.controller :as controller]))

;------------------------------------------------------------------------------ Happy path

(deftest pr-creation-result-returns-ok-on-success
  (testing "successful DAG result returns :ok"
    (is (= :ok (controller/pr-creation-result (dag/ok {:pr/id "PR-1"}))))))

;------------------------------------------------------------------------------ Failure path

(deftest pr-creation-result-returns-anomaly-on-dag-err
  (testing "DAG err result returns :fault anomaly"
    (let [pr-result (dag/err :network "connection refused")
          result (controller/pr-creation-result pr-result)]
      (is (anomaly/anomaly? result))
      (is (= :fault (:anomaly/type result)))
      (is (some? (:error (:anomaly/data result))))
      (is (= "PR creation failed" (:anomaly/message result))))))

(deftest pr-creation-result-carries-error-detail
  (testing ":anomaly/data carries the underlying DAG error"
    (let [pr-result (dag/err :auth "token expired")
          result (controller/pr-creation-result pr-result)]
      (is (= :auth (get-in result [:anomaly/data :error :code])))
      (is (= "token expired" (get-in result [:anomaly/data :error :message]))))))
