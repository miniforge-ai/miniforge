(ns ai.miniforge.operator.llm-improvement-generator
  "LLM-powered improvement generator for the operator meta-loop.

   Takes detected patterns and uses an LLM to generate actionable improvement
   proposals that go beyond template-based suggestions, reasoning about the
   specific context, history, and trade-offs involved.

   Architecture:
   - Fail-open: LLM errors return empty proposals (never block the operator)
   - Uses requiring-resolve to avoid hard dependency on llm component
   - Budget: ~1000 input tokens, ~600 output tokens per generation call"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [ai.miniforge.operator.defaults :as defaults]
   [ai.miniforge.operator.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt construction

;; System prompt and improvement types sourced from defaults — override via config

(defn summarize-pattern
  "Create a compact string summary of a single detected pattern."
  [pattern]
  (let [type-str  (name (:pattern/type pattern))
        affected  (str (:pattern/affected pattern))
        occ       (:pattern/occurrences pattern)
        conf      (:pattern/confidence pattern)
        desc      (or (:pattern/description pattern) "")
        rationale (or (:pattern/rationale pattern) "")]
    (str "Pattern: " type-str "\n"
         "  Affected: " affected "\n"
         "  Occurrences: " occ "\n"
         "  Confidence: " conf "\n"
         "  Description: " desc "\n"
         "  Rationale: " rationale)))

(defn build-generate-prompt
  "Build prompt for improvement generation from detected patterns.

   context may contain :workflow-id, :phase, :budget, etc. for additional grounding."
  [patterns context]
  (let [pattern-count (count patterns)
        pattern-text  (->> patterns
                           (map-indexed (fn [i p]
                                          (str (inc i) ". " (summarize-pattern p))))
                           (str/join "\n\n"))
        context-text  (when (seq context)
                        (str "\nAdditional context:\n"
                             (->> context
                                  (map (fn [[k v]] (str "  " (name k) ": " (pr-str v))))
                                  (str/join "\n"))))]
    (str "Detected patterns (" pattern-count "):\n\n"
         pattern-text
         context-text
         "\n\nGenerate improvement proposals for these patterns as JSON.")))

;------------------------------------------------------------------------------ Layer 1
;; Response parsing

(defn parse-improvement-type
  "Parse an improvement type string to keyword, returning nil for unknown types."
  [type-str allowed-types]
  (let [kw (keyword (str/lower-case (str/trim (str type-str))))]
    (when (contains? allowed-types kw)
      kw)))

(defn parse-improvement
  "Parse a single improvement map from LLM JSON output into a canonical improvement map."
  [raw allowed-types]
  (let [imp-type (parse-improvement-type (:type raw) allowed-types)]
    (when imp-type
      {:improvement/id           (random-uuid)
       :improvement/type         imp-type
       :improvement/target       (get raw :target :unknown)
       :improvement/change       (get raw :change {})
       :improvement/rationale    (get raw :rationale "No rationale provided")
       :improvement/confidence   (let [c (:confidence raw)]
                                   (if (number? c)
                                     (min 1.0 (max 0.0 (double c)))
                                     0.5))
       :improvement/source-pattern-type (when-let [sp (:source-pattern-type raw)]
                                          (keyword sp))
       :improvement/status       :proposed
       :improvement/created-at   (System/currentTimeMillis)
       :improvement/source       :llm})))

(defn parse-generate-response
  "Parse LLM JSON response into a sequence of improvement maps.

   Returns empty sequence on parse failure (fail-open)."
  [response-text allowed-types]
  (try
    (let [cleaned (-> response-text
                      str/trim
                      (str/replace #"^```json?\s*" "")
                      (str/replace #"\s*```$" ""))
          parsed  (json/parse-string cleaned true)]
      (->> (if (sequential? parsed) parsed [])
           (map #(parse-improvement % allowed-types))
           (remove nil?)))
    (catch Exception _e
      [])))

;------------------------------------------------------------------------------ Layer 2
;; LLMImprovementGenerator record

(defrecord LLMImprovementGenerator [llm-client config]
  proto/ImprovementGenerator

  (generate-improvements [_this patterns context]
    (if (empty? patterns)
      []
      (try
        (let [complete-fn (requiring-resolve
                           'ai.miniforge.llm.interface.protocols.llm-client/complete*)
              sys-prompt  (get config :system-prompt defaults/improvement-generator-system-prompt)
              types       (get config :improvement-types defaults/improvement-types)
              prompt      (build-generate-prompt patterns context)
              request     {:prompt     prompt
                           :system     sys-prompt
                           :max-tokens (get config :max-tokens 600)}
              response    (complete-fn llm-client request)]
          (if (:success response)
            (parse-generate-response (:content response) types)
            ;; LLM call failed -> fail-open with empty proposals
            []))
        (catch Exception _e
          ;; Any exception -> fail-open with empty proposals
          []))))

  (get-supported-patterns [_this]
    ;; Handles all pattern types; LLM can reason about any pattern
    #{:repeated-failure
      :performance-degradation
      :resource-waste
      :anti-pattern
      :improvement-opportunity
      ;; Also handles patterns from SimplePatternDetector
      :repeated-phase-failure
      :frequent-rollback
      :recurring-repair}))

;------------------------------------------------------------------------------ Layer 3
;; Constructor

(defn create-llm-improvement-generator
  "Create an LLM-powered improvement generator.

   Arguments:
     opts - Map with:
       :llm-client - LLM client implementing LLMClient protocol (required)
       :config     - Optional config map:
                       :max-tokens       - Max tokens for LLM response (default 600)
                       :system-prompt    - Override the system prompt
                       :improvement-types - Set of allowed improvement type keywords"
  [{:keys [llm-client config]}]
  (->LLMImprovementGenerator llm-client (or config {})))

;------------------------------------------------------------------------------ Rich Comment
(comment

  ;; Requires a real llm-client to test end-to-end
  ;; (def client (ai.miniforge.llm.interface/create-client {:backend :anthropic}))
  ;; (def gen (create-llm-improvement-generator {:llm-client client}))

  ;; Test prompt construction
  (build-generate-prompt
   [{:pattern/type        :repeated-failure
     :pattern/description "Implement phase fails repeatedly due to compile errors"
     :pattern/affected    "implement"
     :pattern/occurrences 5
     :pattern/confidence  0.9
     :pattern/rationale   "Dependencies not validated before compile step"}]
   {:phase :implement})

  ;; Test response parsing
  (parse-generate-response
   "[{\"type\": \"gate-adjustment\",
      \"target\": \"implement-phase\",
      \"change\": {\"action\": \"add-pre-check\", \"gate-type\": \"dependency-check\"},
      \"rationale\": \"Validate dependencies before compile to prevent repeated failures\",
      \"confidence\": 0.85,
      \"source-pattern-type\": \"repeated-failure\"}]"
   defaults/improvement-types)

  ;; Test fail-open
  (parse-generate-response "not valid json" defaults/improvement-types)
  ;; => []

  ;; Test unknown improvement type is filtered
  (parse-improvement {:type "unknown-type" :target "foo" :rationale "bar"}
                     defaults/improvement-types)
  ;; => nil

  :end)
