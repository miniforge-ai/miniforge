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

(ns ai.miniforge.connector-sarif.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.connector-sarif.interface :as sut]
            [ai.miniforge.connector-sarif.schema :as schema]))

;------------------------------------------------------------------------------ Layer 1
;; create-sarif-connector

(deftest create-sarif-connector-returns-record-test
  (testing "Factory returns a SarifConnector defrecord instance"
    (let [c (sut/create-sarif-connector)]
      (is (some? c))
      ;; defrecord generates a class with an underscore in its name.
      (is (instance? ai.miniforge.connector_sarif.core.SarifConnector c)))))

;------------------------------------------------------------------------------ Layer 1
;; connector-metadata shape

(deftest connector-metadata-shape-test
  (testing "Metadata declares name/type/version/capabilities/auth/maintainer"
    (let [m sut/connector-metadata]
      (is (string? (:connector/name m)))
      (is (= :source (:connector/type m)))
      (is (string? (:connector/version m)))
      (is (= #{:cap/batch} (:connector/capabilities m)))
      (is (= #{:none} (:connector/auth-methods m)))
      (is (some? (:connector/retry-policy m)))
      (is (= "data-foundry" (:connector/maintainer m))))))

;------------------------------------------------------------------------------ Layer 1
;; Schema and validation re-exports

(deftest schema-reexports-point-to-spec-test
  (testing "interface re-exports SarifViolation and SarifConfig from spec"
    (is (= schema/SarifViolation sut/SarifViolation))
    (is (= schema/SarifConfig    sut/SarifConfig))))

(deftest validate-config-passthrough-test
  (testing "validate-config delegates to schema"
    ;; Schema validates only that :sarif/source-path is a string —
    ;; use a platform-neutral source rather than a hard-coded Unix path.
    (is (true?  (:valid? (sut/validate-config
                          {:sarif/source-path (System/getProperty "java.io.tmpdir")}))))
    (is (false? (:valid? (sut/validate-config {}))))))

(deftest validate-violation-passthrough-test
  (testing "validate-violation delegates to schema"
    (is (true?  (:valid? (sut/validate-violation
                          {:violation/id          "v"
                           :violation/rule-id     "R"
                           :violation/message     "m"
                           :violation/severity    :error
                           :violation/location    {}
                           :violation/source-tool "T"
                           :violation/raw         {}}))))
    (is (false? (:valid? (sut/validate-violation {}))))))

(deftest violation-json-schema-shape-test
  (testing "violation-json-schema returns the JSON Schema for the violation map"
    (let [s (sut/violation-json-schema)]
      (is (map? s))
      (is (= "object" (:type s)))
      (is (contains? (:properties s) :violation/id)))))

(deftest config-json-schema-shape-test
  (testing "config-json-schema returns the JSON Schema for the config map"
    (let [s (sut/config-json-schema)]
      (is (map? s))
      (is (= "object" (:type s)))
      (is (contains? (:properties s) :sarif/source-path)))))
