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

(ns ai.miniforge.phase.file-context
  "Load existing file contents for agent context.

   Layer 0 utility that reads files from disk with safety limits,
   providing agents visibility into existing implementations."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def defaults
  "Default limits for file loading. Override via opts map."
  {:max-files 25
   :max-lines-per-file 500})

;------------------------------------------------------------------------------ Layer 0
;; File reading

(defn read-file-limited
  "Read a single file, truncating to max-lines.

   Arguments:
   - worktree-path - Root directory of the worktree
   - path - Relative file path
   - max-lines - Maximum number of lines to include

   Returns:
   - {:path :content :truncated? :lines} or nil for non-existent/directory paths"
  [worktree-path path max-lines]
  (try
    (let [f (io/file worktree-path path)]
      (when (and (.exists f) (.isFile f))
        (let [all-lines (str/split-lines (slurp f))
              line-count (count all-lines)
              truncated? (> line-count max-lines)
              kept-lines (if truncated?
                           (take max-lines all-lines)
                           all-lines)]
          {:path path
           :content (str/join "\n" kept-lines)
           :truncated? truncated?
           :lines (min line-count max-lines)})))
    (catch Exception _
      nil)))

(defn load-files-in-scope
  "Load existing file contents for files in scope.

   Arguments:
   - worktree-path - Root directory of the worktree
   - files-in-scope - Seq of relative file paths
   - opts - Optional map with :max-files and :max-lines-per-file overrides

   Returns:
   - Vector of file maps {:path :content :truncated? :lines},
     capped at max-files. Nil entries are removed."
  ([worktree-path files-in-scope]
   (load-files-in-scope worktree-path files-in-scope {}))
  ([worktree-path files-in-scope opts]
   (let [{:keys [max-files max-lines-per-file]} (merge defaults opts)]
     (when (seq files-in-scope)
       (->> files-in-scope
            (take max-files)
            (map #(read-file-limited worktree-path % max-lines-per-file))
            (filterv some?))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load files from the current directory
  (load-files-in-scope "." ["deps.edn" "README.md"])

  ;; Read a single file with limit
  (read-file-limited "." "deps.edn" 50)

  :leave-this-here)
