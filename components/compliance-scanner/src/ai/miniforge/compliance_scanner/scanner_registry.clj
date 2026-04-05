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

(ns ai.miniforge.compliance-scanner.scanner-registry
  "Detection registry: per-rule-id scan configurations.

   Keys are :rule/id keywords matching compiled pack rule identities.
   Each entry carries the detection implementation for one policy rule.
   The compiled pack drives WHAT rules exist; this registry drives HOW
   mechanical rules are detected.

   Layer 0: Registry data
   Layer 1: Accessor helpers"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Registry

(def registry
  "Map of :rule/id keyword → detection config.

   Each config:
   - :rule/id       - keyword rule identity (matches compiled pack :rule/id)
   - :rule/category - Dewey code string (used for within-file ordering)
   - :title         - human-readable rule name
   - :pattern       - java.util.regex.Pattern for detection
   - :file-pred     - fn [path] -> boolean, selects files to scan
   - :suggest-fn    - fn [matched-text] -> suggested replacement string or nil
   - :detect-mode   - :positive (match = violation) or :negative (absence = violation)"
  {:std/clojure
   {:rule/id       :std/clojure
    :rule/category "210"
    :title         "Clojure Map Access"
    :pattern       #"\(or \(:[a-zA-Z][a-zA-Z0-9?!_*+<>-]*\s+[a-zA-Z][a-zA-Z0-9_-]*\)\s+(?:nil|true|false|\"[^\"]*\"|-?\d+(?:\.\d+)?|\[\]|\{\}|:[a-zA-Z][a-zA-Z0-9_-]*)\)"
    :file-pred     (fn [path]
                     (and (or (str/ends-with? path ".clj")
                              (str/ends-with? path ".cljc"))
                          (or (re-find #"(?:^|/)components/[^/]+/src/" path)
                              (re-find #"(?:^|/)bases/[^/]+/src/" path))))
    :suggest-fn    (fn [matched-text]
                     ;; Parse (or (:k m) v) -> (get m :k v)
                     (when-let [[_ kw m default-val]
                                (re-find
                                 #"\(or \((:[\w?!*+<>-]+)\s+([\w-]+)\)\s+(.+?)\)"
                                 (str matched-text))]
                       (str "(get " m " " kw " " (str/trimr default-val) ")")))
    :detect-mode   :positive}

   :std/datever
   {:rule/id       :std/datever
    :rule/category "730"
    :title         "Version Format (SemVer vs DateVer)"
    :pattern       #"\b(\d+)\.(\d+)\.(\d+)\b(?!\.\d)"
    :file-pred     (fn [path]
                     (or (str/ends-with? path "build.clj")
                         (str/ends-with? path "version.edn")
                         (re-find #"\.github/workflows/[^/]+\.ya?ml$" path)))
    :suggest-fn    (fn [matched-text]
                     ;; SemVer X.Y.Z → DateVer X.Y.Z.N; append build count placeholder
                     (str matched-text ".0"))
    :detect-mode   :positive}

   :std/header-copyright
   {:rule/id       :std/header-copyright
    :rule/category "810"
    :title         "Copyright Header (Markdown)"
    ;; Positive signal looked for in first 10 lines.
    ;; Absence of both name + email patterns = violation.
    :pattern       #"Christopher Lester"
    :email-pattern #"christopher@miniforge\.ai"
    :file-pred     (fn [path]
                     (and (str/ends-with? path ".md")
                          (not (re-find #"(?:^|/)\.git/" path))
                          (not (re-find #"(?:^|/)node_modules/" path))
                          (or (re-find #"(?:^|/)docs/" path)
                              (re-find #"(?:^|/)specs/" path)
                              ;; Root-level .md files (no subdirectory separator)
                              (not (str/includes? path "/")))))
    :suggest-fn    (fn [_] nil)   ; prepending is the fix, no inline replacement
    :detect-mode   :negative}})   ; absence of :pattern = violation

;------------------------------------------------------------------------------ Layer 1
;; Accessors

(defn suggest
  "Return a suggested replacement for a matched violation string, or nil."
  [rule-id matched-text]
  (when-let [cfg (get registry rule-id)]
    (when-let [f (get cfg :suggest-fn)]
      (f matched-text))))

(defn enabled-rule-configs
  "Return the detection configs to run.
   opts may contain :rules — a set of :rule/id keywords, or :all (default)."
  [opts]
  (let [requested (get opts :rules :all)]
    (if (= requested :all)
      (vals registry)
      (->> requested
           (map #(get registry %))
           (filter some?)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (keys registry)
  ;; => (:std/clojure :std/datever :std/header-copyright)

  (suggest :std/clojure "(or (:status m) :pending)")
  ;; => "(get m :status :pending)"

  (suggest :std/datever "1.2.3")
  ;; => "1.2.3.0"

  (enabled-rule-configs {:rules #{:std/clojure :std/header-copyright}})

  :leave-this-here)
