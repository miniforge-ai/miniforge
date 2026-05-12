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
  "Coverage for `tool-registry/registry` boundary escalation via
   `response/throw-anomaly!`.

   Pre-cleanup, each site was a raw `(throw (ex-info ...))`. Post-
   cleanup, throws route through the canonical
   `response/throw-anomaly!` carrying typed anomaly categories
   (`:anomalies/incorrect`, `:anomalies/not-found`)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tool-registry.registry :as registry])
  (:import
   (clojure.lang ExceptionInfo)))

(defn- new-registry []
  (registry/->AtomToolRegistry (atom {:tools (atom {}) :logger nil})))

;------------------------------------------------------------------------------ register-tool — invalid configuration

(deftest register-tool-invalid-config-throws-anomaly
  (testing "schema-invalid tool raises :anomalies/incorrect"
    (let [reg    (new-registry)
          thrown (try (registry/register-tool reg {}) nil (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"Invalid tool configuration" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

(deftest register-tool-invalid-config-carries-errors
  (testing "anomaly ex-data carries :tool-id and :errors"
    (let [reg (new-registry)
          thrown (try
                   (registry/register-tool reg {:tool/id :no/such-tool})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :no/such-tool (:tool-id (ex-data thrown))))
      (is (contains? (ex-data thrown) :errors))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

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

(deftest update-tool-not-found-throws-anomaly
  (testing "missing tool raises :anomalies/not-found"
    (let [reg (new-registry)]
      (is (thrown-with-msg?
           ExceptionInfo
           #"Tool not found"
           (registry/update-tool reg :missing/tool {:tool/name "x"}))))))

(deftest update-tool-not-found-carries-tool-id
  (testing "anomaly ex-data carries :tool-id and :anomalies/not-found category"
    (let [reg (new-registry)
          thrown (try
                   (registry/update-tool reg :missing/tool {:tool/name "x"})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :missing/tool (:tool-id (ex-data thrown))))
      (is (= :anomalies/not-found (:anomaly/category (ex-data thrown)))))))
