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

(ns ai.miniforge.policy-pack.anomaly.registry-anomaly-test
  "Coverage for `policy-pack/registry` anomaly behavior.

   Validation/not-found failures return anomaly maps. Unsupported and
   intentionally unimplemented surfaces still throw typed anomalies."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.policy-pack.registry :as registry])
  (:import
   (clojure.lang ExceptionInfo)))

(defn- new-registry []
  (registry/->InMemoryPackRegistry (atom {:packs {}})))

;------------------------------------------------------------------------------ register-pack — invalid schema

(deftest register-pack-invalid-schema-returns-anomaly
  (testing "schema-invalid pack returns :invalid-input / :anomalies/incorrect"
    (let [reg    (new-registry)
          result (registry/register-pack reg {:pack/id :bad-pack})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= :anomalies/incorrect (:anomaly/subtype result)))
      (is (= "Invalid pack schema" (:anomaly/message result))))))

(deftest register-pack-anomaly-carries-pack-id
  (testing "anomaly data carries :pack-id and schema errors"
    (let [reg    (new-registry)
          result (registry/register-pack reg {:pack/id :bad})]
      (is (= :bad (get-in result [:anomaly/data :pack-id])))
      (is (contains? (:anomaly/data result) :errors)))))

;------------------------------------------------------------------------------ import-pack — unsupported source

(deftest import-pack-string-source-throws-unsupported
  (testing "string source raises :anomalies/unsupported"
    (let [reg    (new-registry)
          thrown (try (registry/import-pack reg "/path/to/pack.edn") nil (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"File/URL import not implemented" (.getMessage thrown)))
      (is (= :anomalies/unsupported (:anomaly/category (ex-data thrown)))))))

;------------------------------------------------------------------------------ export-pack — pack not found

(deftest export-pack-not-found-returns-anomaly
  (testing "missing pack returns :not-found / :anomalies/not-found"
    (let [reg    (new-registry)
          result (registry/export-pack reg :missing "1.0.0" :edn)]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= :anomalies/not-found (:anomaly/subtype result)))
      (is (= {:pack-id :missing :version "1.0.0"} (:anomaly/data result))))))

;------------------------------------------------------------------------------ export-pack — unsupported / unknown formats

(deftest export-pack-json-unsupported
  (testing "JSON export raises :anomalies/unsupported for present pack"
    (let [reg (new-registry)
          pack {:pack/id :p1
                :pack/version "1.0.0"
                :pack/name "p"
                :pack/description "d"
                :pack/author "a"
                :pack/rules []}]
      (swap! (.state reg) assoc-in [:packs :p1 "1.0.0"] pack)
      (is (thrown-with-msg?
           ExceptionInfo
           #"JSON export not implemented"
           (registry/export-pack reg :p1 "1.0.0" :json))))))

(deftest export-pack-directory-unsupported
  (testing "directory export raises :anomalies/unsupported for present pack"
    (let [reg (new-registry)
          pack {:pack/id :p1
                :pack/version "1.0.0"
                :pack/name "p"
                :pack/description "d"
                :pack/author "a"
                :pack/rules []}]
      (swap! (.state reg) assoc-in [:packs :p1 "1.0.0"] pack)
      (is (thrown-with-msg?
           ExceptionInfo
           #"Directory export not implemented"
           (registry/export-pack reg :p1 "1.0.0" :directory))))))

(deftest export-pack-unknown-format-incorrect
  (testing "unknown format raises :anomalies/incorrect for present pack"
    (let [reg (new-registry)
          pack {:pack/id :p1
                :pack/version "1.0.0"
                :pack/name "p"
                :pack/description "d"
                :pack/author "a"
                :pack/rules []}]
      (swap! (.state reg) assoc-in [:packs :p1 "1.0.0"] pack)
      (is (thrown-with-msg?
           ExceptionInfo
           #"Unknown export format"
           (registry/export-pack reg :p1 "1.0.0" :bogus))))))
