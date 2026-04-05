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

(ns ai.miniforge.repo-index.scanner
  "Walk git tree via `git ls-tree` to build a repo index.

   Layer 1 — config-driven language detection and generated-file patterns."
  (:require [ai.miniforge.repo-index.factory :as factory]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration (loaded from EDN resource)

(def ^:private config-path "config/repo-index/scanner.edn")

(defn- load-scanner-config []
  (if-let [res (io/resource config-path)]
    (get (edn/read-string (slurp res)) :repo-index/scanner {})
    {}))

(def ^:private scanner-config (delay (load-scanner-config)))

(defn- extension->language []
  (get @scanner-config :extension->language {}))

(defn- special-filenames []
  (get @scanner-config :special-filenames {}))

(defn- generated-path-patterns []
  (->> (get @scanner-config :generated-path-patterns [])
       (mapv re-pattern)))

;------------------------------------------------------------------------------ Layer 0
;; Language detection

(defn- detect-language
  "Detect language from file path extension or special filename."
  [path]
  (let [filename (last (str/split path #"/"))
        lower-filename (str/lower-case filename)]
    (or (get (special-filenames) lower-filename)
        (when-let [ext (last (re-find #"\.(\w+)$" filename))]
          (get (extension->language) (str/lower-case ext))))))

;------------------------------------------------------------------------------ Layer 0
;; Generated file detection

(defn- generated-by-path?
  "Check if a file path matches known generated file patterns."
  [path]
  (boolean (some #(re-find % path) (generated-path-patterns))))

;------------------------------------------------------------------------------ Layer 1
;; Git commands

(defn- run-git-command
  "Execute a git command in repo-root and return trimmed stdout, or nil on failure.

   Clears git environment overrides (GIT_DIR, GIT_WORK_TREE, GIT_INDEX_FILE)
   so the command operates on repo-root rather than any inherited worktree context."
  [repo-root args]
  (try
    (let [pb  (ProcessBuilder. ^java.util.List args)
          _   (.directory pb (io/file repo-root))
          env (.environment pb)
          _   (doto env
                (.remove "GIT_DIR")
                (.remove "GIT_WORK_TREE")
                (.remove "GIT_INDEX_FILE"))
          proc (.start pb)
          output (slurp (.getInputStream proc))
          exit-code (.waitFor proc)]
      (when (zero? exit-code)
        (str/trim output)))
    (catch Exception _
      nil)))

(defn- run-git-ls-tree
  "Execute `git ls-tree -r --long HEAD` and return raw output lines."
  [repo-root]
  (when-let [output (run-git-command repo-root ["git" "ls-tree" "-r" "--long" "HEAD"])]
    (str/split-lines output)))

(defn- run-git-rev-parse-tree
  "Get the tree SHA for HEAD."
  [repo-root]
  (run-git-command repo-root ["git" "rev-parse" "HEAD^{tree}"]))

;------------------------------------------------------------------------------ Layer 1
;; Parsing

(defn- parse-ls-tree-line
  "Parse a single line of `git ls-tree -r --long` output.
   Format: <mode> <type> <sha> <size> <path>
   Returns nil for non-blob entries."
  [line]
  (when-let [m (re-find #"^(\d+)\s+(blob)\s+([0-9a-f]+)\s+(\d+)\s+(.+)$" line)]
    (let [[_ _mode _type blob-sha size-str path] m]
      {:path path
       :blob-sha blob-sha
       :size (parse-long size-str)})))

(defn- count-lines
  "Count lines in a file. Returns 0 for binary/unreadable files."
  [repo-root path]
  (try
    (let [f (io/file repo-root path)]
      (when (.isFile f)
        (with-open [rdr (io/reader f)]
          (count (line-seq rdr)))))
    (catch Exception _
      0)))

(defn- enrich-blob-entry
  "Enrich a parsed blob entry with language, line count, and generated? flag."
  [repo-root {:keys [path blob-sha size]}]
  (factory/->file-record path blob-sha size
                         (or (count-lines repo-root path) 0)
                         (detect-language path)
                         (generated-by-path? path)))

(defn- compute-language-frequencies
  "Compute language frequency map from non-generated file entries."
  [entries]
  (->> entries
       (remove :generated?)
       (keep :language)
       frequencies))

;------------------------------------------------------------------------------ Layer 2
;; Public scan

(defn scan
  "Scan a git repository and build a repo index.

   Arguments:
   - repo-root - Path to the repository root

   Returns:
   - RepoIndex map, or nil if not a git repo / scan fails"
  [repo-root]
  (when-let [tree-sha (run-git-rev-parse-tree repo-root)]
    (when-let [ls-tree-lines (run-git-ls-tree repo-root)]
      (let [entries (->> ls-tree-lines
                         (keep parse-ls-tree-line)
                         (mapv (partial enrich-blob-entry repo-root)))]
        (factory/->repo-index tree-sha repo-root entries
                              (compute-language-frequencies entries))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def idx (scan "."))

  (:tree-sha idx)
  (:file-count idx)
  (:total-lines idx)
  (:languages idx)

  (take 5 (:files idx))
  (filter :generated? (:files idx))

  :leave-this-here)
