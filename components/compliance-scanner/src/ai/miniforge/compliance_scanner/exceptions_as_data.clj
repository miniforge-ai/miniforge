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

   Layer 0: Pure classification — boundary namespace patterns,
            programmer-error guards, throw-shaped form recognition
   Layer 1: File-level analysis — read forms with location metadata,
            walk recursively, classify each candidate site
   Layer 2: Repo-level scan entry point — enumerate target files,
            aggregate violations as data"
  (:require
   [ai.miniforge.compliance-scanner.factory :as factory]
   [clojure.java.io                          :as io]
   [clojure.string                           :as str]
   [clojure.tools.reader                     :as reader]
   [clojure.tools.reader.reader-types        :as rt]))

;------------------------------------------------------------------------------ Layer 0
;; Rule identity

(def rule-id
  "Stable ID for the exceptions-as-data linter rule."
  :std/exceptions-as-data)

(def rule-category
  "Dewey category for foundations/exceptions-as-data."
  "005")

(def rule-title
  "Human-readable title surfaced in scan output."
  "Exceptions as Data")

(def suggestion
  "One-sentence pointer to the suggested rewrite. Linter is informational
   only — actual rewrites belong to the cleanup workstream and are guided
   by the canonical `ai.miniforge.anomaly.interface/anomaly` constructor."
  (str "Return an anomaly map (ai.miniforge.anomaly.interface/anomaly) "
       "instead of throwing; reserve throws for boundary namespaces and "
       "programmer-error guards."))

;------------------------------------------------------------------------------ Layer 0
;; Boundary-namespace detection
;;
;; Boundary namespaces are exempt from the rule because they sit at the
;; system edge where exception conversion is appropriate. Spec lives in
;; .standards/foundations/exceptions-as-data.mdc — this list mirrors it.

(def ^:private boundary-segment-patterns
  "Namespace segments that mark a boundary file. A file is a boundary
   if any of its dotted-segment substrings matches one of these."
  #{"cli" "boundary" "http" "web" "mcp" "consumer" "listener"})

(def ^:private boundary-suffix-patterns
  "Whole-namespace suffixes that mark boundary files."
  #{"main"})

(defn- ns-segments
  "Split a fully-qualified ns symbol into its dotted segments. Returns []
   when the value is not a usable ns symbol."
  [ns-sym]
  (if (and ns-sym (or (symbol? ns-sym) (string? ns-sym)))
    (str/split (name ns-sym) #"\.")
    []))

(defn boundary-namespace?
  "Return true when the given namespace symbol denotes a boundary file
   per the rule's exemption list. Pure — depends only on the name.

   Boundary segments must appear as standalone dotted segments of the
   namespace (e.g. `ai.miniforge.foo.http.handlers`). Component names
   that merely contain `http` (e.g. `bb-data-plane-http`) are NOT
   boundaries — those components are still required to return anomalies
   internally and only convert at their CLI / handler edge."
  [ns-sym]
  (let [segs     (ns-segments ns-sym)
        last-seg (last segs)]
    (boolean
     (or (some boundary-segment-patterns segs)
         (when last-seg
           (or (contains? boundary-suffix-patterns last-seg)
               (str/ends-with? last-seg "-main")))))))

;------------------------------------------------------------------------------ Layer 0
;; Throw-shape recognition

(def ^:private throw-call-symbols
  "Bare or namespaced symbols whose call position is a throw boundary.
   `throw-anomaly!` is canonical anomaly-as-thrown-data; flagging it
   leaves the per-site judgment to the human reviewer."
  #{'throw 'throw+ 'throw-anomaly! 'response/throw-anomaly!})

(def ^:private throw-class-suffixes
  "Constructor-form class-name suffixes that count as exception
   instantiation regardless of qualifier (e.g. `IllegalArgumentException.`,
   `java.lang.RuntimeException.`)."
  ["Exception." "Error." "Throwable."])

(defn- throw-call?
  "True when `head` is a symbol calling out a throw-shaped operator."
  [head]
  (and (symbol? head)
       (or (contains? throw-call-symbols head)
           (let [bare (symbol (name head))]
             (contains? throw-call-symbols bare)))))

(defn- throw-class?
  "True when `head` is a Class. constructor symbol whose name ends in
   one of the configured exception suffixes."
  [head]
  (and (symbol? head)
       (let [s (name head)]
         (and (str/ends-with? s ".")
              (boolean (some #(str/ends-with? s %) throw-class-suffixes))))))

(defn- ex-info-call?
  "True when `head` is `ex-info` or a namespaced alias for it.
   Matches bare `ex-info` only — `ex-info` is not throw on its own,
   but the inventory counts it as a throw-marker for callers that
   just construct then propagate. We require the `ex-info` to be
   unwrapped (i.e. not the body of a docstring); the AST guarantees
   that for us."
  [head]
  (and (symbol? head)
       (= "ex-info" (name head))))

(defn- throw-shaped-form?
  "Classify a list-form's head as a throw-shaped operator. Returns
   the operator kind keyword or nil."
  [head]
  (cond
    (throw-call? head)   :throw
    (throw-class? head)  :ctor
    (ex-info-call? head) :ex-info
    :else                nil))

;------------------------------------------------------------------------------ Layer 0
;; Programmer-error-guard classification

(def ^:private programmer-error-markers
  "Lowercase substrings within the throw message (or in any keyword /
   symbol name appearing inside the throw expression) that signal a
   programmer error / boot-time guard. Markers come straight from the
   inventory's `:fatal-only` rationale column."
  ["unknown"
   "unsupported"
   "must be"
   "must have"
   "required"
   "requires"
   "expected one of"
   "no parser registered"
   "no implementation"
   "not implemented"
   "should not happen"
   "invariant"
   "missing"
   "missing-resource"
   "classpath"
   "integrity"
   "invalid-config"])

(defn- collect-text
  "Collect every string-shaped piece of evidence — string literals plus
   the names of keywords and symbols — that appears anywhere within
   `form`, walking recursively. The keyword/symbol names are included
   because i18n messages live as keyword tokens (e.g.
   `:config/missing-resource`) and the inventory uses those names as the
   programmer-error signal. Bounded depth keeps the walk cheap."
  ([form] (collect-text form 6))
  ([form depth]
   (cond
     (zero? depth)        []
     (string? form)       [form]
     (keyword? form)      [(str (namespace form) "/" (name form))]
     (symbol? form)       [(name form)]
     (or (seq? form)
         (vector? form)
         (set? form))     (mapcat #(collect-text % (dec depth)) form)
     (map? form)          (mapcat (fn [[k v]]
                                    (concat (collect-text k (dec depth))
                                            (collect-text v (dec depth))))
                                  form)
     :else                [])))

(defn- programmer-error-guard?
  "True when the throw-shaped form looks like a programmer-error guard.
   Signal: any string, keyword, or symbol name inside the throw
   expression matches one of `programmer-error-markers`. The .standards
   rule classifies these as `:fatal-only` — informational, not actionable."
  [form]
  (let [tokens (collect-text form)
        joined (str/lower-case (str/join " " tokens))]
    (boolean (some #(str/includes? joined %) programmer-error-markers))))

;------------------------------------------------------------------------------ Layer 1
;; Reader integration

(defn- indexing-pushback
  "Build an indexing pushback reader from a string. We use the indexing
   reader so each form carries `:line` / `:column` reader-meta, which
   we propagate into the violation map."
  [^String content]
  (rt/indexing-push-back-reader (java.io.PushbackReader.
                                 (java.io.StringReader. content))))

(defn- read-all-forms
  "Read every top-level form from the file content. Returns a vector of
   `[form meta]` pairs where `meta` carries `:line` and `:column`. On
   reader error, returns whatever was read up to the error site rather
   than throwing — the linter is best-effort and refuses to fail loudly
   on tokenizer surprises (per the rule itself: linter eats its own
   dog food)."
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

(defn- form-line
  "Extract `:line` from form metadata, defaulting to 0."
  [form]
  (or (when (instance? clojure.lang.IObj form)
        (:line (meta form)))
      0))

(defn- form-column
  "Extract `:column` from form metadata, defaulting to 0."
  [form]
  (or (when (instance? clojure.lang.IObj form)
        (:column (meta form)))
      0))

(defn- ns-form?
  "True when `form` is a `(ns ...)` declaration."
  [form]
  (and (seq? form)
       (= 'ns (first form))))

(defn- extract-ns-symbol
  "Return the namespace symbol from a vector of top-level forms,
   or nil if none present."
  [forms]
  (some (fn [f]
          (when (ns-form? f)
            (let [n (second f)]
              (when (symbol? n) n))))
        forms))

;------------------------------------------------------------------------------ Layer 1
;; Walk + classify

(defn- form-snippet
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

(defn- classify-throw-site
  "Return the severity classification for a throw-shaped form found in
   a non-boundary namespace. Programmer-error guards are `:fatal-only`
   (informational); everything else is `:cleanup-needed`."
  [form]
  (if (programmer-error-guard? form)
    :fatal-only
    :cleanup-needed))

(defn- ->violation-record
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

(def ^:private skip-walk-heads
  "Forms whose contents are not analyzed. `comment` is a Rich Comment
   block and its body is dev-only. The inventory excludes these."
  #{'comment 'clojure.core/comment})

(defn- visit-form
  "Walk a single form and conj violation records onto `acc!` (a transient
   vector). Recurses into seqable children, including vectors and maps.

   Counting policy: a throw-shaped form is recorded once. We then stop
   recursing into its children — `(throw (ex-info ...))` is one site, not
   two. Standalone `(ex-info ...)` outside of a `throw` still counts on
   its own. `(comment ...)` blocks are skipped entirely.

   `boundary?` is computed once at the file level — when true, the file
   yields no violations regardless of contents."
  [acc! file-path boundary? form]
  (cond
    boundary?
    acc!

    (seq? form)
    (let [head (first form)]
      (cond
        (and (symbol? head) (contains? skip-walk-heads head))
        acc!

        (throw-shaped-form? head)
        (conj! acc! (->violation-record file-path form
                                        (classify-throw-site form)
                                        (throw-shaped-form? head)))

        :else
        (reduce (fn [a child] (visit-form a file-path boundary? child))
                acc!
                form)))

    (or (vector? form) (set? form))
    (reduce (fn [a child] (visit-form a file-path boundary? child))
            acc!
            form)

    (map? form)
    (reduce (fn [a [k v]]
              (-> a
                  (visit-form file-path boundary? k)
                  (visit-form file-path boundary? v)))
            acc!
            form)

    :else
    acc!))

(defn analyze-content
  "Analyze a single Clojure source file's content. Returns a map with:

     :ns           — the resolved namespace symbol (or nil)
     :boundary?    — true when the namespace is exempt
     :violations   — vector of violation records (line/col/kind/classification)

   Pure: no I/O. Caller passes content; we don't open files here so the
   function is trivially testable."
  [file-path ^String content]
  (let [forms      (read-all-forms content)
        ns-sym     (extract-ns-symbol forms)
        boundary?  (boundary-namespace? ns-sym)
        violations (persistent!
                    (reduce (fn [a f] (visit-form a file-path boundary? f))
                            (transient [])
                            forms))]
    {:ns         ns-sym
     :boundary?  boundary?
     :violations violations}))

;------------------------------------------------------------------------------ Layer 2
;; File enumeration and scan entry point

(defn- target-file?
  "True when the path matches `components/*/src/**/*.clj` or
   `bases/*/src/**/*.clj`. Repo-relative path expected."
  [^String relative-path]
  (and (or (str/starts-with? relative-path "components/")
           (str/starts-with? relative-path "bases/"))
       (or (str/ends-with? relative-path ".clj")
           (str/ends-with? relative-path ".cljc"))
       (str/includes? relative-path "/src/")))

(defn- list-target-files
  "Walk the repo root and return repo-relative paths for every Clojure
   source file under components/*/src or bases/*/src. Test files are
   intentionally excluded — the rule is about production source."
  [repo-root]
  (let [root (io/file repo-root)
        root-len (inc (count (.getAbsolutePath root)))]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (map (fn [^java.io.File f]
                (let [abs (.getAbsolutePath f)
                      rel (if (>= (count abs) root-len)
                            (subs abs root-len)
                            abs)]
                  rel)))
         (filter target-file?)
         vec)))

(defn- format-violation
  "Build a public Violation map (per compliance-scanner schema) from an
   internal record. Severity policy: every emit defaults to `:warning`,
   even `:fatal-only` rows — the latter just carry that classification
   in their rationale so consumers can filter."
  [{:keys [file line column kind classification snippet]}]
  (let [kind-name (case kind
                    :throw   "throw"
                    :ctor    "exception ctor"
                    :ex-info "ex-info"
                    "throw-shaped")
        rationale (case classification
                    :fatal-only
                    (str kind-name " classified :fatal-only "
                         "(programmer-error guard); informational only")
                    (str kind-name " outside boundary namespace; "
                         "consider returning an anomaly map"))]
    (-> (factory/->violation
         rule-id rule-category rule-title
         file (or line 1)
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

(defn analyze-file
  "Analyze a single file by absolute or relative path. Returns a vector
   of public Violation maps."
  [repo-root relative-path]
  (let [abs (io/file repo-root relative-path)]
    (if (.isFile abs)
      (let [content (try (slurp abs) (catch Exception _ nil))]
        (if content
          (mapv format-violation (:violations (analyze-content relative-path content)))
          []))
      [])))

(defn scan-repo
  "Scan a repository for exceptions-as-data violations.

   Arguments:
   - repo-root - absolute or relative path to the repo root.

   Returns a map:
     :violations    - vector of Violation maps (severity :warning)
     :files-scanned - count of files inspected
     :rule/id       - rule identifier
     :counts        - {:cleanup-needed N :fatal-only M}

   Pure data. The function does not write reports, does not throw, and
   does not call System/exit — this is the linter, not a gate."
  [repo-root]
  (let [files       (list-target-files repo-root)
        per-file    (mapv (fn [rel] (analyze-file repo-root rel)) files)
        violations  (vec (apply concat per-file))
        cleanup     (count (filter #(= :cleanup-needed (:classification %)) violations))
        fatal       (count (filter #(= :fatal-only (:classification %)) violations))]
    {:violations    violations
     :files-scanned (count files)
     :rule/id       rule-id
     :counts        {:cleanup-needed cleanup
                     :fatal-only     fatal}}))

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
