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

(ns ai.miniforge.compliance-scanner.report
  "EDN delta report and markdown work-spec writers.

   Layer 0: Path helpers
   Layer 1: Report writers"
  (:require [clojure.java.io :as io]
            [clojure.pprint  :as pprint]))

;------------------------------------------------------------------------------ Layer 0
;; Path helpers

(defn- report-path
  "Return the absolute path for the EDN compliance report."
  [repo-path]
  (str repo-path "/.miniforge/compliance-report.edn"))

(defn- work-spec-path
  "Return the absolute path for the dated markdown work spec."
  [repo-path]
  (let [date-str (.format
                  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")
                  (java.time.LocalDate/now))]
    (str repo-path "/docs/compliance/" date-str "-compliance-delta.md")))

(defn- ensure-parent!
  "Create parent directories for a file path if they don't exist."
  [file-path]
  (-> (io/file file-path) .getParentFile .mkdirs))

;------------------------------------------------------------------------------ Layer 1
;; Report writers

(defn write-report!
  "Write EDN delta report to .miniforge/compliance-report.edn.

   Arguments:
   - delta-report - DeltaReport map
   - repo-path    - string path to repo root

   Returns the path written."
  [delta-report repo-path]
  (let [path (report-path repo-path)]
    (ensure-parent! path)
    (spit path (with-out-str (pprint/pprint delta-report)))
    path))

(defn write-work-spec!
  "Write markdown work spec to docs/compliance/YYYY-MM-DD-compliance-delta.md.

   Arguments:
   - work-spec - markdown string
   - repo-path - string path to repo root

   Returns the path written."
  [work-spec repo-path]
  (let [path (work-spec-path repo-path)]
    (ensure-parent! path)
    (spit path work-spec)
    path))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (write-report!
   {:repo-path      "/tmp/my-repo"
    :standards-path "/tmp/my-repo/.standards"
    :scan-timestamp "2026-04-04T00:00:00Z"
    :summary        {:total-violations 3 :auto-fixable 2
                     :needs-review 1 :files-affected 2 :rules-violated 1}
    :violations     []}
   "/tmp/my-repo")

  (write-work-spec! "# Compliance\n\nNo issues." "/tmp/my-repo")

  :leave-this-here)
