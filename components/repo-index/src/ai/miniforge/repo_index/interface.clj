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

(ns ai.miniforge.repo-index.interface
  "Public API for the repo-index component.

   Provides repository scanning, repo map generation, and file retrieval.

   Layer 0: Schema re-exports
   Layer 1: Index building and repo map
   Layer 2: File retrieval"
  (:require [ai.miniforge.repo-index.schema :as schema]
            [ai.miniforge.repo-index.scanner :as scanner]
            [ai.miniforge.repo-index.repo-map :as repo-map]
            [ai.miniforge.repo-index.storage :as storage]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def FileRecord schema/FileRecord)
(def RepoIndex schema/RepoIndex)
(def RepoMapEntry schema/RepoMapEntry)
(def RepoMapSlice schema/RepoMapSlice)

;------------------------------------------------------------------------------ Layer 1
;; Index building

(defn build-index
  "Build a repo index for a git repository.

   Uses cached index if tree-sha hasn't changed, otherwise performs a full scan.

   Arguments:
   - repo-root - Path to the repository root

   Returns:
   - RepoIndex map, or nil if not a git repo"
  [repo-root]
  (when-let [index (scanner/scan repo-root)]
    (let [cached (storage/cached-index repo-root (:tree-sha index))]
      (if cached
        cached
        (do
          (storage/save-index repo-root index)
          index)))))

;------------------------------------------------------------------------------ Layer 1
;; Repo map

(defn repo-map
  "Generate a token-budgeted repo map from a repo index.

   Arguments:
   - index - RepoIndex map (from build-index)
   - opts  - Optional map:
     - :token-budget       - Max tokens (default 500)
     - :exclude-generated? - Exclude generated files (default true)

   Returns:
   - RepoMapSlice with :text (rendered markdown), :token-estimate, etc."
  ([index] (repo-map/generate index))
  ([index opts] (repo-map/generate index opts)))

(defn repo-map-text
  "Convenience: generate repo map and return just the rendered text.

   Arguments:
   - index - RepoIndex map
   - opts  - Optional map (same as repo-map)

   Returns:
   - String with rendered repo map markdown"
  ([index] (:text (repo-map index)))
  ([index opts] (:text (repo-map index opts))))

;------------------------------------------------------------------------------ Layer 2
;; File retrieval

(defn- find-in-index
  "Find a file record in the index by path. Returns nil if absent."
  [index file-path]
  (first (filter #(= (:path %) file-path) (:files index))))

(defn- read-file-limited
  "Read a file from disk, truncating to max-lines.
   Returns {:path :content :lines :truncated?} or nil on error."
  [repo-root file-path max-lines]
  (try
    (let [f (io/file repo-root file-path)]
      (when (.isFile f)
        (let [all-lines (str/split-lines (slurp f))
              line-count (count all-lines)
              truncated? (> line-count max-lines)
              kept-lines (if truncated? (take max-lines all-lines) all-lines)]
          {:path file-path
           :content (str/join "\n" kept-lines)
           :lines (min line-count max-lines)
           :truncated? truncated?})))
    (catch Exception _
      nil)))

(defn get-file
  "Read a single file's contents from disk, using the index for validation.

   Arguments:
   - index     - RepoIndex map
   - file-path - Relative path to the file
   - opts      - Optional map:
     - :max-lines - Maximum lines to return (default 500)

   Returns:
   - {:path :content :lines :truncated?} or nil if file not in index"
  ([index file-path] (get-file index file-path {}))
  ([index file-path opts]
   (let [max-lines (or (:max-lines opts) 500)]
     (when (find-in-index index file-path)
       (read-file-limited (:repo-root index) file-path max-lines)))))

(defn get-files
  "Read multiple files from disk, using the index for validation.

   Arguments:
   - index      - RepoIndex map
   - file-paths - Seq of relative file paths
   - opts       - Optional map (same as get-file)

   Returns:
   - Vector of file maps, filtered to only files that exist in the index"
  ([index file-paths] (get-files index file-paths {}))
  ([index file-paths opts]
   (->> file-paths
        (map #(get-file index % opts))
        (filterv some?))))

(defn find-files
  "Find files in the index matching a predicate.

   Arguments:
   - index - RepoIndex map
   - pred  - Predicate fn taking a FileRecord

   Returns:
   - Vector of matching FileRecord maps"
  [index pred]
  (filterv pred (:files index)))

(defn files-by-language
  "Get all non-generated files for a given language.

   Arguments:
   - index    - RepoIndex map
   - language - Language string (e.g. \"clojure\")

   Returns:
   - Vector of FileRecord maps"
  [index language]
  (find-files index
    (fn [f]
      (and (= (:language f) language)
           (not (:generated? f))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def idx (build-index "."))

  (println (repo-map-text idx))
  (println (repo-map-text idx {:token-budget 1000}))

  (get-file idx "deps.edn")
  (get-files idx ["deps.edn" "workspace.edn"])

  (count (files-by-language idx "clojure"))
  (find-files idx #(> (:lines %) 200))

  :leave-this-here)
