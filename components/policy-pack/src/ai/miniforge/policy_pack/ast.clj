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

(ns ai.miniforge.policy-pack.ast
  "Tree-sitter CLI wrapper for language-agnostic AST analysis.

   Shells out to `tree-sitter query` for structural pattern matching.
   No JNI, no library linking, GraalVM-safe. One interface for all
   languages via pluggable tree-sitter grammars.

   Layer 0: CLI invocation helpers
   Layer 1: Query execution and output parsing
   Layer 2: Match-to-violation conversion"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; CLI helpers

(defn tree-sitter-available?
  "Check if the tree-sitter CLI is available on PATH."
  []
  (try
    (let [pb   (ProcessBuilder. ^java.util.List ["tree-sitter" "--version"])
          proc (.start pb)
          _    (.waitFor proc)]
      (zero? (.exitValue proc)))
    (catch Exception _ false)))

(defn file-extension
  "Extract the file extension from a path (without the dot)."
  [path]
  (let [filename (.getName (java.io.File. (str path)))]
    (when-let [idx (str/last-index-of filename ".")]
      (subs filename (inc idx)))))

(def ^:private extension->lang
  "Map file extensions to tree-sitter language names."
  {"clj"  "clojure"
   "cljs" "clojure"
   "cljc" "clojure"
   "py"   "python"
   "js"   "javascript"
   "ts"   "typescript"
   "tsx"  "tsx"
   "rs"   "rust"
   "go"   "go"
   "java" "java"
   "rb"   "ruby"
   "swift" "swift"
   "tf"   "hcl"
   "yaml" "yaml"
   "yml"  "yaml"
   "json" "json"
   "toml" "toml"
   "md"   "markdown"
   "sh"   "bash"
   "bash" "bash"})

(defn detect-language
  "Detect tree-sitter language from file path or explicit :lang option."
  [file-path lang-override]
  (or lang-override
      (get extension->lang (file-extension file-path))
      "text"))

;------------------------------------------------------------------------------ Layer 1
;; Query execution

(defn- write-temp-query
  "Write a query pattern to a temporary .scm file. Returns the File."
  [query-str]
  (let [f (java.io.File/createTempFile "ts-query-" ".scm")]
    (.deleteOnExit f)
    (spit f query-str)
    f))

(defn- write-temp-source
  "Write source content to a temporary file with the right extension.
   Returns the File."
  [content extension]
  (let [f (java.io.File/createTempFile "ts-src-" (str "." (or extension "txt")))]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- parse-query-output
  "Parse tree-sitter query output into match maps.

   Tree-sitter query output format (one match per line):
     <file>:<row>:<col>: pattern: <idx>, capture: <name>, text: `<text>`

   Returns vector of {:row int :col int :capture string :text string :pattern int}."
  [output]
  (when-not (str/blank? output)
    (->> (str/split-lines output)
         (keep (fn [line]
                 (when-let [m (re-matches
                               #".*:(\d+):(\d+):\s*pattern:\s*(\d+),\s*capture:\s*(\S+),\s*text:\s*`(.*)`"
                               line)]
                   {:row     (Integer/parseInt (nth m 1))
                    :col     (Integer/parseInt (nth m 2))
                    :pattern (Integer/parseInt (nth m 3))
                    :capture (nth m 4)
                    :text    (nth m 5)})))
         vec)))

(defn run-query
  "Execute a tree-sitter query against source content.

   Arguments:
   - content    — Source code string
   - query      — Tree-sitter query pattern (S-expression)
   - lang       — Tree-sitter language name (e.g. \"clojure\")
   - extension  — File extension for temp file (e.g. \"clj\")

   Returns:
   - {:success? true :matches [...]}
   - {:success? false :error string}"
  [content query lang extension]
  (let [query-file  (write-temp-query query)
        source-file (write-temp-source content extension)]
    (try
      (let [pb (ProcessBuilder. ^java.util.List
                ["tree-sitter" "query"
                 (.getAbsolutePath query-file)
                 (.getAbsolutePath source-file)])
            _    (-> (.environment pb) (.put "TREE_SITTER_LANGUAGE" lang))
            proc (.start pb)
            out  (slurp (.getInputStream proc))
            err  (slurp (.getErrorStream proc))
            _    (.waitFor proc)
            exit (.exitValue proc)]
        (if (zero? exit)
          {:success? true
           :matches  (or (parse-query-output out) [])}
          {:success? false
           :error    (str "tree-sitter query failed (exit " exit "): "
                          (str/trim err))}))
      (catch Exception e
        {:success? false :error (.getMessage e)})
      (finally
        (.delete query-file)
        (.delete source-file)))))

;------------------------------------------------------------------------------ Layer 2
;; Match-to-violation helpers

(defn matches->violations
  "Convert tree-sitter query matches to violation-shaped maps.

   Arguments:
   - matches   — Vector of match maps from run-query
   - rule-id   — Rule keyword
   - rule-cat  — Rule category string
   - title     — Rule title string
   - file-path — Source file path

   Returns vector of violation-shaped maps."
  [matches rule-id rule-cat title file-path]
  (mapv (fn [{:keys [row text]}]
          {:rule/id       rule-id
           :rule/category rule-cat
           :rule/title    title
           :file          file-path
           :line          (inc row) ; tree-sitter is 0-indexed
           :current       text
           :suggested     nil
           :auto-fixable? false
           :rationale     ""})
        matches))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (tree-sitter-available?)

  ;; Query for function calls to `eval` in Clojure
  (run-query "(eval x)" "(call_expression function: (identifier) @fn (#eq? @fn \"eval\"))"
             "clojure" "clj")

  :leave-this-here)
