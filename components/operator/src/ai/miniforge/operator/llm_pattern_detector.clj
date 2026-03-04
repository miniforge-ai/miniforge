(ns ai.miniforge.operator.llm-pattern-detector
  "LLM-powered pattern detector for the operator meta-loop.

   Uses an LLM to analyze workflow signals and detect patterns that
   rule-based detectors may miss, such as subtle anti-patterns,
   performance degradation trends, and improvement opportunities.

   Architecture:
   - Fail-open: LLM errors return empty pattern list (never block the operator)
   - Uses requiring-resolve to avoid hard dependency on llm component
   - Budget: ~1000 input tokens, ~500 output tokens per analysis call"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [ai.miniforge.operator.defaults :as defaults]
   [ai.miniforge.operator.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt construction

;; System prompt sourced from defaults — override via config :system-prompt

(defn- summarize-signal
  "Create a compact string summary of a single signal."
  [sig]
  (let [type-str (name (:signal/type sig))
        data     (:signal/data sig)
        ts       (:signal/timestamp sig)]
    (str "[" type-str "] "
         (when (:phase data) (str "phase=" (name (:phase data)) " "))
         (when (:from-phase data) (str "from=" (name (:from-phase data)) " "))
         (when (:to-phase data) (str "to=" (name (:to-phase data)) " "))
         (when (:error-type data) (str "error=" (name (:error-type data)) " "))
         (when (:error data) (str "error=" (:error data) " "))
         (when (:workflow-id data) (str "wf=" (:workflow-id data) " "))
         "ts=" ts)))

(defn- build-detect-prompt
  "Build prompt for pattern detection from a sequence of signals.

   Keeps total input under ~1000 tokens by limiting signal count and summary length."
  [signals]
  (let [;; Take most recent 50 signals to keep prompt size manageable
        recent        (take-last 50 signals)
        signal-count  (count signals)
        shown-count   (count recent)
        signal-lines  (->> recent
                           (map-indexed (fn [i sig]
                                          (str (inc i) ". " (summarize-signal sig))))
                           (str/join "\n"))
        header        (str "Total signals in window: " signal-count
                           (when (> signal-count shown-count)
                             (str " (showing most recent " shown-count ")"))
                           "\n\n")]
    (str header
         "Signals:\n"
         signal-lines
         "\n\nAnalyze these signals and return detected patterns as JSON.")))

;------------------------------------------------------------------------------ Layer 1
;; Response parsing

(defn- parse-pattern-type
  "Parse a pattern type string to keyword, returning nil for unknown types."
  [type-str]
  (let [kw (keyword (str/lower-case (str/trim (str type-str))))]
    (when (contains? #{:repeated-failure :performance-degradation
                       :resource-waste :anti-pattern :improvement-opportunity}
                     kw)
      kw)))

(defn- parse-pattern
  "Parse a single pattern map from LLM JSON output into a canonical pattern map."
  [raw]
  (let [pattern-type (parse-pattern-type (:type raw))]
    (when pattern-type
      {:pattern/type        pattern-type
       :pattern/description (or (:description raw) "No description provided")
       :pattern/affected    (or (:affected raw) "unknown")
       :pattern/occurrences (let [n (:occurrences raw)]
                              (if (number? n) (int n) 1))
       :pattern/confidence  (let [c (:confidence raw)]
                              (if (number? c)
                                (min 1.0 (max 0.0 (double c)))
                                0.5))
       :pattern/rationale   (or (:rationale raw) "No rationale provided")
       :pattern/source      :llm})))

(defn- parse-detect-response
  "Parse LLM JSON response into a sequence of pattern maps.

   Returns empty sequence on parse failure (fail-open)."
  [response-text]
  (try
    (let [cleaned (-> response-text
                      str/trim
                      (str/replace #"^```json?\s*" "")
                      (str/replace #"\s*```$" ""))
          parsed  (json/parse-string cleaned true)]
      (->> (if (sequential? parsed) parsed [])
           (map parse-pattern)
           (remove nil?)))
    (catch Exception _e
      [])))

;------------------------------------------------------------------------------ Layer 2
;; LLMPatternDetector record

(defrecord LLMPatternDetector [llm-client config]
  proto/PatternDetector

  (detect [_this signals]
    (if (empty? signals)
      []
      (try
        (let [complete-fn (requiring-resolve
                           'ai.miniforge.llm.interface.protocols.llm-client/complete*)
              prompt      (build-detect-prompt signals)
              sys-prompt  (get config :system-prompt defaults/pattern-detector-system-prompt)
              request     {:prompt     prompt
                           :system     sys-prompt
                           :max-tokens (get config :max-tokens 500)}
              response    (complete-fn llm-client request)]
          (if (:success response)
            (parse-detect-response (:content response))
            ;; LLM call failed -> fail-open with empty patterns
            []))
        (catch Exception _e
          ;; Any exception -> fail-open with empty patterns
          []))))

  (get-pattern-types [_this]
    #{:repeated-failure
      :performance-degradation
      :resource-waste
      :anti-pattern
      :improvement-opportunity}))

;------------------------------------------------------------------------------ Layer 3
;; Constructor

(defn create-llm-pattern-detector
  "Create an LLM-powered pattern detector.

   Arguments:
     opts - Map with:
       :llm-client - LLM client implementing LLMClient protocol (required)
       :config     - Optional config map:
                       :max-tokens    - Max tokens for LLM response (default 500)
                       :system-prompt - Override the system prompt"
  [{:keys [llm-client config]}]
  (->LLMPatternDetector llm-client (or config {})))

;------------------------------------------------------------------------------ Rich Comment
(comment

  ;; Requires a real llm-client to test end-to-end
  ;; (def client (ai.miniforge.llm.interface/create-client {:backend :anthropic}))
  ;; (def detector (create-llm-pattern-detector {:llm-client client}))

  ;; Test prompt construction
  (build-detect-prompt
   [{:signal/type      :workflow-failed
     :signal/data      {:phase :implement :error "Compilation error"}
     :signal/timestamp 1000}
    {:signal/type      :workflow-failed
     :signal/data      {:phase :implement :error "Compilation error"}
     :signal/timestamp 2000}
    {:signal/type      :phase-rollback
     :signal/data      {:from-phase :review :to-phase :implement}
     :signal/timestamp 3000}])

  ;; Test response parsing
  (parse-detect-response
   "[{\"type\": \"repeated-failure\",
      \"description\": \"Implement phase fails repeatedly\",
      \"affected\": \"implement\",
      \"occurrences\": 2,
      \"confidence\": 0.8,
      \"rationale\": \"Add pre-checks to catch compilation errors earlier\"}]")

  ;; Test fail-open
  (parse-detect-response "not valid json")
  ;; => []

  :end)
