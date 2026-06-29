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

(ns ai.miniforge.tool-registry.anomaly.registry-anomaly-test
  "Coverage for `tool-registry/registry` anomaly behavior.

   Validation/not-found failures return anomaly maps. Hard registry shape
   invariants still throw typed anomalies."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tool-registry.registry :as registry])
  (:import
   (clojure.lang ExceptionInfo)))

(defn- new-registry []
  (registry/->AtomToolRegistry (atom {:tools (atom {}) :logger nil})))

;------------------------------------------------------------------------------ register-tool — invalid configuration

(deftest register-tool-invalid-config-returns-anomaly
  (testing "schema-invalid tool returns :invalid-input / :anomalies/incorrect"
    (let [reg    (new-registry)
          result (registry/register-tool reg {})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= :anomalies/incorrect (:anomaly/subtype result)))
      (is (= "Invalid tool configuration" (:anomaly/message result))))))

(deftest register-tool-invalid-config-carries-errors
  (testing "anomaly data carries :tool-id and schema errors"
    (let [reg    (new-registry)
          result (registry/register-tool reg {:tool/id :no/such-tool})]
      (is (= :no/such-tool (get-in result [:anomaly/data :tool-id])))
      (is (contains? (:anomaly/data result) :errors)))))

;------------------------------------------------------------------------------ register-tool — invalid tool id shape

(deftest register-tool-invalid-id-shape-throws-anomaly
  (testing "non-namespaced tool-id raises :anomalies/incorrect"
    ;; Provide a minimally schema-valid tool so schema/validate-tool
    ;; passes and the id-shape guard is what fires.
    (let [reg    (new-registry)
          tool   {:tool/id :not-namespaced :tool/type :function :tool/name "test"}
          thrown (try (registry/register-tool reg tool) nil (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"Tool ID must be a namespaced keyword" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

;------------------------------------------------------------------------------ update-tool — tool not found

(deftest update-tool-not-found-returns-anomaly
  (testing "missing tool returns :not-found / :anomalies/not-found"
    (let [reg    (new-registry)
          result (registry/update-tool reg :missing/tool {:tool/name "x"})]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= :anomalies/not-found (:anomaly/subtype result)))
      (is (= {:tool-id :missing/tool} (:anomaly/data result))))))

(deftest update-tool-not-found-carries-tool-id
  (testing "anomaly data carries :tool-id"
    (let [reg    (new-registry)
          result (registry/update-tool reg :missing/tool {:tool/name "x"})]
      (is (= :missing/tool (get-in result [:anomaly/data :tool-id]))))))

;------------------------------------------------------------------------------ update-tool — invalid update

(deftest update-tool-invalid-update-returns-anomaly
  (testing "merged invalid config returns :invalid-input / :anomalies/incorrect"
    (let [reg  (new-registry)
          tool {:tool/id :tools/greet
                :tool/type :function
                :tool/name "Greet"}]
      (registry/register-tool reg tool)
      (let [result (registry/update-tool reg :tools/greet {:tool/type :unknown})]
        (is (anomaly/anomaly? result))
        (is (= :invalid-input (:anomaly/type result)))
        (is (= :anomalies/incorrect (:anomaly/subtype result)))
        (is (= :tools/greet (get-in result [:anomaly/data :tool-id])))
        (is (contains? (:anomaly/data result) :errors))))))

(deftest update-tool-id-change-returns-anomaly
  (testing "updates cannot change the registry key's tool id"
    (let [reg  (new-registry)
          tool {:tool/id :tools/greet
                :tool/type :function
                :tool/name "Greet"}]
      (registry/register-tool reg tool)
      (let [result (registry/update-tool reg :tools/greet {:tool/id :tools/renamed})]
        (is (anomaly/anomaly? result))
        (is (= :invalid-input (:anomaly/type result)))
        (is (= :anomalies/incorrect (:anomaly/subtype result)))
        (is (= "Tool ID cannot be changed" (:anomaly/message result)))
        (is (= {:tool-id :tools/greet
                :requested-tool-id :tools/renamed}
               (:anomaly/data result)))))))
