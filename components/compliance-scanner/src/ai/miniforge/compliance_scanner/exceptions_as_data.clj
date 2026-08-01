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
(ns ai.miniforge.compliance-scanner.exceptions-as-data
  "Exceptions-as-data linter (Dewey 005).

   Scans Clojure source under components/*/src and bases/*/src for
   throw-shaped forms that should instead return canonical anomalies
   per `ai.miniforge.anomaly.interface`.

   This is a Clojure-aware AST scan, not a regex scan: we read each
   file with `clojure.tools.reader` so docstrings, comment forms, and
   string literals containing the word `throw` cannot trigger false
   positives.

   Pure classification, reader/file-enumeration primitives, and the
   recursive form walk live in `exceptions-as-data-classify`,
   `exceptions-as-data-reader`, and `exceptions-as-data-walk`
   respectively (rule 210: an eighth real layer here is the signal to
   split it).

   Layer 0: Rule identity + single-file content analysis
   Layer 1: Single-file path-safe analysis entry point
   Layer 2: Top-level repo scan entry point"
  (:require
   [ai.miniforge.compliance-scanner.exceptions-as-data-classify :as classify]
   [ai.miniforge.compliance-scanner.exceptions-as-data-reader   :as reader]
   [ai.miniforge.compliance-scanner.exceptions-as-data-walk     :as walk]
   [clojure.java.io                                              :as io]))

;------------------------------------------------------------------------------ Layer 0

;; Rule identity
(def ^{:stratum 0} rule-id
  :std/exceptions-as-data)

(def ^{:stratum 0} rule-category
  "Dewey category for foundations/exceptions-as-data."
  "005")

(def ^{:stratum 0} rule-title
  "Human-readable title surfaced in scan output."
  "Exceptions as Data")

(def ^{:stratum 0} suggestion
  "One-sentence pointer to the suggested rewrite. Linter is informational
   only — actual rewrites belong to the cleanup workstream and are guided
   by the canonical `ai.miniforge.anomaly.interface/anomaly` constructor."
  (str "Return an anomaly map (ai.miniforge.anomaly.interface/anomaly) "
       "instead of throwing; reserve throws for boundary namespaces and "
       "programmer-error guards."))

(defn ^{:stratum 0} analyze-content
  "Analyze a single Clojure source file's content. Returns a map with:

     :ns           — the resolved namespace symbol (or nil)
     :boundary?    — true when the namespace is exempt
     :violations   — vector of violation records (line/col/kind/classification)

   Pure: no I/O. Caller passes content; we don't open files here so the
   function is trivially testable."
  [file-path ^String content]
  (let [forms      (reader/read-all-forms content)
        ns-sym     (reader/extract-ns-symbol forms)
        boundary?  (classify/boundary-namespace? ns-sym)
        violations (persistent!
                    (reduce (fn [a f] (walk/visit-form a file-path boundary? f))
                            (transient [])
                            forms))]
    {:ns         ns-sym
     :boundary?  boundary?
     :violations violations}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} analyze-file
  "Analyze a single file by absolute or relative path. Returns a vector
   of public Violation maps.

   Returns an empty vector without reading the file when `relative-path`
   escapes `repo-root` — this prevents path-traversal attacks via inputs
   such as \"../../../etc/passwd\" or absolute paths like \"/etc/passwd\".
   Canonical paths resolve symlinks and `..` segments before comparison."
  [repo-root relative-path]
  (let [root (io/file repo-root)
        abs  (try (io/file root relative-path)
                  (catch IllegalArgumentException _
                    ;; clojure.java.io/file rejects absolute second arguments;
                    ;; treat as traversal attempt.
                    nil))]
    (if (or (nil? abs) (not (reader/within-root? root abs)))
      (do (binding [*out* *err*]
            (println (str "[compliance-scanner] WARN path-traversal rejected: "
                          relative-path " escapes repo-root " repo-root)))
          [])
      (if (.isFile abs)
        (let [content (try (slurp abs) (catch Exception _ nil))]
          (if content
            (mapv #(reader/format-violation % rule-id rule-category rule-title suggestion)
                  (:violations (analyze-content relative-path content)))
            []))
        []))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scan-repo
  "Scan a repository for exceptions-as-data violations.

   Arguments:
   - repo-root        - absolute or relative path to the repo root
   - changed-files    - (optional) set of repo-relative path strings;
                        when present, restricts the scan to that set.
                        Used by `bb review --since` for incremental
                        runs so the linter doesn't full-scan when other
                        rules are diff-limited.

   Returns a map:
     :violations    - vector of Violation maps (severity :warning)
     :files-scanned - count of files inspected
     :rule/id       - rule identifier
     :counts        - {:cleanup-needed N :fatal-only M :local-boundary K}

   Pure data. The function does not write reports, does not throw, and
   does not call System/exit — this is the linter, not a gate."
  ([repo-root] (scan-repo repo-root nil))
  ([repo-root changed-files]
   (let [all-files   (reader/list-target-files repo-root)
         files       (cond->> all-files
                       changed-files (filterv (set changed-files)))
         per-file    (mapv (fn [rel] (analyze-file repo-root rel)) files)
         violations  (vec (apply concat per-file))
         cleanup     (count (filter #(= :cleanup-needed (:classification %)) violations))
         fatal       (count (filter #(= :fatal-only (:classification %)) violations))
         local-boundary (count (filter #(= :local-boundary (:classification %)) violations))]
     {:violations    violations
      :files-scanned (count files)
      :rule/id       rule-id
      :counts        {:cleanup-needed cleanup
                      :fatal-only     fatal
                      :local-boundary local-boundary}})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run the linter against the current repo.
  (def result (scan-repo "."))
  (count (:violations result))
  (:counts result)

  ;; Inspect a single file.
  (analyze-file "." "components/agent/src/ai/miniforge/agent/role_config.clj")

  ;; Pure analysis — no I/O.
  (analyze-content "demo.clj"
                   "(ns demo) (defn boom [] (throw (ex-info \"x\" {})))")

  :leave-this-here)
