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
(ns ai.miniforge.compliance-scanner.exceptions-as-data-classify
  "Pure classification: boundary-namespace detection, throw-shape
   recognition, programmer-error-guard and local-boundary-wrapper
   detection, and the combined per-site classification decision. Split
   out of `exceptions-as-data` (rule 210: an eighth real layer there is
   the signal to split it).

   Layer 0: Boundary/throw-shape/rethrow pattern constants + reader-free
            primitives (collect-text, defn-form?, bang-symbol?, ...)
   Layer 1: Single-concern predicates built on Layer 0 (boundary-prefix?,
            throw-call?, throw-class?, programmer-error-guard?,
            defn-context, local-boundary-wrapper?, cleanup-call?,
            interrupted-catch-binding)
   Layer 2: Combined classification (boundary-namespace?, throw-shaped-form?,
            cleanup-before-rethrow?, classify-throw-site)"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

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

;; Rethrow / interrupted-exception classification
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

(def ^{:stratum 0} skip-walk-heads
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

(defn ^{:stratum 1} defn-context
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

(defn- ^{:stratum 1} cleanup-call?
  [form]
  (boolean
   (some cleanup-rethrow-markers
         (map name (filter symbol? (tree-seq coll? seq form))))))

(defn ^{:stratum 1} interrupted-catch-binding
  [form]
  (when (interrupted-exception-catch? form)
    (let [binding-sym (nth form 2 nil)]
      (when (symbol? binding-sym)
        binding-sym))))

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

(defn ^{:stratum 2} throw-shaped-form?
  "Classify a list-form's head as a throw-shaped operator. Returns
   the operator kind keyword or nil."
  [head]
  (cond
    (throw-call? head)   :throw
    (throw-class? head)  :ctor
    (ex-info-call? head) :ex-info
    :else                nil))

(defn ^{:stratum 2} cleanup-before-rethrow?
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

(defn ^{:stratum 2} classify-throw-site
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
