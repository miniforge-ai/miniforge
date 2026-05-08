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

(ns ai.miniforge.workflow.anomaly.load-from-resource-test
  "Coverage for `loader/load-from-resource` (anomaly-returning) and the
   `loader/load-workflow` boundary that escalates anomalies to slingshot
   throws.

   The parse-failure path returns a `:fault` anomaly. The boundary
   `load-workflow` inlines a `response/throw-anomaly!` with the
   `:anomalies/fault` slingshot category so legacy try+ callers above
   still observe the thrown shape they depend on."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.loader :as loader])
  (:import (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ Happy path (anomaly-returning API)

(deftest load-from-resource-returns-workflow-on-success
  (testing "valid workflow EDN parses to a map (no anomaly)"
    (let [workflow (loader/load-from-resource :canonical-sdlc "2.0.0")]
      (is (some? workflow))
      (is (not (anomaly/anomaly? workflow)))
      (is (map? workflow)))))

(deftest load-from-resource-returns-nil-when-not-on-classpath
  (testing "absent resource returns plain nil (not an anomaly)"
    (let [result (loader/load-from-resource :no-such-workflow-9001 "1.0.0")]
      (is (nil? result))
      (is (not (anomaly/anomaly? result))))))

;------------------------------------------------------------------------------ Failure path (anomaly-returning API)

(deftest load-from-resource-returns-anomaly-on-parse-failure
  (testing "EDN parse failure yields a :fault anomaly with diagnostics"
    ;; Simulate an exception path in load-from-resource by redef'ing a
    ;; collaborator. We use a workflow id that does resolve on the
    ;; classpath, then make the inner read explode.
    (with-redefs [edn/read (fn [_] (throw (RuntimeException. "edn parse boom")))]
      (let [result (loader/load-from-resource :canonical-sdlc "2.0.0")]
        (is (anomaly/anomaly? result))
        (is (= :fault (:anomaly/type result)))
        (let [data (:anomaly/data result)]
          (is (= :canonical-sdlc (:workflow-id data)))
          (is (some? (:resource-path data)))
          (is (= "edn parse boom" (:error data))))))))

;------------------------------------------------------------------------------ Boundary escalation through load-workflow

(deftest load-workflow-throws-on-load-from-resource-fault
  (testing "load-workflow escalates a parse-failure anomaly to slingshot"
    (loader/clear-cache!)
    (with-redefs [loader/load-from-resource (fn [workflow-id _version]
                                              (anomaly/anomaly :fault
                                                               "boom"
                                                               {:workflow-id workflow-id
                                                                :resource-path "x"
                                                                :error "parse error"}))
                  loader/load-from-store (fn [_ _ _] nil)]
      (try
        (loader/load-workflow :anything "1.0.0" {})
        (is false "should have thrown")
        (catch ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :anomalies/fault (:anomaly/category data)))))))))
