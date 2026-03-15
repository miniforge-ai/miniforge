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

   Layer 1 — depends on schema (Layer 0)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Language detection (extension-based)

(def ^:private extension->language
  "Map file extensions to language names."
  {"clj"   "clojure"
   "cljc"  "clojure"
   "cljs"  "clojurescript"
   "edn"   "edn"
   "py"    "python"
   "js"    "javascript"
   "ts"    "typescript"
   "tsx"   "typescript"
   "jsx"   "javascript"
   "java"  "java"
   "go"    "go"
   "rs"    "rust"
   "rb"    "ruby"
   "sh"    "shell"
   "bash"  "shell"
   "zsh"   "shell"
   "yml"   "yaml"
   "yaml"  "yaml"
   "json"  "json"
   "toml"  "toml"
   "xml"   "xml"
   "html"  "html"
   "css"   "css"
   "scss"  "scss"
   "sql"   "sql"
   "md"    "markdown"
   "txt"   "text"
   "cfg"   "config"
   "ini"   "config"
   "conf"  "config"
   "c"     "c"
   "cpp"   "cpp"
   "h"     "c"
   "hpp"   "cpp"
   "cs"    "csharp"
   "swift" "swift"
   "kt"    "kotlin"
   "scala" "scala"
   "ex"    "elixir"
   "exs"   "elixir"
   "hs"    "haskell"
   "lua"   "lua"
   "r"     "r"
   "R"     "r"
   "pl"    "perl"
   "php"   "php"
   "tf"    "terraform"
   "proto" "protobuf"
   "graphql" "graphql"
   "dockerfile" "dockerfile"})

(def ^:private special-filenames
  "Filenames that map to languages without extensions."
  {"dockerfile" "dockerfile"
   "makefile"   "make"
   "rakefile"   "ruby"
   "gemfile"    "ruby"})

(defn- detect-language
  "Detect language from file path extension or special filename."
  [path]
  (let [filename (last (str/split path #"/"))
        lower-filename (str/lower-case filename)]
    (or (get special-filenames lower-filename)
        (when-let [ext (last (re-find #"\.(\w+)$" filename))]
          (get extension->language (str/lower-case ext))))))

;------------------------------------------------------------------------------ Layer 0
;; Generated file detection

(def ^:private generated-path-patterns
  "Regex patterns that indicate generated/vendored files."
  [#"^vendor/"
   #"^node_modules/"
   #"^\.git/"
   #"^target/"
   #"^dist/"
   #"^build/"
   #"^out/"
   #"^\.cpcache/"
   #"^\.clj-kondo/\.cache/"
   #"\.min\.(js|css)$"
   #"\.bundle\.(js|css)$"
   #"\.generated\."
   #"^package-lock\.json$"
   #"^yarn\.lock$"
   #"^pnpm-lock\.yaml$"
   #"^Cargo\.lock$"
   #"^go\.sum$"
   #"^poetry\.lock$"
   #"^Gemfile\.lock$"
   #"^composer\.lock$"])

(defn- generated-by-path?
  "Check if a file path matches known generated file patterns."
  [path]
  (boolean (some #(re-find % path) generated-path-patterns)))

;------------------------------------------------------------------------------ Layer 1
;; Git commands

(defn- run-git-command
  "Execute a git command in repo-root and return trimmed stdout, or nil on failure."
  [repo-root args]
  (try
    (let [pb (ProcessBuilder. ^java.util.List args)
          _ (.directory pb (io/file repo-root))
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
  {:path path
   :blob-sha blob-sha
   :size size
   :lines (or (count-lines repo-root path) 0)
   :language (detect-language path)
   :generated? (generated-by-path? path)})

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
        {:tree-sha tree-sha
         :repo-root repo-root
         :files entries
         :file-count (count entries)
         :total-lines (reduce + 0 (map :lines entries))
         :languages (compute-language-frequencies entries)
         :indexed-at (java.util.Date.)}))))

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
