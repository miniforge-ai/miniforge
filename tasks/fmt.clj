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

(ns fmt
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

(defn staged-files []
  (-> (p/sh "git" "diff" "--cached" "--name-only" "--diff-filter=ACM")
      :out
      str/split-lines
      (->> (remove str/blank?))))

(defn staged-by-ext [ext]
  (->> (staged-files)
       (filter (fn [f] (str/ends-with? f ext)))))

(defn- in-block?
  "Track whether we're inside a fenced code block."
  [line state]
  (if (str/starts-with? (str/trim line) "```")
    (not state)
    state))

(defn- wrap-prose-line
  "Wrap a single prose line at `max-col`, preserving leading indent.
   Only wraps at word boundaries. Returns a vector of lines."
  [line max-col]
  (let [indent (re-find #"^\s*" line)
        prefix (if (re-find #"^\s*[-*+\d]" line)
                 (str indent "  ")
                 indent)]
    (if (<= (count line) max-col)
      [line]
      (loop [remaining (str/trim line)
             result []]
        (if (<= (count (str indent remaining)) max-col)
          (let [full (if (seq result)
                       (str prefix remaining)
                       (str indent remaining))]
            (conj result full))
          (let [target (- max-col (count (if (seq result) prefix indent)))
                cut (or (str/last-index-of remaining " " target) target)
                before (subs remaining 0 cut)
                after (str/trim (subs remaining cut))
                full (if (seq result)
                       (str prefix before)
                       (str indent before))]
            (if (str/blank? after)
              (conj result full)
              (recur after (conj result full)))))))))

(defn wrap-md-lines
  "Wrap long prose lines in a markdown string. Skips code blocks,
   headings, tables, and HTML. Uses 120-char limit to match .markdownlint.jsonc."
  [content]
  (let [max-col 120
        ends-newline? (str/ends-with? content "\n")
        lines (str/split-lines content)]
    (loop [remaining lines
           code-block? false
           out []]
      (if (empty? remaining)
        (cond-> (str/join "\n" out) ends-newline? (str "\n"))
        (let [line (first remaining)
              trimmed (str/trim line)
              new-block? (in-block? line code-block?)
              skip? (or code-block?
                        new-block?
                        (str/starts-with? trimmed "#")
                        (str/starts-with? trimmed "|")
                        (str/starts-with? trimmed "<")
                        (str/starts-with? trimmed "[")
                        (re-find #"^\s*```" trimmed))]
          (if (or skip? (<= (count line) max-col))
            (recur (rest remaining) new-block? (conj out line))
            (recur (rest remaining) new-block?
                   (into out (wrap-prose-line line max-col)))))))))

(defn wrap-md-file!
  "Wrap long lines in a markdown file in-place."
  [path]
  (let [content (slurp path)
        wrapped (wrap-md-lines content)]
    (when (not= content wrapped)
      (spit path wrapped)
      true)))

(defn md-staged []
  (let [md-files (staged-by-ext ".md")]
    (if (seq md-files)
      (do
        (println "📝 Formatting" (count md-files) "Markdown file(s)...")
        ;; Wrap long prose lines before linting
        (doseq [f md-files]
          (when (wrap-md-file! f)
            (println "  ↳ wrapped long lines in" f)))
        (let [{:keys [exit]} (apply p/sh "markdownlint" "--fix" md-files)]
          (when-not (zero? exit)
            (System/exit exit)))
        ;; Re-add the fixed files to staging
        (doseq [f md-files]
          (p/sh "git" "add" f)))
      (println "✓ No Markdown files to format"))))
