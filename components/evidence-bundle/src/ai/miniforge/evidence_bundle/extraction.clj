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
(ns ai.miniforge.evidence-bundle.extraction
  "Utilities for extracting and materializing artifacts from evidence bundles.
   Handles writing code artifacts to disk. Bulk extraction
   (extract-files) and the load+extract convenience wrapper live in
   `extraction-bulk` (rule 210: a fourth real layer here is the signal
   to split it).

   Layer 0: File write/delete/load primitives + validation error helper
   Layer 1: Single-file extraction + artifact validation"
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

;; File Operations
(defn ^{:stratum 0} write-file
  "Write content to a file path.
   Creates parent directories if needed.

   Returns: {:path path :action action :success true/false :error optional}"
  [path content action]
  (try
    (io/make-parents path)
    (spit path content)
    {:path path :action action :success true}
    (catch Exception e
      {:path path :action action :success false :error (.getMessage e)})))

(defn ^{:stratum 0} delete-file-safe
  "Delete a file if it exists.

   Returns: {:path path :action :delete :success true/false :error optional}"
  [path]
  (try
    (io/delete-file path true)
    {:path path :action :delete :success true}
    (catch Exception e
      {:path path :action :delete :success false :error (.getMessage e)})))

;; Artifact Loading and Extraction
(defn ^{:stratum 0} load-artifact
  "Load artifact from an EDN file.

   Arguments:
   - artifact-path: Path to EDN file containing artifact

   Returns artifact map or throws exception if file cannot be read.

   Example:
     (load-artifact \"/tmp/artifact.edn\")"
  [artifact-path]
  (-> artifact-path slurp edn/read-string))

;; Validation
(defn ^{:stratum 0} add-error
  "Add an error to the errors vector if condition is true."
  [errors condition message]
  (if condition
    (conj errors message)
    errors))

;------------------------------------------------------------------------------ Layer 1

;; Artifact File Extraction
(defn ^{:stratum 1} extract-file
  "Extract a single file from artifact and write to disk.

   File map should contain:
   - :path - File path to write
   - :content - File content
   - :action - One of :create, :modify, or :delete

   Returns map with:
   - :path - The file path
   - :action - The action taken
   - :success - true/false
   - :error - Error message if failed

   Examples:
     (extract-file {:path \"src/foo.clj\"
                    :content \"(ns foo)\"
                    :action :create})

     (extract-file {:path \"src/old.clj\"
                    :action :delete})"
  [{:keys [path content action]}]
  (case action
    (:create :modify) (write-file path content action)
    :delete (delete-file-safe path)
    {:path path :action action :success false
     :error (str "Unknown action: " action)}))

(defn ^{:stratum 1} validate-artifact
  "Validate that an artifact has the required structure for extraction.

   Returns map with:
   - :valid? - true if artifact is valid
   - :errors - Vector of error messages (if invalid)

   Example:
     (validate-artifact {:code/files [...]})
     => {:valid? true}

     (validate-artifact {})
     => {:valid? false
         :errors [\"Missing :code/files\"]}"
  [artifact]
  (let [errors (-> []
                   (add-error (not (map? artifact))
                             "Artifact must be a map")
                   (add-error (and (map? artifact)
                                   (not (contains? artifact :code/files)))
                             "Missing :code/files")
                   (add-error (and (map? artifact)
                                   (contains? artifact :code/files)
                                   (not (vector? (:code/files artifact))))
                             ":code/files must be a vector"))]
    (response/validation-result errors)))
