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
            local boundary wrappers, programmer-error guards,
            throw-shaped form recognition
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

;; Boundary-namespace detection
;;
;; Boundary namespaces are exempt from the rule because they sit at the
;; system edge where exception conversion is appropriate. Spec lives in
;; .standards/foundations/exceptions-as-data.mdc — this list mirrors it.
(def ^{:stratum 0} ^:private boundary-segment-patterns
  "Namespace segments that mark a boundary file. A file is a boundary
   if any of its dotted segments matches one of these."
  #{"cli" "boundary" "http" "web" "mcp" "consumer" "listener" "listeners"})

(def ^{:stratum 0} ^:private boundary-prefix-patterns
  "Whole namespace prefixes that mark boundary bases whose Polylith name
   cannot be represented as a standalone dotted segment. Keep this list
   narrow so component names that merely contain boundary words, such as
   `bb-data-plane-http`, remain non-boundary.

   `ai.miniforge.response.anomaly` is the repository's canonical
   thrown-anomaly bridge; it is intentionally a boundary even though the
   component name is not a generic protocol segment."
  #{"ai.miniforge.mcp-context-server"
    "ai.miniforge.response.anomaly"})

(def ^{:stratum 0} ^:private boundary-suffix-patterns
  "Whole-namespace suffixes that mark boundary files."
  #{"main"})

(defn- ^{:stratum 0} ns-segments
  "Split a fully-qualified ns symbol or string into its dotted segments.
   Returns [] when the value is not a usable ns value."
  [ns-sym]
  (if (and ns-sym (or (symbol? ns-sym) (string? ns-sym)))
    (str/split (str ns-sym) #"\.")
    []))

;; Throw-shape recognition
(def ^{:stratum 0} ^:private throw-call-symbols
  "Bare or namespaced symbols whose call position is a throw boundary.
   `throw-anomaly!` is canonical anomaly-as-thrown-data; flagging it
   leaves the per-site judgment to the human reviewer."
  #{'throw 'throw+ 'throw-anomaly! 'response/throw-anomaly!})

(def ^{:stratum 0} ^:private throw-class-suffixes
  "Constructor-form class-name suffixes that count as exception
   instantiation regardless of qualifier (e.g. `IllegalArgumentException.`,
   `java.lang.RuntimeException.`)."
  ["Exception." "Error." "Throwable."])

(defn- ^{:stratum 0} ex-info-call?
  "True when `head` is a symbol whose unqualified `name` is `ex-info`.

   This matches:
   - bare `ex-info`
   - namespaced calls like `clojure.core/ex-info`
   - any local rename whose final segment is `ex-info`

   `ex-info` on its own is not a throw, but the inventory counts it as a
   throw-marker for callers that just construct then propagate. The AST
   read guarantees we are looking at code (not a docstring or string
   literal containing the word)."
  [head]
  (and (symbol? head)
       (= "ex-info" (name head))))

;; Programmer-error-guard classification
(def ^{:stratum 0} ^:private programmer-error-markers
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
   "non-fn"
   "non-keyword"
   "classpath"
   "integrity"
   "invalid-config"
   "not-function"
   "unregistered-at-resolve"
   "unmapped"
   "no matching"])

(defn- ^{:stratum 0} collect-text
  "Collect every string-shaped piece of evidence — string literals plus
   the names of keywords and symbols — that appears anywhere within
   `form`, walking recursively. The keyword/symbol names are included
   because i18n messages live as keyword tokens (e.g.
   `:config/missing-resource`) and the inventory uses those names as the
   programmer-error signal. Bounded depth keeps the walk cheap."
  ([form] (collect-text form 8))
  ([form depth]
   (cond
     (zero? depth)        []
     (string? form)       [form]
     (keyword? form)      [(if-let [ns (namespace form)]
                             (str ns "/" (name form))
                             (name form))]
     (symbol? form)       [(name form)]
     (or (seq? form)
         (vector? form)
         (set? form))     (mapcat #(collect-text % (dec depth)) form)
     (map? form)          (mapcat (fn [[k v]]
                                    (concat (collect-text k (dec depth))
                                            (collect-text v (dec depth))))
                                  form)
     :else                [])))

;; Local boundary-wrapper classification
(defn- ^{:stratum 0} defn-form?
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{"defn" "defn-"} (name (first form)))))

(defn- ^{:stratum 0} bang-symbol?
  [sym]
  (and (symbol? sym)
       (str/ends-with? (name sym) "!")))

;; Reader integration
(defn- ^{:stratum 0} indexing-pushback
  "Build an indexing pushback reader from a string. We use the indexing
   reader so each form carries `:line` / `:column` reader-meta, which
   we propagate into the violation map."
  [^String content]
  (rt/indexing-push-back-reader (java.io.PushbackReader.
                                 (java.io.StringReader. content))))

(defn- ^{:stratum 0} form-line
  "Extract `:line` from form metadata, defaulting to 0."
  [form]
  (or (when (instance? clojure.lang.IObj form)
        (:line (meta form)))
      0))

(defn- ^{:stratum 0} form-column
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
(defn- ^{:stratum 0} form-snippet
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

(defn- ^{:stratum 0} simple-rethrow?
  [form]
  (and (seq? form)
       (= 'throw (first form))
       (= 2 (count form))
       (symbol? (second form))))

(defn- ^{:stratum 0} throw-anomaly-form?
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "throw-anomaly!" (name (first form)))))

(def ^{:stratum 0} ^:private cleanup-rethrow-markers
  "Cleanup operations that make a same-catch rethrow informational.

   This is deliberately narrow: a log-and-rethrow remains actionable, while
   scheduler shutdown and future cancellation preserve resources before
   propagating the original failure."
  #{"shutdownNow" ".shutdownNow" "future-cancel"})

(def ^{:stratum 0} ^:private skip-walk-heads
  "Forms whose contents are not analyzed. `comment` is a Rich Comment
   block and its body is dev-only. The inventory excludes these."
  #{'comment 'clojure.core/comment})

(defn- ^{:stratum 0} interrupted-exception-catch?
  [form]
  (and (seq? form)
       (= 'catch (first form))
       (let [class-sym (second form)]
         (and (symbol? class-sym)
              (= "InterruptedException" (name class-sym))))))

;; File enumeration and scan entry point
(defn- ^{:stratum 0} target-file?
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

(defn- ^{:stratum 0} within-root?
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

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} boundary-prefix?
  [ns-sym]
  (let [ns-str (when (and ns-sym (or (symbol? ns-sym) (string? ns-sym)))
                 (str ns-sym))]
    (boolean
     (when ns-str
       (some (fn [prefix]
               (or (= ns-str prefix)
                   (str/starts-with? ns-str (str prefix "."))))
             boundary-prefix-patterns)))))

(defn- ^{:stratum 1} throw-call?
  "True when `head` is a symbol calling out a throw-shaped operator."
  [head]
  (and (symbol? head)
       (or (contains? throw-call-symbols head)
           (let [bare (symbol (name head))]
             (contains? throw-call-symbols bare)))))

(defn- ^{:stratum 1} throw-class?
  "True when `head` is a Class. constructor symbol whose name ends in
   one of the configured exception suffixes."
  [head]
  (and (symbol? head)
       (let [s (name head)]
         (and (str/ends-with? s ".")
              (boolean (some #(str/ends-with? s %) throw-class-suffixes))))))

(defn- ^{:stratum 1} programmer-error-guard?
  "True when the throw-shaped form looks like a programmer-error guard.
   Signal: any string, keyword, or symbol name inside the throw
   expression matches one of `programmer-error-markers`. The .standards
   rule classifies these as `:fatal-only` — informational, not actionable."
  [form]
  (let [tokens (collect-text form)
        joined (str/lower-case (str/join " " tokens))]
    (boolean (some #(str/includes? joined %) programmer-error-markers))))

(defn- ^{:stratum 1} defn-context
  "Extract the local function name, docstring, and metadata from a defn form.
   This is intentionally small: enough for classifier context, not a full
   defn parser."
  [form]
  (when (defn-form? form)
    (let [name-sym (second form)
          tail     (nnext form)
          [doc tail'] (if (string? (first tail))
                        [(first tail) (next tail)]
                        [nil tail])
          attr-map (when (map? (first tail')) (first tail'))
          metadata (merge (meta name-sym) attr-map)]
      {:defn-name name-sym
       :defn-doc  doc
       :defn-meta metadata})))

(defn- ^{:stratum 1} local-boundary-wrapper?
  "True when the enclosing defn is a documented local compatibility boundary.

  Whole boundary namespaces are exempt earlier. This narrower classifier
  covers the post-cleanup shape used inside ordinary component namespaces:
  a canonical anomaly-returning function plus a retained thrower that bridges
  old exception callers. Undocumented throwers remain `:cleanup-needed`."
  [context]
  (let [doc-value (get context :defn-doc)
        doc-text  (str/lower-case (if (string? doc-value) doc-value ""))
        meta-text (str/lower-case (str/join " " (collect-text (:defn-meta context))))
        evidence  (str doc-text " " meta-text)]
    (boolean
     (or
      (and (str/includes? evidence "exceptions-as-data")
           (str/includes? evidence "prefer"))
      (and (str/includes? doc-text "boundary")
           (or (str/includes? doc-text "anomaly-returning")
               (str/includes? doc-text "canonical")))
      (and (str/includes? doc-text "throws via")
           (str/includes? doc-text "anomaly-returning"))
      (and (bang-symbol? (:defn-name context))
           (str/includes? doc-text "anomaly")
           (or (str/includes? doc-text "boundary")
               (str/includes? doc-text "legacy")
               (str/includes? doc-text "compat")
               (str/includes? doc-text "prefer")))))))

(defn- ^{:stratum 1} read-all-forms
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

(defn- ^{:stratum 1} extract-ns-symbol
  "Return the namespace symbol from a vector of top-level forms,
   or nil if none present."
  [forms]
  (some (fn [f]
          (when (ns-form? f)
            (let [n (second f)]
              (when (symbol? n) n))))
        forms))

(defn- ^{:stratum 1} cleanup-call?
  [form]
  (boolean
   (some cleanup-rethrow-markers
         (map name (filter symbol? (tree-seq coll? seq form))))))

(defn- ^{:stratum 1} ->violation-record
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

(defn- ^{:stratum 1} interrupted-catch-binding
  [form]
  (when (interrupted-exception-catch? form)
    (let [binding-sym (nth form 2 nil)]
      (when (symbol? binding-sym)
        binding-sym))))

(defn- ^{:stratum 1} list-target-files
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

(defn- ^{:stratum 1} format-violation
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} boundary-namespace?
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
         (boundary-prefix? ns-sym)
         (when last-seg
           (or (contains? boundary-suffix-patterns last-seg)
               (str/ends-with? last-seg "-main")))))))

(defn- ^{:stratum 2} throw-shaped-form?
  "Classify a list-form's head as a throw-shaped operator. Returns
   the operator kind keyword or nil."
  [head]
  (cond
    (throw-call? head)   :throw
    (throw-class? head)  :ctor
    (ex-info-call? head) :ex-info
    :else                nil))

(defn- ^{:stratum 2} cleanup-before-rethrow?
  [catch-form binding-sym]
  (let [body (drop 3 catch-form)]
    (loop [forms body
           saw-cleanup? false]
      (if-let [form (first forms)]
        (cond
          (and (simple-rethrow? form)
               (= binding-sym (second form)))
          saw-cleanup?

          (cleanup-call? form)
          (recur (next forms) true)

          :else
          (recur (next forms) saw-cleanup?))
        false))))

(defn- ^{:stratum 2} classify-throw-site
  "Return the severity classification for a throw-shaped form found in
   a non-boundary namespace. Documented local boundary wrappers are
   `:local-boundary`; programmer-error guards, exact `InterruptedException`
   catch-binding rethrows, and explicit cleanup-preserving same-binding
   rethrows are `:fatal-only` (informational); everything else is
   `:cleanup-needed`."
  [form context]
  (cond
    (local-boundary-wrapper? context)
    :local-boundary

    (and (:response-chain-boundary? context)
         (throw-anomaly-form? form))
    :local-boundary

    (or (and (:interrupted-binding context)
             (simple-rethrow? form)
             (= (second form) (:interrupted-binding context)))
        (and (:cleanup-rethrow-binding context)
             (simple-rethrow? form)
             (= (second form) (:cleanup-rethrow-binding context)))
        (programmer-error-guard? form))
    :fatal-only

    :else
    :cleanup-needed))

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} form-context
  "Enrich traversal context once for a parent form before walking children."
  [context form]
  (let [defn-data   (defn-context form)
        interrupted (interrupted-catch-binding form)
        catch-binding (when (and (seq? form)
                                 (= 'catch (first form)))
                        (nth form 2 nil))]
    (cond-> context
      defn-data (merge defn-data)
      (and (seq? form)
           (symbol? (first form))
           (= "execute-with-handling" (name (first form))))
      (assoc :response-chain-boundary? true)
      interrupted (assoc :interrupted-binding interrupted)
      (and (symbol? catch-binding)
           (cleanup-before-rethrow? form catch-binding))
      (assoc :cleanup-rethrow-binding catch-binding))))

;------------------------------------------------------------------------------ Layer 4

(defn- ^{:stratum 4} visit-form
  "Walk a single form and conj violation records onto `acc!` (a transient
   vector). Recurses into seqable children, including vectors and maps.

   Counting policy: a throw-shaped form is recorded once. We then stop
   recursing into its children — `(throw (ex-info ...))` is one site, not
   two. Standalone `(ex-info ...)` outside of a `throw` still counts on
   its own. `(comment ...)` blocks are skipped entirely.

   `boundary?` is computed once at the file level — when true, the file
   yields no violations regardless of contents."
  ([acc! file-path boundary? form]
   (visit-form acc! file-path boundary? {} form))
  ([acc! file-path boundary? context form]
  (cond
    boundary?
    acc!

    (seq? form)
    (let [head (first form)
          kind (throw-shaped-form? head)]
      (cond
        (and (symbol? head) (contains? skip-walk-heads head))
        acc!

        kind
        (conj! acc! (->violation-record file-path form
                                        (classify-throw-site form context)
                                        kind))

        :else
        (let [child-context (form-context context form)]
          (reduce (fn [a child]
                    (visit-form a file-path boundary? child-context child))
                  acc!
                  form))))

    (or (vector? form) (set? form))
    (reduce (fn [a child] (visit-form a file-path boundary? context child))
            acc!
            form)

    (map? form)
    (reduce (fn [a [k v]]
              (-> a
                  (visit-form file-path boundary? context k)
                  (visit-form file-path boundary? context v)))
            acc!
            form)

    :else
    acc!)))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} analyze-content
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

;------------------------------------------------------------------------------ Layer 6

(defn ^{:stratum 6} analyze-file
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
    (if (or (nil? abs) (not (within-root? root abs)))
      (do (binding [*out* *err*]
            (println (str "[compliance-scanner] WARN path-traversal rejected: "
                          relative-path " escapes repo-root " repo-root)))
          [])
      (if (.isFile abs)
        (let [content (try (slurp abs) (catch Exception _ nil))]
          (if content
            (mapv format-violation (:violations (analyze-content relative-path content)))
            []))
        []))))

;------------------------------------------------------------------------------ Layer 7

(defn ^{:stratum 7} scan-repo
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
   (let [all-files   (list-target-files repo-root)
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
