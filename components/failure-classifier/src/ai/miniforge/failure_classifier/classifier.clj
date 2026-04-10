;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.failure-classifier.classifier
  "Runtime failure classification engine per N1 §5.3.3.

   Classification MUST be performed by the runtime (orchestrator or
   agent-runtime component), not by the agent itself — agents are
   unreliable classifiers of their own failures.

   Layer 0: Pattern-matching rules (pure data)
   Layer 1: Classification logic (pure function)"
  (:require
   [ai.miniforge.failure-classifier.taxonomy :as taxonomy]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Classification rules — ordered, first match wins

(def ^:private timeout-patterns
  "Patterns indicating timeout failures."
  [#"(?i)timeout"
   #"(?i)timed?\s*out"
   #"(?i)deadline\s*exceeded"
   #"(?i)ttl\s*expired"
   #"(?i)wall.?clock\s*limit"])

(def ^:private resource-patterns
  "Patterns indicating resource/budget exhaustion."
  [#"(?i)budget\s*exhaust"
   #"(?i)token\s*limit"
   #"(?i)max.?retries"
   #"(?i)iteration\s*limit"
   #"(?i)cost\s*limit"
   #"(?i)rate\s*limit"
   #"(?i)quota\s*exceed"
   #"(?i)out\s*of\s*memory"
   #"(?i)oom"])

(def ^:private external-patterns
  "Patterns indicating external service failures."
  [#"(?i)connection\s*refuse"
   #"(?i)connection\s*reset"
   #"(?i)dns\s*resol"
   #"(?i)service\s*unavailable"
   #"(?i)503"
   #"(?i)502\s*bad\s*gateway"
   #"(?i)network\s*error"
   #"(?i)unreachable"
   #"(?i)ssl.*error"
   #"(?i)econnrefused"
   #"(?i)socket\s*exception"])

(def ^:private concurrency-patterns
  "Patterns indicating concurrency/conflict failures."
  [#"(?i)merge\s*conflict"
   #"(?i)lock\s*contention"
   #"(?i)deadlock"
   #"(?i)concurrent\s*modif"
   #"(?i)stale\s*ref"
   #"(?i)rebase\s*conflict"
   #"(?i)resource\s*busy"])

(def ^:private data-integrity-patterns
  "Patterns indicating data integrity failures."
  [#"(?i)hash\s*mismatch"
   #"(?i)checksum"
   #"(?i)schema\s*viol"
   #"(?i)malformed"
   #"(?i)corrupt"
   #"(?i)stale\s*context"
   #"(?i)content\s*drift"])

(def ^:private policy-patterns
  "Patterns indicating policy/gate rejection."
  [#"(?i)policy\s*reject"
   #"(?i)policy.*violation"
   #"(?i)gate\s.*fail"
   #"(?i)gate\s*reject"
   #"(?i)\bviolation"
   #"(?i)not\s*permitted"
   #"(?i)forbidden"
   #"(?i)unauthorized"
   #"(?i)capability\s*denied"])

(def ^:private tool-error-patterns
  "Patterns indicating tool execution errors."
  [#"(?i)tool\s*error"
   #"(?i)tool\s*fail"
   #"(?i)command\s*fail"
   #"(?i)exit\s*code\s*[1-9]"
   #"(?i)non.?zero\s*exit"
   #"(?i)process\s*fail"])

(def ^:private task-code-patterns
  "Patterns indicating user code/spec/test failures."
  [#"(?i)test\s*fail"
   #"(?i)compile\s*error"
   #"(?i)syntax\s*error"
   #"(?i)lint\s*error"
   #"(?i)type\s*error"
   #"(?i)assertion\s*fail"
   #"(?i)coverage\s*below"])

(def ^:private agent-error-patterns
  "Patterns indicating agent logic defects."
  [#"(?i)hallucin"
   #"(?i)agent\s*error"
   #"(?i)prompt\s*fail"
   #"(?i)parse.*response"
   #"(?i)invalid\s*json"
   #"(?i)unexpected\s*format"
   #"(?i)agent\s*loop\s*fail"])

(def ^:private classification-rules
  "Ordered list of [pattern-set failure-class] pairs.
   Earlier entries take priority for ambiguous failures."
  [[timeout-patterns        :failure.class/timeout]
   [resource-patterns       :failure.class/resource]
   [external-patterns       :failure.class/external]
   [concurrency-patterns    :failure.class/concurrency]
   [data-integrity-patterns :failure.class/data-integrity]
   [policy-patterns         :failure.class/policy]
   [tool-error-patterns     :failure.class/tool-error]
   [task-code-patterns      :failure.class/task-code]
   [agent-error-patterns    :failure.class/agent-error]])

;------------------------------------------------------------------------------ Layer 0
;; Exception class mapping

(def ^:private exception-class-map
  "Maps exception class names (or prefixes) to failure classes."
  {"java.net.SocketTimeoutException"    :failure.class/timeout
   "java.net.ConnectException"          :failure.class/external
   "java.net.UnknownHostException"      :failure.class/external
   "java.net.SocketException"           :failure.class/external
   "javax.net.ssl"                      :failure.class/external
   "java.util.concurrent.TimeoutException" :failure.class/timeout
   "java.lang.OutOfMemoryError"         :failure.class/resource
   "java.lang.StackOverflowError"       :failure.class/resource
   "clojure.lang.ExceptionInfo"         nil ; needs message-level classification
   })

;------------------------------------------------------------------------------ Layer 1
;; Classification logic (pure)

(defn- matches-patterns?
  "Returns true if text matches any of the regex patterns."
  [text patterns]
  (when (and text (seq patterns))
    (some #(re-find % text) patterns)))

(defn- classify-by-message
  "Classify failure by matching error message against pattern rules."
  [message]
  (when message
    (some (fn [[patterns failure-class]]
            (when (matches-patterns? message patterns)
              failure-class))
          classification-rules)))

(defn- normalize-class-name
  "Normalize class name — (str (type x)) returns 'class java.net.Foo'."
  [s]
  (if (and s (str/starts-with? s "class "))
    (subs s 6)
    s))

(defn- classify-by-exception-class
  "Classify by exception class name (exact or prefix match)."
  [exception-class-name]
  (when-let [cls (normalize-class-name exception-class-name)]
    (or (get exception-class-map cls)
        (some (fn [[prefix failure-class]]
                (when (and failure-class
                           (str/starts-with? cls prefix))
                  failure-class))
              exception-class-map))))

(defn- classify-by-anomaly-category
  "Classify by anomaly category (from response/anomaly maps)."
  [anomaly-category]
  (case anomaly-category
    :anomalies/timeout     :failure.class/timeout
    :anomalies/unavailable :failure.class/external
    :anomalies/interrupted :failure.class/external
    :anomalies/busy        :failure.class/resource
    :anomalies/forbidden   :failure.class/policy
    :anomalies/not-found   :failure.class/data-integrity
    :anomalies/conflict    :failure.class/concurrency
    :anomalies/incorrect   nil ; needs deeper analysis
    :anomalies/fault       nil ; needs deeper analysis
    :anomalies/unsupported nil
    ;; Phase-specific anomalies
    :anomalies.phase/budget-exceeded :failure.class/resource
    :anomalies.phase/agent-failed    :failure.class/agent-error
    :anomalies.phase/no-agent        :failure.class/agent-error
    :anomalies.gate/validation-failed :failure.class/policy
    :anomalies.gate/check-failed     :failure.class/policy
    :anomalies.gate/repair-failed    :failure.class/policy
    :anomalies.agent/llm-error       :failure.class/external
    :anomalies.agent/invoke-failed   :failure.class/agent-error
    :anomalies.agent/parse-failed    :failure.class/agent-error
    :anomalies.workflow/rollback-limit :failure.class/resource
    :anomalies.workflow/max-phases   :failure.class/resource
    nil))

(defn classify-failure
  "Classify a failure into the canonical taxonomy.

   Classification attempts these strategies in order:
   1. Anomaly category (if present) — most precise
   2. Exception class name — JVM-level signal
   3. Error message pattern matching — broadest coverage
   4. Falls back to :failure.class/unknown

   Arguments:
     failure-context - map with optional keys:
       :anomaly/category   - keyword from anomaly taxonomy
       :exception/class    - string, Java exception class name
       :error/message      - string, human-readable error message
       :error/data         - map, additional structured error data
       :phase              - keyword, workflow phase where failure occurred
       :tool/id            - keyword, tool that failed (if applicable)

   Returns: keyword from the canonical failure class set."
  [{:keys [anomaly/category exception/class error/message] :as _failure-context}]
  (or
   ;; Strategy 1: Anomaly category
   (classify-by-anomaly-category category)

   ;; Strategy 2: Exception class
   (classify-by-exception-class class)

   ;; Strategy 3: Message patterns
   (classify-by-message message)

   ;; Fallback: unknown
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

  (classify-failure {:anomaly/category :anomalies.gate/check-failed
                     :error/message "Gate lint-check failed with 3 violations"})
  ;; => :failure.class/policy

  (classify-exception (java.net.SocketTimeoutException. "Read timed out"))
  ;; => :failure.class/timeout

  :leave-this-here)
