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
(ns ai.miniforge.policy-pack.mdc-compiler.frontmatter
  "MDC frontmatter line-grammar: splitting a file into frontmatter/body,
   and parsing the frontmatter block's lines (key: value, multi-line
   `- item` lists) into a string-keyed map. Split out of
   `ai.miniforge.policy-pack.mdc-compiler` (rule 210: slice 2/6 of the
   same split train as
   `ai.miniforge.policy-pack.mdc-compiler.frontmatter-values`, miniforge#1729
   — same approach as the dag-orchestrator split, miniforge#1485, and
   the workflow-runner split, miniforge#1662)."
  (:require
   [ai.miniforge.policy-pack.mdc-compiler.frontmatter-values :as frontmatter-values]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; MDC parsing — frontmatter and body extraction
(defn ^{:stratum 0} split-frontmatter
  "Split MDC content into frontmatter string and body string.

   Expects --- delimited YAML frontmatter at the top of the file.

   Arguments:
   - content - Full .mdc file content

   Returns:
   - {:frontmatter <string> :body <string>}"
  [content]
  (let [trimmed (str/trim (str content))]
    (if (str/starts-with? trimmed "---")
      (let [after-open (subs trimmed 3)
            close-idx (str/index-of after-open "---")]
        (if close-idx
          {:frontmatter (str/trim (subs after-open 0 close-idx))
           :body        (str/trim (subs after-open (+ close-idx 3)))}
          {:frontmatter ""
           :body        trimmed}))
      {:frontmatter ""
       :body        trimmed})))

(defn- ^{:stratum 0} parse-list-item
  "Parse a '- <value>' frontmatter list line to its string value."
  [trimmed]
  (frontmatter-values/strip-quotes (str/trim (subs trimmed 2))))

(defn- ^{:stratum 0} parse-kv-line
  "Parse a 'key: value' frontmatter line.
   Returns [new-current-key updated-acc]."
  [trimmed acc]
  (let [idx (str/index-of trimmed ":")
        k   (str/trim (subs trimmed 0 idx))
        v   (str/trim (subs trimmed (inc idx)))]
    (if (str/blank? v)
      [k (assoc acc k [])]
      [nil (assoc acc k (frontmatter-values/parse-frontmatter-value v))])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} process-frontmatter-line
  "Process one frontmatter line. Returns [current-key acc]."
  [trimmed current-key acc]
  (cond
    (str/blank? trimmed)
    [current-key acc]

    (and current-key (str/starts-with? trimmed "- "))
    [current-key (update acc current-key (fnil conj []) (parse-list-item trimmed))]

    (str/includes? trimmed ":")
    (parse-kv-line trimmed acc)

    :else [current-key acc]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-frontmatter
  "Parse YAML-like frontmatter text into a string-keyed map.

   Handles simple key: value pairs, inline arrays [a, b], and
   multi-line list items (- value) under a key.

   Arguments:
   - frontmatter-str - Raw text between --- delimiters

   Returns:
   - Map of {string-key parsed-value}, or empty map."
  [frontmatter-str]
  (if (str/blank? frontmatter-str)
    {}
    (loop [[line & remaining] (str/split-lines frontmatter-str)
           current-key nil
           acc {}]
      (if (nil? line)
        acc
        (let [[current-key' acc'] (process-frontmatter-line (str/trim line) current-key acc)]
          (recur remaining current-key' acc'))))))
