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

(ns ai.miniforge.spec-parser.anomaly.normalize-spec-test
  "Coverage for `core/normalize-spec` (anomaly-returning) and its
   boundary escalation through `parse-spec-file`.

   The three shape checks (must-be-map, must-have-:spec/title,
   must-have-:spec/description) all classify as `:invalid-input` because
   the spec is caller-supplied. The boundary at `parse-spec-file`
   rethrows as ex-info."
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.spec-parser.core :as core]))

;------------------------------------------------------------------------------ Anomaly-returning happy path

(deftest normalize-spec-minimal-input
  (testing "minimal valid input normalizes (not an anomaly)"
    (let [result (core/normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (not (anomaly/anomaly? result)))
      (is (= "T" (:spec/title result)))
      (is (= "D" (:spec/description result))))))

;------------------------------------------------------------------------------ Anomaly-returning failure paths

(deftest normalize-spec-non-map-input
  (testing "non-map input yields :invalid-input anomaly"
    (let [result (core/normalize-spec "not a map")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= "Workflow spec must be a map" (:anomaly/message result))))))

(deftest normalize-spec-missing-title
  (testing "map without :spec/title yields :invalid-input anomaly"
    (let [result (core/normalize-spec {:spec/description "D"})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= "Workflow spec must have :spec/title" (:anomaly/message result))))))

(deftest normalize-spec-missing-description
  (testing "map without :spec/description yields :invalid-input anomaly"
    (let [result (core/normalize-spec {:spec/title "T"})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= "Workflow spec must have :spec/description" (:anomaly/message result))))))

;------------------------------------------------------------------------------ Boundary escalation

(deftest parse-spec-file-escalates-missing-title
  (testing "spec missing :spec/title surfaces as ex-info from parse-spec-file"
    (let [tmp (fs/create-temp-file {:suffix ".edn"})]
      (try
        (spit (fs/file tmp) "{:spec/description \"only desc\"}")
        (let [thrown (try
                       (core/parse-spec-file (str tmp))
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown))
          (is (= :invalid-input (:anomaly/type (ex-data thrown)))))
        (finally
          (fs/delete-if-exists tmp))))))
