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

(ns ai.miniforge.self-healing.workaround-registry
  "Persistent registry of known vendor bug workarounds.
   Storage: ~/.miniforge/known_workarounds.edn"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;;------------------------------------------------------------------------------ Layer 0
;; File paths and utilities

(defn workaround-registry-path
  "Get path to workaround registry file.

   Returns: String path to ~/.miniforge/known_workarounds.edn"
  []
  (let [home (System/getProperty "user.home")
        miniforge-dir (io/file home ".miniforge")]
    (.getPath (io/file miniforge-dir "known_workarounds.edn"))))

(defn ensure-directory-exists
  "Ensure parent directory exists for a file path.

   Arguments:
     file-path - String path to file

   Returns: nil"
  [file-path]
  (let [parent-dir (.getParentFile (io/file file-path))]
    (when-not (.exists parent-dir)
      (.mkdirs parent-dir))))

(defn safe-read-edn
  "Safely read EDN from file, returning default on error.

   Arguments:
     file-path - String path to file
     default - Default value if read fails

   Returns: Parsed EDN or default"
  [file-path default]
  (try
    (when (.exists (io/file file-path))
      (edn/read-string (slurp file-path)))
    (catch Exception _
      default)))

(defn atomic-write-edn
  "Atomically write EDN to file using temp file + rename.

   Arguments:
     file-path - String path to file
     data - Data to write as EDN

   Returns: nil"
  [file-path data]
  (ensure-directory-exists file-path)
  (let [temp-file (str file-path ".tmp")]
    (spit temp-file (pr-str data))
    (.renameTo (io/file temp-file) (io/file file-path))))

;;------------------------------------------------------------------------------ Layer 1
;; Workaround registry operations

(defn load-workarounds
  "Load workarounds from persistent storage.

   Returns: Map with :workarounds vector"
  []
  (or (safe-read-edn (workaround-registry-path) nil)
      {:workarounds []}))

(defn save-workarounds!
  "Save workarounds to persistent storage.

   Arguments:
     workarounds-data - Map with :workarounds vector

   Returns: nil"
  [workarounds-data]
  (atomic-write-edn (workaround-registry-path) workarounds-data))

(defn add-workaround!
  "Add a new workaround to the registry.

   Arguments:
     workaround - Map with workaround details:
       :id - UUID (optional, will be generated if not provided)
       :error-pattern-id - Keyword matching error-patterns/*.edn :id
       :description - String description of the workaround
       :workaround-type - :retry | :parameter-adjustment | :backend-switch | :prompt-rewrite
       :workaround-data - Map with type-specific data
       :github-issue-url - Optional string URL

   Returns: Updated workaround with :id and timestamps"
  [workaround]
  (let [registry (load-workarounds)
        id (or (:id workaround) (java.util.UUID/randomUUID))
        now (str (java.time.Instant/now))
        new-workaround (merge {:id id
                               :success-count 0
                               :failure-count 0
                               :confidence 0.0
                               :discovered-at now
                               :last-used nil}
                              workaround)]
    (save-workarounds!
     (update registry :workarounds conj new-workaround))
    new-workaround))

(defn update-workaround-stats!
  "Update success/failure statistics for a workaround.

   Arguments:
     workaround-id - UUID or string ID of workaround
     success? - Boolean indicating if workaround succeeded

   Returns: Updated workaround map or nil if not found"
  [workaround-id success?]
  (let [registry (load-workarounds)
        workaround-id (if (string? workaround-id)
                        (java.util.UUID/fromString workaround-id)
                        workaround-id)
        workarounds (:workarounds registry)
        idx (.indexOf (mapv :id workarounds) workaround-id)]
    (when (>= idx 0)
      (let [workaround (nth workarounds idx)
            updated (-> workaround
                       (update (if success? :success-count :failure-count) inc)
                       (assoc :last-used (str (java.time.Instant/now))))
            total (+ (:success-count updated) (:failure-count updated))
            confidence (if (> total 0)
                        (double (/ (:success-count updated) total))
                        0.0)
            updated (assoc updated :confidence confidence)
            new-workarounds (assoc (vec workarounds) idx updated)]
        (save-workarounds! (assoc registry :workarounds new-workarounds))
        updated))))

(defn get-workaround-by-pattern
  "Get workaround matching an error pattern ID.

   Arguments:
     error-pattern-id - Keyword pattern ID

   Returns: Workaround map or nil if not found"
  [error-pattern-id]
  (let [registry (load-workarounds)]
    (first (filter #(= (:error-pattern-id %) error-pattern-id)
                   (:workarounds registry)))))

(defn get-high-confidence-workarounds
  "Get all workarounds with confidence >= 0.8.

   Returns: Vector of high-confidence workaround maps"
  []
  (let [registry (load-workarounds)]
    (vec (filter #(>= (:confidence %) 0.8)
                 (:workarounds registry)))))

(defn get-all-workarounds
  "Get all workarounds from registry.

   Returns: Vector of all workaround maps"
  []
  (:workarounds (load-workarounds)))

(defn delete-workaround!
  "Delete a workaround from the registry.

   Arguments:
     workaround-id - UUID or string ID of workaround

   Returns: Boolean indicating if workaround was found and deleted"
  [workaround-id]
  (let [registry (load-workarounds)
        workaround-id (if (string? workaround-id)
                        (java.util.UUID/fromString workaround-id)
                        workaround-id)
        workarounds (:workarounds registry)
        new-workarounds (vec (remove #(= (:id %) workaround-id) workarounds))]
    (when (not= (count workarounds) (count new-workarounds))
      (save-workarounds! (assoc registry :workarounds new-workarounds))
      true)))
