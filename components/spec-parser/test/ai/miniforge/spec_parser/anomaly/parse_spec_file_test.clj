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
(ns ai.miniforge.spec-parser.anomaly.parse-spec-file-test
  "Coverage for `core/parse-spec-file` (boundary fn) — file-not-found
   path and the boundary escalation contract.

   `parse-spec-file` is the public escalation point for the spec-parser
   component. Layer 0-4 fns return anomalies; this fn rethrows them
   as ex-info so existing slingshot callers (CLI `run`, task-executor
   pre-flight) keep their exception-shaped contract.

   File-not-found is `:not-found` (caller-supplied path that does not
   resolve). All anomalies escalated by `parse-spec-file` carry the
   original `:anomaly/type` in their ex-data for downstream
   classification."
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [ai.miniforge.spec-parser.core :as core]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Happy path
(deftest ^{:stratum 0} parse-spec-file-valid-edn
  (testing "valid EDN spec parses and normalizes"
    (let [tmp (fs/create-temp-file {:suffix ".edn"})]
      (try
        (spit (fs/file tmp) "{:spec/title \"T\" :spec/description \"D\"}")
        (let [result (core/parse-spec-file (str tmp))]
          (is (= "T" (:spec/title result)))
          (is (= "D" (:spec/description result)))
          (is (= :edn (get-in result [:spec/provenance :source-format]))))
        (finally
          (fs/delete-if-exists tmp))))))

;------------------------------------------------------------------------------ File-not-found anomaly + boundary escalation
(defn- ^{:stratum 0} nonexistent-path
  "Generate a path under the system temp dir that is guaranteed not to
   exist. Avoids `/tmp/...` hardcodes that can be flaky if the file
   happens to be present on the test machine."
  [suffix]
  (str (fs/path (fs/temp-dir)
                (str "spec-parser-anomaly-" (random-uuid) "-" suffix))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} parse-spec-file-not-found-escalates
  (testing "missing path surfaces as :not-found ex-info"
    (let [path   (nonexistent-path "missing.edn")
          thrown (try
                   (core/parse-spec-file path)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :not-found (:anomaly/type (ex-data thrown))))
      (is (re-find #"Spec file not found" (ex-message thrown))))))

(deftest ^{:stratum 1} parse-spec-file-ex-data-carries-anomaly-type
  (testing "every escalated anomaly tags ex-data with :anomaly/type"
    (let [path   (nonexistent-path "ex-data.edn")
          thrown (try
                   (core/parse-spec-file path)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :not-found (:anomaly/type (ex-data thrown))))
      (is (= path (:path (ex-data thrown)))))))
