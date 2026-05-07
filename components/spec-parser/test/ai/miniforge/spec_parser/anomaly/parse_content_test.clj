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

(ns ai.miniforge.spec-parser.anomaly.parse-content-test
  "Coverage for `core/parse-content` (anomaly-returning) and its
   boundary escalation through `parse-spec-file`.

   `parse-content` dispatches on a known format keyword. Reaching the
   unknown-format branch is exhaustive — `detect-format` only emits
   formats with parsers — so it is `:fault` (programmer error in the
   registry), not `:invalid-input`. `parse-yaml` is a separate
   `:unsupported` anomaly because YAML is reachable through the normal
   detect-format path until YAML support lands."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.spec-parser.core :as core]))

;------------------------------------------------------------------------------ Anomaly-returning happy path

(deftest parse-content-known-format
  (testing "known format dispatches to its parser"
    (is (= {:a 1} (core/parse-content :edn "{:a 1}")))))

;------------------------------------------------------------------------------ Anomaly-returning failure paths

(deftest parse-content-unknown-format-returns-fault
  (testing "unknown format yields :fault anomaly (registry-level programmer error)"
    (let [result (core/parse-content :toml "[x]")]
      (is (anomaly/anomaly? result))
      (is (= :fault (:anomaly/type result)))
      (is (contains? (set (get-in result [:anomaly/data :available])) :edn)))))

(deftest parse-content-yaml-returns-unsupported
  (testing "YAML dispatches to parse-yaml which returns :unsupported anomaly"
    (let [result (core/parse-content :yaml "title: T\n")]
      (is (anomaly/anomaly? result))
      (is (= :unsupported (:anomaly/type result))))))

(deftest parse-content-malformed-edn-returns-anomaly
  (testing "format-level dispatch surfaces parser anomalies (not wrapped)"
    (let [result (core/parse-content :edn "{:a 1")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))

;------------------------------------------------------------------------------ Boundary escalation
;;
;; `parse-content`'s :fault branch is unreachable through `parse-spec-file`
;; (every keyword `detect-format` emits has a registered parser), but
;; `parse-yaml`'s :unsupported anomaly *is* reachable: a `.yaml` file
;; on disk routes through detect-format → parse-content → parse-yaml.

(deftest parse-spec-file-escalates-yaml-unsupported
  (testing "yaml content surfaces as ex-info from parse-spec-file"
    (let [tmp (babashka.fs/create-temp-file {:suffix ".yaml"})]
      (try
        (spit (babashka.fs/file tmp) "title: T\n")
        (let [thrown (try
                       (core/parse-spec-file (str tmp))
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown))
          (is (= :unsupported (:anomaly/type (ex-data thrown)))))
        (finally
          (babashka.fs/delete-if-exists tmp))))))
