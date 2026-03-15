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

(ns ai.miniforge.repo-index.storage
  "Persist and load repo indexes to `.miniforge/index/`.

   Layer 1 — depends on schema (Layer 0)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; Path helpers

(def ^:private index-dir-name
  "Directory name for storing indexes within the repo."
  ".miniforge/index")

(defn- index-dir
  "Get the index directory path for a repo root."
  [repo-root]
  (io/file repo-root index-dir-name))

(defn- index-file
  "Get the index file path for a given tree-sha."
  [repo-root tree-sha]
  (io/file (index-dir repo-root) (str tree-sha ".edn")))

;------------------------------------------------------------------------------ Layer 1
;; Persistence

(defn save-index
  "Save a repo index to disk, keyed by tree-sha.

   Arguments:
   - repo-root - Path to the repository root
   - index     - RepoIndex map from scanner/scan

   Returns:
   - Path to the saved file, or nil on failure"
  [repo-root index]
  (try
    (let [dir (index-dir repo-root)
          f (index-file repo-root (:tree-sha index))]
      (.mkdirs dir)
      (spit f (pr-str index))
      (.getPath f))
    (catch Exception _
      nil)))

(defn load-index
  "Load a previously saved repo index by tree-sha.

   Arguments:
   - repo-root - Path to the repository root
   - tree-sha  - Git tree SHA to look up

   Returns:
   - RepoIndex map, or nil if not found"
  [repo-root tree-sha]
  (try
    (let [f (index-file repo-root tree-sha)]
      (when (.exists f)
        (edn/read-string (slurp f))))
    (catch Exception _
      nil)))

(defn cached-index
  "Load the cached index for the current HEAD tree-sha, if available.

   Arguments:
   - repo-root - Path to the repository root
   - tree-sha  - Current HEAD tree SHA

   Returns:
   - RepoIndex map if cache hit, nil if cache miss"
  [repo-root tree-sha]
  (load-index repo-root tree-sha))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.repo-index.scanner :as scanner])
  (def idx (scanner/scan "."))

  ;; Save
  (save-index "." idx)

  ;; Load
  (load-index "." (:tree-sha idx))

  ;; Check cache
  (cached-index "." (:tree-sha idx))

  :leave-this-here)
