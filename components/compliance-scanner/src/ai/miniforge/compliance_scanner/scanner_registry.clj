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

   Layer 0: File predicates and suggest functions
   Layer 1: Registry data
   Layer 2: Accessor helpers"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File predicates

(defn clojure-source-file?
  "Return true if path is a Clojure source file in a component or base src tree."
  [path]
  (and (or (str/ends-with? path ".clj")
           (str/ends-with? path ".cljc"))
       (or (re-find #"(?:^|/)components/[^/]+/src/" path)
           (re-find #"(?:^|/)bases/[^/]+/src/" path))))

(defn version-file?
  "Return true if path is a version declaration or CI workflow file."
  [path]
  (or (str/ends-with? path "build.clj")
      (str/ends-with? path "version.edn")
      (boolean (re-find #"\.github/workflows/[^/]+\.ya?ml$" path))))

(defn markdown-doc-file?
  "Return true if path is a tracked markdown file in docs/, specs/, or at repo root."
  [path]
  (and (str/ends-with? path ".md")
       (not (re-find #"(?:^|/)\.git/" path))
       (not (re-find #"(?:^|/)node_modules/" path))
       (or (re-find #"(?:^|/)docs/" path)
           (re-find #"(?:^|/)specs/" path)
           (not (str/includes? path "/")))))

;------------------------------------------------------------------------------ Layer 0
;; Suggest functions

(defn suggest-clojure-map-access
  "Return a (get m :k v) replacement for a matched (or (:k m) v) form, or nil."
  [matched-text]
  (when-let [[_ kw m default-val]
             (re-find #"\(or \((:[\w?!*+<>-]+)\s+([\w-]+)\)\s+(.+?)\)"
                      (str matched-text))]
    (str "(get " m " " kw " " (str/trimr default-val) ")")))

(defn suggest-datever
  "Return nil — SemVer→DateVer conversion requires human context (release date)."
  [_]
  nil)

(defn suggest-copyright-header
  "Return nil — prepending the header is the fix; no inline replacement exists."
  [_]
  nil)

;------------------------------------------------------------------------------ Layer 1
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
    :file-pred     clojure-source-file?
    :suggest-fn    suggest-clojure-map-access
    :detect-mode   :positive}

   :std/datever
   {:rule/id       :std/datever
    :rule/category "730"
    :title         "Version Format (SemVer vs DateVer)"
    ;; Match X.Y.Z where first segment is NOT a 4-digit year (2000-2099).
    ;; This excludes DateVer strings like 2026.01.20.1 from false-positive detection.
    :pattern       #"\b(?!20\d\d\.)(\d{1,3})\.(\d+)\.(\d+)\b(?!\.\d)"
    :file-pred     version-file?
    :suggest-fn    suggest-datever
    :detect-mode   :positive}

   :std/header-copyright
   {:rule/id       :std/header-copyright
    :rule/category "810"
    :title         "Copyright Header (Markdown)"
    ;; Positive signal looked for in first 10 lines.
    ;; Absence of both name + email patterns = violation.
    :pattern       #"Christopher Lester"
    :email-pattern #"christopher@miniforge\.ai"
    :file-pred     markdown-doc-file?
    :suggest-fn    suggest-copyright-header
    :detect-mode   :negative}})

;------------------------------------------------------------------------------ Layer 2
;; Accessors

(defn suggest
  "Return a suggested replacement for a matched violation string, or nil."
  [rule-id matched-text]
  (when-let [cfg (get registry rule-id)]
    (when-let [f (get cfg :suggest-fn)]
      (f matched-text))))

(defn enabled-rule-configs
  "Return the detection configs to run.
   opts may contain :rules — a set of :rule/id keywords, :all, or :always-apply (default)."
  [opts]
  (let [raw       (get opts :rules :always-apply)
        requested (if (string? raw) (keyword (subs raw 1)) raw)]
    (if (contains? #{:all :always-apply} requested)
      (vals registry)
      (->> (if (keyword? requested) #{requested} requested)
           (map #(get registry %))
           (filter some?)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (keys registry)
  ;; => (:std/clojure :std/datever :std/header-copyright)

  (suggest :std/clojure "(or (:status m) :pending)")
  ;; => "(get m :status :pending)"

  (suggest :std/datever "1.2.3")
  ;; => nil (needs human review)

  (clojure-source-file? "components/foo/src/ai/miniforge/foo/core.clj")
  ;; => true
  (version-file? "build.clj")
  ;; => true
  (markdown-doc-file? "docs/guide.md")
  ;; => true

  (enabled-rule-configs {:rules #{:std/clojure :std/header-copyright}})

  :leave-this-here)
