;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.failure-classifier.classifier
  "Runtime failure classification engine per N1 §5.3.3.

   Classification MUST be performed by the runtime (orchestrator or
   agent-runtime component), not by the agent itself — agents are
   unreliable classifiers of their own failures.

   All classification rules live in config/failure-classifier/rules.edn.
   This namespace loads, compiles, and applies them.

   Layer 0: Config loading and compilation
   Layer 1: Classification strategies (pure)
   Layer 2: Orchestration (pure)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Config loading and compilation

(def ^:private rules-config
  "Classification rules loaded from EDN config."
  (-> (io/resource "config/failure-classifier/rules.edn") slurp edn/read-string))

(defn- compile-pattern
  "Compile a string pattern into a case-insensitive regex."
  [pattern-str]
  (re-pattern (str "(?i)" pattern-str)))

(defn- compile-rule
  "Compile a message rule: string patterns → compiled regexes."
  [{:keys [patterns class]}]
  {:patterns (mapv compile-pattern patterns)
   :class class})

(def ^:private classification-rules
  "Compiled ordered list of [{:patterns [regex...] :class keyword}].
   Earlier entries take priority for ambiguous failures."
  (mapv compile-rule (:message-rules rules-config)))

(def ^:private exception-class-map
  "Maps exception class names (or prefixes) to failure classes."
  (:exception-map rules-config))

(def ^:private anomaly-category-map
  "Maps anomaly category keywords to failure classes."
  (:anomaly-map rules-config))

;------------------------------------------------------------------------------ Layer 1
;; Classification strategies

(defn- matches-any-pattern?
  "Returns true if text matches any of the compiled regex patterns."
  [text patterns]
  (when (and text (seq patterns))
    (some #(re-find % text) patterns)))

(defn- classify-by-message
  "Classify failure by matching error message against ordered pattern rules."
  [message]
  (when message
    (some (fn [{:keys [patterns class]}]
            (when (matches-any-pattern? message patterns)
              class))
          classification-rules)))

(defn- normalize-class-name
  "Normalize class name — (str (type x)) returns 'class java.net.Foo'."
  [s]
  (if (and s (str/starts-with? s "class "))
    (subs s 6)
    s))

(defn- prefix-match
  "Find a failure class by prefix-matching the exception class name."
  [cls]
  (some (fn [[prefix failure-class]]
          (when (and failure-class (str/starts-with? cls prefix))
            failure-class))
        exception-class-map))

(defn- classify-by-exception-class
  "Classify by exception class name (exact match, then prefix match)."
  [exception-class-name]
  (when-let [cls (normalize-class-name exception-class-name)]
    (or (get exception-class-map cls)
        (prefix-match cls))))

(defn- classify-by-anomaly-category
  "Classify by anomaly category keyword from the anomaly map."
  [anomaly-category]
  (get anomaly-category-map anomaly-category))

;------------------------------------------------------------------------------ Layer 2
;; Orchestration

(defn classify-failure
  "Classify a failure into the canonical taxonomy.

   Strategies applied in order (first non-nil wins):
   1. Anomaly category — most precise
   2. Exception class name — JVM-level signal
   3. Error message pattern matching — broadest coverage
   4. Falls back to :failure.class/unknown

   Arguments:
     failure-context - map with optional keys:
       :anomaly/category   - keyword from anomaly taxonomy
       :exception/class    - string, Java exception class name
       :error/message      - string, human-readable error message

   Returns: keyword from the canonical failure class set."
  [{:keys [anomaly/category exception/class error/message]}]
  (or (classify-by-anomaly-category category)
      (classify-by-exception-class class)
      (classify-by-message message)
      :failure.class/unknown))

(defn classify-exception
  "Convenience: classify a Throwable directly."
  [^Throwable ex]
  (classify-failure
   {:anomaly/category  nil
    :exception/class   (str (type ex))
    :error/message     (.getMessage ex)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (classify-failure {:error/message "Connection refused to api.example.com"})
  ;; => :failure.class/external

  (classify-failure {:error/message "Budget exhausted: 100000 tokens used"})
  ;; => :failure.class/resource

  (classify-failure {:anomaly/category :anomalies/timeout})
  ;; => :failure.class/timeout

  (classify-failure {:error/message "Something went wrong"})
  ;; => :failure.class/unknown

  (classify-exception (java.net.SocketTimeoutException. "Read timed out"))
  ;; => :failure.class/timeout

  :leave-this-here)
