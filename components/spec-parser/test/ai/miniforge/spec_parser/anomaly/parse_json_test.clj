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

(ns ai.miniforge.spec-parser.anomaly.parse-json-test
  "Coverage for `core/parse-json` (anomaly-returning) and its boundary
   escalation through `parse-spec-file`."
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.spec-parser.core :as core]))

;------------------------------------------------------------------------------ Anomaly-returning happy path

(deftest parse-json-valid-content
  (testing "valid JSON parses to keywordized map"
    (is (= {:title "T"} (core/parse-json "{\"title\": \"T\"}")))))

;------------------------------------------------------------------------------ Anomaly-returning failure path

(deftest parse-json-malformed-returns-anomaly
  (testing "malformed JSON yields :invalid-input anomaly"
    (let [result (core/parse-json "{not json}")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= "Failed to parse JSON file" (:anomaly/message result))))))

;------------------------------------------------------------------------------ Boundary escalation

(deftest parse-spec-file-escalates-malformed-json
  (testing "malformed JSON surfaces as ex-info from parse-spec-file"
    (let [tmp (fs/create-temp-file {:suffix ".json"})]
      (try
        (spit (fs/file tmp) "{not json}")
        (let [thrown (try
                       (core/parse-spec-file (str tmp))
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown))
          (is (= :invalid-input (:anomaly/type (ex-data thrown)))))
        (finally
          (fs/delete-if-exists tmp))))))
