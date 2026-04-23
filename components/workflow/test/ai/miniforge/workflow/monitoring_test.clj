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

(ns ai.miniforge.workflow.monitoring-test
  (:require
   [ai.miniforge.workflow.monitoring :as monitoring]
   [clojure.test :refer [deftest is testing]]))

(deftest create-supervisors-defaults-test
  (testing "create-supervisors falls back to the default progress monitor"
    (let [supervisors (monitoring/create-supervisors {:workflow/id :test})]
      (is (= 1 (count supervisors))))))

(deftest create-supervisors-invalid-id-test
  (testing "create-supervisors fails fast on unsupported supervisor ids"
    (let [workflow {:workflow/id :test
                    :workflow/meta-agents [{:id :unknown-supervisor}]}
          result (try
                   (monitoring/create-supervisors workflow)
                   nil
                   (catch clojure.lang.ExceptionInfo ex
                     ex))]
      (is (instance? clojure.lang.ExceptionInfo result))
      (let [data (ex-data result)]
        (is (= :anomalies.workflow/invalid-supervisor
               (:anomaly/category data)))
        (is (= :unknown-supervisor
               (:supervisor/id data)))))))
