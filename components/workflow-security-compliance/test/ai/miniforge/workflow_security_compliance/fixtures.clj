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

(ns ai.miniforge.workflow-security-compliance.fixtures
  (:require
   [ai.miniforge.workflow-security-compliance.phases :as phases]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Test constants

(def ^:private fixtures-dir
  "test/ai/miniforge/workflow_security_compliance/fixture_files")

(def sarif-violation-count
  5)

(def csv-violation-count
  2)

(def total-violation-count
  (+ sarif-violation-count csv-violation-count))

(defn fixture-path
  [filename]
  (str fixtures-dir "/" filename))

(defn create-output-dir
  [prefix]
  (-> (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))
      .toFile
      .getAbsolutePath))

(defn base-ctx
  ([] (base-ctx [(fixture-path "sample-scan.sarif")
                 (fixture-path "sample-scan.csv")]))
  ([scan-paths]
   {:execution/id (random-uuid)
    :execution/input {:scan-paths scan-paths
                      :output-dir (create-output-dir "miniforge-sec-")}
    :execution/metrics {:duration-ms 0}
    :execution/phase-results {}}))

(defn- finish-phase
  [ctx leave-fn]
  (-> ctx
      (assoc-in [:phase :started-at] (System/currentTimeMillis))
      leave-fn))

(defn parsed-ctx
  ([] (parsed-ctx (base-ctx)))
  ([ctx]
   (-> ctx
       phases/enter-sec-parse-scan
       (finish-phase phases/leave-sec-parse-scan))))

(defn traced-ctx
  ([] (traced-ctx (base-ctx)))
  ([ctx]
   (-> ctx
       parsed-ctx
       phases/enter-sec-trace-source
       (finish-phase phases/leave-sec-trace-source))))

(defn verified-ctx
  ([] (verified-ctx (base-ctx)))
  ([ctx]
   (-> ctx
       traced-ctx
       phases/enter-sec-verify-docs
       (finish-phase phases/leave-sec-verify-docs))))

(defn classified-ctx
  ([] (classified-ctx (base-ctx)))
  ([ctx]
   (-> ctx
       verified-ctx
       phases/enter-sec-classify
       (finish-phase phases/leave-sec-classify))))

(defn exclusions-ctx
  ([] (exclusions-ctx (base-ctx)))
  ([ctx]
   (-> ctx
       classified-ctx
       phases/enter-sec-generate-exclusions
       (finish-phase phases/leave-sec-generate-exclusions))))

(defn exclusions-file
  [output-dir]
  (io/file output-dir ".security-exclusions" "exclusions.edn"))
