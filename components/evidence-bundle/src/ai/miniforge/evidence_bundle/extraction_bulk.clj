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
(ns ai.miniforge.evidence-bundle.extraction-bulk
  "Bulk artifact extraction, split out of `extraction` (rule 210: a
   fourth real layer there is the signal to split it).

   Layer 0: Bulk extraction across every file in an artifact
   Layer 1: Load-from-file + extract convenience wrapper"
  (:require
   [ai.miniforge.evidence-bundle.extraction :as extraction]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} extract-files
  "Extract multiple files from artifact and write to disk.

   Artifact should be a map containing:
   - :code/files - Vector of file maps (see extraction/extract-file for structure)

   Returns map with:
   - :total - Total number of files processed
   - :successful - Number of successful operations
   - :failed - Number of failed operations
   - :results - Vector of individual file results
   - :summary - Summary string from artifact (if present)

   Example:
     (extract-files {:code/files [{:path \"src/foo.clj\"
                                   :content \"(ns foo)\"
                                   :action :create}
                                  {:path \"src/bar.clj\"
                                   :content \"(ns bar)\"
                                   :action :modify}]
                     :code/summary \"Added foo and updated bar\"})"
  [artifact]
  (let [files (:code/files artifact)
        results (mapv extraction/extract-file files)
        successful (count (filter :success results))
        failed (count (remove :success results))]
    {:total (count files)
     :successful successful
     :failed failed
     :results results
     :summary (:code/summary artifact)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} extract-artifact-from-file
  "Load artifact from file and extract all files to disk.

   This is a convenience function that combines extraction/load-artifact
   and extract-files.

   Arguments:
   - artifact-path: Path to EDN file containing artifact

   Returns extraction results map (see extract-files).

   Example:
     (extract-artifact-from-file \"/tmp/artifact.edn\")"
  [artifact-path]
  (let [artifact (extraction/load-artifact artifact-path)]
    (extract-files artifact)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Extract a single file
  (extraction/extract-file {:path "test.txt"
                            :content "Hello, world!"
                            :action :create})

  ;; Extract multiple files from artifact
  (def artifact
    {:code/files [{:path "src/foo.clj"
                   :content "(ns foo)"
                   :action :create}
                  {:path "src/bar.clj"
                   :content "(ns bar)"
                   :action :modify}]
     :code/summary "Added foo and updated bar"})

  (extract-files artifact)

  ;; Load and extract from file
  (extract-artifact-from-file "/tmp/artifact.edn")

  ;; Validate artifact
  (extraction/validate-artifact artifact)
  (extraction/validate-artifact {})

  :end)
