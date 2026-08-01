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
(ns ai.miniforge.compliance-scanner.named-constants
  "Named-constants locator (Dewey 006) — deterministic MEASUREMENT tool.

   Locates magic NUMERIC literals in Clojure source: numbers whose value is
   not a structural sentinel. It is a repeatable, LLM-free replacement for the
   semantic judge when counting/locating the named-constants backlog. It does
   NOT fix (extraction needs a name + intent docstring — a judgment call), and
   it is a LOCATOR, not a gate: it deliberately over-reports (it does not model
   the rule's every semantic exemption — math identities, self-documenting
   keywords), so its output is a triage list.

   The char-lexer + numeric classification live in `named-constants-lexer`
   (rule 210: a sixth real layer here is the signal to split it).
   Structured-string magic literals (paths, format placeholders) are out
   of scope here; the judge still covers those.

   Layer 0: Rule identity + file-path helpers
   Layer 1: Per-file violation mapping + repo file listing
   Layer 2: Top-level repo scan entry point"
  (:require
   [ai.miniforge.compliance-scanner.named-constants-lexer :as lexer]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} rule-id :std/named-constants)

(def ^{:stratum 0} rule-category "006")

(defn- ^{:stratum 0} normalize-separators [^String p] (str/replace p "\\" "/"))

(defn- ^{:stratum 0} target-file? [rel]
  (and (str/ends-with? rel ".clj")
       (or (re-find #"^components/[^/]+/src/" rel)
           (re-find #"^bases/[^/]+/src/" rel))))

(defn- ^{:stratum 0} safe-slurp
  "Read a file, returning its content or nil on any I/O error (permissions,
   encoding, transient failure). The locator is best-effort — a single
   unreadable file must not abort the whole scan."
  [f]
  (try (slurp f) (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

;; File + repo scan
(defn ^{:stratum 1} analyze-content
  "Return violation maps for magic numeric literals in `content`.
   `rel` is the repo-relative path used in the record."
  [rel ^String content]
  (mapv (fn [{:keys [line col token]}]
          {:rule/id  rule-id
           :rule/category rule-category
           :file     rel
           :line     line
           :column   col
           :current  token
           :kind     :magic-numeric})
        (lexer/scan-numeric-tokens content)))

(defn- ^{:stratum 1} list-target-files [repo-root]
  (let [root (io/file repo-root)
        root-len (inc (count (.getAbsolutePath root)))]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (map (fn [^java.io.File f]
                (normalize-separators (subs (.getAbsolutePath f) root-len))))
         (filter target-file?)
         vec)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scan-repo
  "Scan `repo-root` for magic numeric literals. Returns
   {:violations [...] :files-scanned N :rule/id :std/named-constants
    :counts {:magic-numeric N}}. Pure locator: no writes, no throw, no exit —
   an unreadable file is skipped, not raised."
  ([repo-root] (scan-repo repo-root nil))
  ([repo-root changed-files]
   (let [files (cond->> (list-target-files repo-root)
                 changed-files (filterv (set changed-files)))
         violations (vec (mapcat (fn [rel]
                                   (if-let [content (safe-slurp (io/file repo-root rel))]
                                     (analyze-content rel content)
                                     []))
                                 files))]
     {:violations    violations
      :files-scanned (count files)
      :rule/id       rule-id
      :counts        {:magic-numeric (count violations)}})))
