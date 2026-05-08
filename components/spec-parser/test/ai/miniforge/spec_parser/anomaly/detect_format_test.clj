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

(ns ai.miniforge.spec-parser.anomaly.detect-format-test
  "Coverage for `core/detect-format` (anomaly-returning) and its
   boundary escalation through `parse-spec-file`.

   `detect-format` classifies an extension to a parser keyword. An
   unsupported extension is `:invalid-input` because the path is
   caller-supplied. The boundary at `parse-spec-file` rethrows the
   anomaly as ex-info so existing slingshot callers (CLI `run`,
   task-executor pre-flight) keep their exception-shaped contract."
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.spec-parser.core :as core]))

;------------------------------------------------------------------------------ Anomaly-returning happy path

(deftest detect-format-known-extensions
  (testing "supported extensions return their format keyword"
    (is (= :edn      (core/detect-format "spec.edn")))
    (is (= :json     (core/detect-format "spec.json")))
    (is (= :markdown (core/detect-format "spec.md")))
    (is (= :yaml     (core/detect-format "spec.yaml")))
    (is (= :yaml     (core/detect-format "spec.yml")))))

;------------------------------------------------------------------------------ Anomaly-returning failure path

(deftest detect-format-unsupported-returns-anomaly
  (testing "unknown extension yields :invalid-input anomaly"
    (let [result (core/detect-format "spec.toml")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))

(deftest detect-format-anomaly-data-carries-triage-info
  (testing "anomaly data carries path, extension, and supported set"
    (let [result (core/detect-format "spec.toml")
          data   (:anomaly/data result)]
      (is (= "spec.toml" (:path data)))
      (is (= "toml" (:extension data)))
      (is (contains? (:supported data) "edn")))))

;------------------------------------------------------------------------------ Boundary escalation
;;
;; `parse-spec-file` is the public escalation point. When given a path
;; that exists on disk but has an unsupported extension, the
;; `detect-format` anomaly is rethrown as ex-info carrying the
;; `:anomaly/type` for downstream classification.

(deftest parse-spec-file-escalates-unsupported-extension
  (testing "unsupported extension surfaces as ex-info from parse-spec-file"
    (let [tmp (fs/create-temp-file {:suffix ".toml"})]
      (try
        (spit (fs/file tmp) "title = \"x\"")
        (let [thrown (try
                       (core/parse-spec-file (str tmp))
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown) "expected ex-info to be thrown")
          (is (= :invalid-input (:anomaly/type (ex-data thrown)))))
        (finally
          (fs/delete-if-exists tmp))))))
