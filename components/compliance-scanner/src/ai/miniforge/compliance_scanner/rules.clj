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

(ns ai.miniforge.compliance-scanner.rules
  "Per-rule detection configurations for Dewey 210, 730, and 810.

   All patterns are hardcoded for M1 (no .mdc compilation needed).

   Layer 0: Rule config data
   Layer 1: Suggestion-generation helpers"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Rule configs

(def rule-configs
  "Map of Dewey code → rule detection config.

   Each config has:
   - :dewey        - string Dewey code
   - :title        - human-readable rule name
   - :pattern      - java.util.regex.Pattern for positive matches
   - :file-pred    - fn [file-path] -> boolean, selects files to scan
   - :suggest-fn   - fn [matched-text] -> suggested replacement string or nil
   - :detect-mode  - :positive (pattern = violation) or
                     :negative (absence of pattern = violation)"
  {"210"
   {:dewey       "210"
    :title       "Clojure Map Access"
    :pattern     #"\(or \(:[a-zA-Z][a-zA-Z0-9?!_*+<>-]*\s+[a-zA-Z][a-zA-Z0-9_-]*\)\s+(?:nil|true|false|\"[^\"]*\"|-?\d+(?:\.\d+)?|\[\]|\{\}|:[a-zA-Z][a-zA-Z0-9_-]*)\)"
    :file-pred   (fn [path]
                   (and (or (str/ends-with? path ".clj")
                            (str/ends-with? path ".cljc"))
                        (or (re-find #"(?:^|/)components/[^/]+/src/" path)
                            (re-find #"(?:^|/)bases/[^/]+/src/" path))))
    :suggest-fn  (fn [matched-text]
                   ;; Parse (or (:k m) v) -> (get m :k v)
                   ;; matched-text is the full expression including outer parens.
                   (when-let [[_ kw m default-val]
                              (re-find
                               #"\(or \((:[\w?!*+<>-]+)\s+([\w-]+)\)\s+(.+?)\)"
                               (str matched-text))]
                     (str "(get " m " " kw " " (str/trimr default-val) ")")))
    :detect-mode :positive}

   "730"
   {:dewey       "730"
    :title       "Version Format (SemVer vs DateVer)"
    :pattern     #"\b(\d+)\.(\d+)\.(\d+)\b(?!\.\d)"
    :file-pred   (fn [path]
                   (or (str/ends-with? path "build.clj")
                       (str/ends-with? path "version.edn")
                       (re-find #"\.github/workflows/[^/]+\.ya?ml$" path)))
    :suggest-fn  (fn [matched-text]
                   ;; SemVer stays as-is; the issue is using SemVer where
                   ;; DateVer is required — suggest adding build count segment
                   (str matched-text ".0"))
    :detect-mode :positive}

   "810"
   {:dewey       "810"
    :title       "Copyright Header (Markdown)"
    ;; The *positive* signal we look for in the first 10 lines.
    ;; A file is a VIOLATION when this pattern is ABSENT.
    :pattern     #"Christopher Lester"
    :email-pattern #"christopher@miniforge\.ai"
    :file-pred   (fn [path]
                   (and (str/ends-with? path ".md")
                        (not (re-find #"(?:^|/)\.git/" path))
                        (not (re-find #"(?:^|/)node_modules/" path))
                        (or (re-find #"(?:^|/)docs/" path)
                            (re-find #"(?:^|/)specs/" path)
                            ;; Root-level .md files (no subdirectory separator)
                            (not (str/includes? path "/")))))
    :suggest-fn  (fn [_] nil)   ; prepending is the fix, no inline replacement
    :detect-mode :negative}})   ; absence of :pattern = violation

;------------------------------------------------------------------------------ Layer 1
;; Suggestion helper

(defn suggest
  "Return a suggested replacement for a matched violation string, or nil."
  [dewey matched-text]
  (when-let [cfg (get rule-configs dewey)]
    (when-let [f (get cfg :suggest-fn)]
      (f matched-text))))

;------------------------------------------------------------------------------ Layer 1
;; Enabled rules helper

(defn enabled-rule-configs
  "Return the rule configs to run.
   opts may contain :rules — a set of Dewey strings, or :all (default)."
  [opts]
  (let [requested (get opts :rules :all)]
    (if (= requested :all)
      (vals rule-configs)
      (->> requested
           (map #(get rule-configs %))
           (filter some?)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (keys rule-configs)
  ;; => ("210" "730" "810")

  (suggest "210" "(or (:status m) :pending)")
  ;; => "(get m :status :pending)"

  (suggest "730" "1.2.3")
  ;; => "1.2.3.0"

  (enabled-rule-configs {:rules #{"210" "810"}})

  :leave-this-here)
