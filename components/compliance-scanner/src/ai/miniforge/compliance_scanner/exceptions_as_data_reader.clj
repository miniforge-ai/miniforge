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
(ns ai.miniforge.compliance-scanner.exceptions-as-data-reader
  "Clojure-reader integration, path-safe file enumeration, and the
   internal→public violation-record builders. Split out of
   `exceptions-as-data` (rule 210: an eighth real layer there is the
   signal to split it).

   Layer 0: Form-meta accessors, path-safety primitives, output
            formatting (all independent of one another)
   Layer 1: Whole-file form reading, ns-symbol extraction, violation-record
            construction, and repo file enumeration"
  (:require [ai.miniforge.compliance-scanner.factory     :as factory]
            [clojure.java.io                             :as io]
            [clojure.string                              :as str]
            [clojure.tools.reader                        :as reader]
            [clojure.tools.reader.reader-types           :as rt]))

;------------------------------------------------------------------------------ Layer 0

;; Reader integration
(defn- ^{:stratum 0} indexing-pushback
  "Build an indexing pushback reader from a string. We use the indexing
   reader so each form carries `:line` / `:column` reader-meta, which
   we propagate into the violation map."
  [^String content]
  (rt/indexing-push-back-reader (java.io.PushbackReader.
                                 (java.io.StringReader. content))))

(defn ^{:stratum 0} form-line
  "Extract `:line` from form metadata, defaulting to 0."
  [form]
  (or (when (instance? clojure.lang.IObj form)
        (:line (meta form)))
      0))

(defn ^{:stratum 0} form-column
  "Extract `:column` from form metadata, defaulting to 0."
  [form]
  (or (when (instance? clojure.lang.IObj form)
        (:column (meta form)))
      0))

(defn- ^{:stratum 0} ns-form?
  "True when `form` is a `(ns ...)` declaration."
  [form]
  (and (seq? form)
       (= 'ns (first form))))

;; Walk + classify
(defn ^{:stratum 0} form-snippet
  "Render a short single-line snippet of the form for the violation's
   `:current` field. Truncated to keep CLI output readable."
  [form]
  (let [s (try (pr-str form)
               (catch Exception _ "<unprintable>"))
        one-line (-> s
                     (str/replace #"\s+" " ")
                     str/trim)
        max-len 120]
    (if (> (count one-line) max-len)
      (str (subs one-line 0 max-len) " …")
      one-line)))

;; File enumeration and scan entry point
(defn ^{:stratum 0} target-file?
  "True when the path matches `components/*/src/**/*.clj` or
   `bases/*/src/**/*.clj`. Repo-relative path expected."
  [^String relative-path]
  (and (or (str/starts-with? relative-path "components/")
           (str/starts-with? relative-path "bases/"))
       (or (str/ends-with? relative-path ".clj")
           (str/ends-with? relative-path ".cljc"))
       (str/includes? relative-path "/src/")))

(defn- ^{:stratum 0} normalize-separators
  "Convert platform path separators to forward slash. `target-file?`
   matches against forward-slash-delimited segments (`components/`,
   `/src/`), which is the canonical Polylith convention. On Windows,
   `getAbsolutePath` returns backslashes; without normalization the
   linter would scan zero files. On Linux/macOS this is a no-op."
  [^String path]
  (if (= "\\" java.io.File/separator)
    (str/replace path "\\" "/")
    path))

(defn ^{:stratum 0} within-root?
  "Return true iff the canonical path of `candidate` starts with the
   canonical path of `root` followed by the system file separator.
   Uses canonical paths to resolve symlinks and `..` segments before
   the comparison, preventing path-traversal via relative-path inputs.

   Returns false (safe default) when .getCanonicalPath throws
   IOException or SecurityException so the caller's exceptions-as-data
   contract is preserved."
  [^java.io.File root ^java.io.File candidate]
  (try
    (let [canonical-root      (.getCanonicalPath root)
          canonical-candidate (.getCanonicalPath candidate)
          sep                 java.io.File/separator
          prefix              (if (str/ends-with? canonical-root sep)
                                canonical-root
                                (str canonical-root sep))]
      (str/starts-with? canonical-candidate prefix))
    (catch Exception _
      false)))

(defn ^{:stratum 0} format-violation
  "Build a public Violation map (per compliance-scanner schema) from an
   internal record. Severity policy: every emit defaults to `:warning`,
   even `:fatal-only` rows — the latter just carry that classification
   in their rationale so consumers can filter."
  [{:keys [file line column kind classification snippet]} rule-id rule-category rule-title suggestion]
  (let [kind-name (case kind
                    :throw   "throw"
                    :ctor    "exception ctor"
                    :ex-info "ex-info"
                    "throw-shaped")
        rationale (case classification
                    :fatal-only
                    (str kind-name " classified :fatal-only "
                         "(programmer-error guard); informational only")
                    :local-boundary
                    (str kind-name " inside documented local boundary wrapper; "
                         "informational only")
                    (str kind-name " outside boundary namespace; "
                         "consider returning an anomaly map"))]
    (-> (factory/->violation
         rule-id rule-category rule-title
         file
         ;; Clamp line to a positive integer. `(or line 1)` does not
         ;; suffice — a literal 0 from `form-line`'s default is truthy
         ;; and would propagate through, producing invalid `file:0:col`
         ;; locations in output.
         (max 1 (long (or line 1)))
         snippet
         suggestion
         false             ; never auto-fixable; cleanup is a human pass
         rationale)
        (assoc :severity             :warning
               :column               (or column 0)
               :classification       classification
               ;; Tell the classify phase to leave :auto-fixable? false:
               ;; the linter is a human-review gate, not a mechanical fix.
               :auto-fixable-default false))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-all-forms
  "Read every top-level form from the file content.

   Returns a vector of forms. The indexing reader attaches `:line` and
   `:column` to each form's metadata, accessible via `clojure.core/meta`
   (see `form-line` / `form-column`). On reader error, returns whatever
   was read up to the error site rather than throwing — the linter is
   best-effort and refuses to fail loudly on tokenizer surprises (per
   the rule itself: linter eats its own dog food)."
  [^String content]
  (let [eof    (Object.)
        rdr    (indexing-pushback content)
        opts   {:eof eof :read-cond :allow :features #{:clj}}]
    (loop [acc (transient [])]
      (let [form (try (reader/read opts rdr)
                      (catch Exception _ eof))]
        (if (identical? form eof)
          (persistent! acc)
          (recur (conj! acc form)))))))

(defn ^{:stratum 1} extract-ns-symbol
  "Return the namespace symbol from a vector of top-level forms,
   or nil if none present."
  [forms]
  (some (fn [f]
          (when (ns-form? f)
            (let [n (second f)]
              (when (symbol? n) n))))
        forms))

(defn ^{:stratum 1} ->violation-record
  "Construct an internal violation record. Severity is always informational
   — exceptions-as-data is `:warning`, never `:error`, until cleanup
   completes."
  [file-path form classification kind]
  {:file       file-path
   :line       (form-line form)
   :column     (form-column form)
   :kind       kind
   :classification classification
   :snippet    (form-snippet form)})

(defn ^{:stratum 1} list-target-files
  "Walk the repo root and return repo-relative paths for every Clojure
   source file under components/*/src or bases/*/src. Test files are
   intentionally excluded — the rule is about production source.

   Paths are normalized to forward-slash separators so `target-file?`
   matches consistently on Windows and POSIX hosts."
  [repo-root]
  (let [root     (io/file repo-root)
        root-len (inc (count (.getAbsolutePath root)))]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (map (fn [^java.io.File f]
                (let [abs (.getAbsolutePath f)
                      rel (if (>= (count abs) root-len)
                            (subs abs root-len)
                            abs)]
                  (normalize-separators rel))))
         (filter target-file?)
         vec)))
