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

(ns ai.miniforge.agent.reviewer
  "Reviewer agent implementation.
   Performs LLM-backed semantic code review plus deterministic gate validation.
   Falls back to gate-only review when no LLM backend is available."
  (:require
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.loop.interface :as loop]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Reviewer-specific schemas

(def GateFeedback
  "Schema for feedback from a single gate."
  [:map
   [:gate-id keyword?]
   [:gate-type keyword?]
   [:passed? boolean?]
   [:errors {:optional true} [:vector :any]]
   [:warnings {:optional true} [:vector :any]]
   [:duration-ms {:optional true} [:int {:min 0}]]])

(def ReviewIssue
  "Schema for a single review issue from LLM analysis."
  [:map
   [:severity [:enum :blocking :warning :nit]]
   [:file {:optional true} [:string {:min 1}]]
   [:line {:optional true} [:int {:min 1}]]
   [:description [:string {:min 1}]]
   [:suggestion {:optional true} [:string {:min 1}]]])

(def ReviewArtifact
  "Schema for the reviewer's output."
  [:map
   [:review/id uuid?]
   [:review/decision [:enum :approved :rejected :conditionally-approved :changes-requested]]
   [:review/gate-results [:vector GateFeedback]]
   [:review/summary [:string {:min 1}]]
   [:review/artifact-id {:optional true} uuid?]
   [:review/gates-passed [:int {:min 0}]]
   [:review/gates-failed [:int {:min 0}]]
   [:review/gates-total [:int {:min 0}]]
   [:review/blocking-issues {:optional true} [:vector :string]]
   [:review/warnings {:optional true} [:vector :string]]
   [:review/recommendations {:optional true} [:vector :string]]
   [:review/issues {:optional true} [:vector ReviewIssue]]
   [:review/strengths {:optional true} [:vector :string]]
   [:review/created-at {:optional true} inst?]])

;; System prompt - loaded from resources/prompts/reviewer.edn
(def reviewer-system-prompt
  "System prompt for the reviewer agent."
  (delay (prompts/load-prompt :reviewer)))

;------------------------------------------------------------------------------ Layer 1
;; Gate running and feedback

(defn gate-result->feedback
  "Convert a loop gate result to reviewer feedback format."
  [gate result]
  (let [gate-id (:gate/id gate :unknown)
        gate-type (:gate/type gate :unknown)
        passed? (:gate/passed? result true)
        errors (:gate/errors result [])
        warnings (:gate/warnings result [])]
    {:gate-id gate-id
     :gate-type gate-type
     :passed? passed?
     :errors errors
     :warnings warnings
     :duration-ms (or (:gate/duration-ms result) 0)}))

(defn create-exception-feedback
  "Create error feedback when gate throws exception."
  [gate idx exception duration]
  {:gate-id (or (:gate/id gate) (keyword (str "gate-" idx)))
   :gate-type :unknown
   :passed? false
   :errors [{:type :gate-exception
             :message (str "Gate execution failed: " (ex-message exception))}]
   :duration-ms duration})

(defn run-single-gate
  "Run a single gate and return feedback with timing.
   Handles exceptions gracefully."
  [gate idx artifact context logger]
  (let [gate-start (System/currentTimeMillis)
        gate-id (:gate/id gate :unknown)]
    (log/debug logger :reviewer :reviewer/gate-start
               {:data {:gate-idx idx :gate-id gate-id}})
    (try
      (let [result (loop/check-gate gate artifact context)
            duration (- (System/currentTimeMillis) gate-start)
            feedback (gate-result->feedback gate result)]
        (log/info logger :reviewer :reviewer/gate-complete
                  {:data {:gate-id (:gate-id feedback)
                          :passed? (:passed? feedback)
                          :duration-ms duration}})
        (assoc feedback :duration-ms duration))
      (catch Exception e
        (let [duration (- (System/currentTimeMillis) gate-start)]
          (log/error logger :reviewer :reviewer/gate-error
                     {:data {:gate-idx idx
                             :error (ex-message e)}})
          (create-exception-feedback gate idx e duration))))))

(defn run-gates-on-artifact
  "Run all gates on the artifact and collect results.
   Returns vector of GateFeedback maps."
  [gates artifact context logger]
  (log/info logger :reviewer :reviewer/running-gates
            {:data {:gate-count (count gates)}})
  (->> gates
       (map-indexed (fn [idx gate]
                      (run-single-gate gate idx artifact context logger)))
       vec))

(defn extract-blocking-issues
  "Extract blocking errors from failed gates."
  [failed-gates]
  (->> failed-gates
       (mapcat :errors)
       (filter #(= :blocking (:severity % :blocking)))
       vec))

(defn extract-warning-messages
  "Extract warning messages from all gates."
  [gate-feedbacks]
  (->> gate-feedbacks
       (mapcat :warnings)
       (map :message)
       vec))

(defn extract-error-messages
  "Extract error messages from failed gates."
  [failed-gates]
  (->> failed-gates
       (mapcat :errors)
       (map :message)
       vec))

(defn decide-on-failures
  "Determine decision when there are gate failures."
  [failed-gates blocking-issues config]
  (if (seq blocking-issues)
    {:decision :rejected
     :blocking-issues (mapv :message blocking-issues)
     :warnings []}
    {:decision (if (:strict config false) :rejected :conditionally-approved)
     :blocking-issues (if (:strict config)
                        (extract-error-messages failed-gates)
                        [])
     :warnings (extract-error-messages failed-gates)}))

(defn make-review-decision
  "Determine review decision based on gate results."
  [gate-feedbacks config]
  (let [failed (filter (complement :passed?) gate-feedbacks)
        failed-count (count failed)]
    (if (zero? failed-count)
      {:decision :approved
       :blocking-issues []
       :warnings (extract-warning-messages gate-feedbacks)}
      (let [blocking-issues (extract-blocking-issues failed)]
        (decide-on-failures failed blocking-issues config)))))

(defn generate-summary
  "Generate human-readable summary of review."
  [decision gate-feedbacks]
  (let [passed (filter :passed? gate-feedbacks)
        failed (filter (complement :passed?) gate-feedbacks)
        total (count gate-feedbacks)]
    (case decision
      :approved
      (format "Review approved: All %d gates passed" total)

      :rejected
      (format "Review rejected: %d/%d gates failed with blocking issues"
              (count failed) total)

      :conditionally-approved
      (format "Conditionally approved: %d/%d gates passed, %d non-blocking issues"
              (count passed) total (count failed))

      ;; default for :changes-requested or other LLM-sourced decisions
      (format "Review complete: %s (%d gates evaluated)" (name decision) total))))

(defn generate-recommendations
  "Generate recommendations based on gate results."
  [gate-feedbacks]
  (->> gate-feedbacks
       (filter (complement :passed?))
       (mapcat (fn [feedback]
                 (map (fn [error]
                        (str "[" (name (:gate-id feedback)) "] "
                             (:message error)
                             (when-let [fix (:fix-suggestion error)]
                               (str " -> " fix))))
                      (:errors feedback))))
       vec))

;------------------------------------------------------------------------------ Layer 2
;; LLM review: prompt building and response parsing

(defn format-artifact-for-review
  "Format a code artifact into a readable string for the LLM prompt."
  [artifact]
  (cond
    ;; CodeArtifact with :code/files
    (:code/files artifact)
    (str/join "\n\n"
              (map (fn [{:keys [path content action]}]
                     (str "### " path " (" (name (or action :unknown)) ")\n"
                          "```\n" content "\n```"))
                   (:code/files artifact)))

    ;; Plain string
    (string? artifact)
    artifact

    ;; Fallback
    :else
    (pr-str artifact)))

(defn build-review-prompt
  "Construct the user prompt for LLM review from task data."
  [input]
  (let [artifact (or (:task/artifact input) input)
        description (or (:task/description input) "")
        title (or (:task/title input) "")
        intent (or (:task/intent input) "")
        constraints (or (:task/constraints input) "")
        tests (:task/tests input)
        artifact-text (format-artifact-for-review artifact)]
    (str "Review the following code implementation.\n\n"
         (when (seq title)
           (str "## Task: " title "\n\n"))
         (when (seq description)
           (str "## Description\n\n" description "\n\n"))
         (when (and intent (not (str/blank? (str intent))))
           (str "## Intent\n\n" (if (string? intent) intent (pr-str intent)) "\n\n"))
         (when (and constraints (not (str/blank? (str constraints))))
           (str "## Constraints\n\n" (if (string? constraints) constraints (pr-str constraints)) "\n\n"))
         "## Code to Review\n\n"
         artifact-text
         (when tests
           (str "\n\n## Test Results\n\n"
                (if (string? tests) tests (pr-str tests))))
         "\n\nOutput your review as a Clojure map inside a ```clojure code block.")))

(defn parse-review-response
  "Parse the LLM response to extract review feedback.
   Handles EDN in code blocks and plain EDN."
  [response-content]
  (try
    (let [parsed (if-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" response-content)]
                   (edn/read-string (second match))
                   (edn/read-string response-content))]
      (when (map? parsed)
        parsed))
    (catch Exception _
      nil)))

(defn normalize-llm-decision
  "Map LLM decision keywords to ReviewArtifact-compatible decisions."
  [decision]
  (case decision
    :approved :approved
    :rejected :rejected
    :changes-requested :changes-requested
    ;; default
    :changes-requested))

(defn llm-issues->blocking-strings
  "Extract blocking issue descriptions from LLM issues."
  [issues]
  (->> issues
       (filter #(= :blocking (:severity %)))
       (mapv :description)))

(defn llm-issues->warning-strings
  "Extract warning descriptions from LLM issues."
  [issues]
  (->> issues
       (filter #(= :warning (:severity %)))
       (mapv :description)))

(defn llm-issues->recommendations
  "Extract suggestions from LLM issues as recommendations."
  [issues]
  (->> issues
       (filter :suggestion)
       (mapv (fn [{:keys [file description suggestion]}]
               (str (when file (str "[" file "] "))
                    description " -> " suggestion)))))

;------------------------------------------------------------------------------ Layer 3
;; Review validation and repair

(defn validate-review-artifact
  "Validate a review artifact against the schema."
  [artifact]
  (let [schema-valid? (m/validate ReviewArtifact artifact)]
    (if-not schema-valid?
      {:valid? false
       :errors (schema/explain ReviewArtifact artifact)}
      ;; Additional validations
      (let [passed (:review/gates-passed artifact)
            failed (:review/gates-failed artifact)
            total (:review/gates-total artifact)]
        (if (not= total (+ passed failed))
          {:valid? false
           :errors {:gates "Gate counts don't add up"}}
          {:valid? true :errors nil})))))

(defn repair-review-artifact
  "Attempt to repair a review artifact."
  [artifact _errors _context]
  (let [repaired (atom artifact)]
    ;; Fix missing ID
    (when-not (:review/id @repaired)
      (swap! repaired assoc :review/id (random-uuid)))

    ;; Fix missing decision
    (when-not (:review/decision @repaired)
      (swap! repaired assoc :review/decision :rejected))

    ;; Fix missing gate results
    (when-not (:review/gate-results @repaired)
      (swap! repaired assoc :review/gate-results []))

    ;; Recalculate gate counts
    (let [results (:review/gate-results @repaired)
          passed (count (filter :passed? results))
          failed (count (filter (complement :passed?) results))
          total (count results)]
      (swap! repaired assoc
             :review/gates-passed passed
             :review/gates-failed failed
             :review/gates-total total))

    ;; Fix missing summary
    (when-not (:review/summary @repaired)
      (swap! repaired assoc :review/summary
             (generate-summary (:review/decision @repaired)
                               (:review/gate-results @repaired))))

    {:status :success
     :output @repaired}))

;------------------------------------------------------------------------------ Layer 4
;; Public API - Helper functions

(defn extract-artifact-and-id
  "Extract artifact and its ID from input."
  [input]
  (let [artifact (or (:task/artifact input) (:artifact input) input)
        artifact-id (or (:artifact/id artifact)
                        (:code/id artifact)
                        (random-uuid))]
    [artifact artifact-id]))

(defn calculate-gate-counts
  "Calculate passed, failed, and total gate counts."
  [gate-feedbacks]
  (let [passed (count (filter :passed? gate-feedbacks))
        failed (count (filter (complement :passed?) gate-feedbacks))
        total (count gate-feedbacks)]
    {:passed passed :failed failed :total total}))

(defn build-review-artifact
  "Build the review artifact from gate results, LLM feedback, and decision."
  [gate-feedbacks decision blocking-issues warnings artifact-id counts
   & {:keys [issues strengths summary]}]
  (cond-> {:review/id (random-uuid)
           :review/decision decision
           :review/gate-results gate-feedbacks
           :review/summary (or summary (generate-summary decision gate-feedbacks))
           :review/artifact-id artifact-id
           :review/gates-passed (:passed counts)
           :review/gates-failed (:failed counts)
           :review/gates-total (:total counts)
           :review/blocking-issues blocking-issues
           :review/warnings warnings
           :review/recommendations (generate-recommendations gate-feedbacks)
           :review/created-at (java.util.Date.)}
    (seq issues) (assoc :review/issues issues)
    (seq strengths) (assoc :review/strengths strengths)))

(defn build-review-result
  "Build the final result map with metrics."
  [review counts duration tokens & {:keys [cost-usd]}]
  {:status :success
   :output review
   :artifact review
   :metrics (cond-> {:decision (:review/decision review)
                     :gates-passed (:passed counts)
                     :gates-failed (:failed counts)
                     :gates-total (:total counts)
                     :duration-ms duration
                     :tokens tokens}
              cost-usd (assoc :cost-usd cost-usd))})

(defn merge-gate-overrides
  "If gates failed, override the LLM decision accordingly."
  [llm-decision gate-decision config]
  (cond
    ;; Gate rejection always wins
    (= :rejected gate-decision)
    :rejected

    ;; Gate conditional-approval downgrades LLM approval
    (and (= :approved llm-decision) (= :conditionally-approved gate-decision))
    (if (:strict config) :rejected :conditionally-approved)

    ;; Otherwise use LLM decision
    :else
    llm-decision))

;------------------------------------------------------------------------------ Layer 4b
;; Phase lifecycle telemetry

(defn enter-review
  "Emit a phase-started telemetry event when entering the review phase.

   Called at the very beginning of a review invocation to mark phase entry.
   `data` is a map of contextual information about the review about to begin
   (e.g. :artifact-id, :gate-count, :llm?).

   Example:
     (enter-review logger {:artifact-id artifact-id
                           :gate-count (count gates)
                           :llm? (boolean llm-client)})"
  [logger data]
  (log/info logger :reviewer :reviewer/phase-started {:data data}))

(defn leave-review
  "Emit a phase-completed telemetry event when leaving the review phase.

   Called just before returning from a review invocation to mark phase exit.
   `data` must include :review/decision; additional fields (e.g. :duration-ms,
   :gates-passed, :gates-failed) are recommended for observability.

   Example:
     (leave-review logger {:review/decision :approved
                           :duration-ms 120
                           :gates-passed 3
                           :gates-failed 0})"
  [logger data]
  (log/info logger :reviewer :reviewer/phase-completed {:data data}))

;------------------------------------------------------------------------------ Layer 5
;; Agent creation

(defn create-reviewer
  "Create a Reviewer agent with optional configuration overrides.

   The Reviewer performs LLM-backed semantic code review plus deterministic
   gate validation. Falls back to gate-only review when no LLM backend
   is available.

   Options:
   - :gates       - Vector of gate implementations (default: syntax, lint, policy)
   - :strict      - If true, any gate failure causes rejection (default: false)
   - :logger      - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)
   - :config      - Agent configuration (model, temperature, etc.)

   Example:
     (create-reviewer)
     (create-reviewer {:llm-backend llm-client})
     (create-reviewer {:gates [(loop/syntax-gate)
                               (loop/lint-gate)]
                       :strict true})"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))
        default-gates [(loop/syntax-gate)
                       (loop/lint-gate)
                       (loop/policy-gate :security {:policies [:no-secrets]})]
        gates (or (:gates opts) default-gates)
        review-config (->> (merge {:temperature 0.1
                                   :max-tokens 4000}
                                  (:config opts))
                           (model/apply-default-model :reviewer))
        config {:strict (get opts :strict false)}]
    (specialized/create-base-agent
     {:role :reviewer
      :system-prompt @reviewer-system-prompt
      :config review-config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (model/resolve-llm-client-for-role
                          :reviewer
                          (get opts :llm-backend (:llm-backend context)))
              on-chunk (:on-chunk context)
              [artifact artifact-id] (extract-artifact-and-id input)
              start-time (System/currentTimeMillis)]

          ;; Phase lifecycle: mark review entry
          (enter-review logger {:artifact-id artifact-id
                                :gate-count (count gates)
                                :llm? (boolean llm-client)})

          (log/info logger :reviewer :reviewer/review-start
                    {:data {:artifact-id artifact-id
                            :gate-count (count gates)
                            :llm? (boolean llm-client)}})

          (if llm-client
            ;; LLM + gates review
            (let [user-prompt (build-review-prompt input)
                  response (if on-chunk
                             (llm/chat-stream llm-client user-prompt on-chunk
                                              {:system @reviewer-system-prompt
                                               :max-turns 20})
                             (llm/chat llm-client user-prompt
                                       {:system @reviewer-system-prompt
                                        :max-turns 20}))
                  tokens (get response :tokens 0)
                  cost-usd (get response :cost-usd)]

              (log/info logger :reviewer :reviewer/llm-called
                        {:data {:success (llm/success? response)
                                :tokens tokens
                                :streaming? (boolean on-chunk)}})

              (let [;; Parse LLM review
                    llm-review (when (llm/success? response)
                                 (parse-review-response (llm/get-content response)))
                    llm-decision (when llm-review
                                  (normalize-llm-decision (:review/decision llm-review)))
                    llm-issues (or (:review/issues llm-review) [])
                    llm-strengths (or (:review/strengths llm-review) [])
                    llm-summary (:review/summary llm-review)

                    ;; Run deterministic gates
                    gate-feedbacks (run-gates-on-artifact gates artifact context logger)
                    gate-result (make-review-decision gate-feedbacks config)
                    counts (calculate-gate-counts gate-feedbacks)

                    ;; Merge decisions: gates can override LLM
                    final-decision (if llm-decision
                                     (merge-gate-overrides llm-decision (:decision gate-result) config)
                                     (:decision gate-result))

                    ;; Merge issues from both sources
                    all-blocking (into (vec (:blocking-issues gate-result))
                                       (llm-issues->blocking-strings llm-issues))
                    all-warnings (into (vec (:warnings gate-result))
                                       (llm-issues->warning-strings llm-issues))

                    ;; Merge recommendations
                    llm-recs (llm-issues->recommendations llm-issues)

                    ;; Build summary
                    summary (or llm-summary
                                (generate-summary final-decision gate-feedbacks))

                    review (cond-> (build-review-artifact
                                    gate-feedbacks final-decision all-blocking all-warnings
                                    artifact-id counts
                                    :issues llm-issues
                                    :strengths llm-strengths
                                    :summary summary)
                             (seq llm-recs) (update :review/recommendations
                                                    (fn [existing] (into (or existing []) llm-recs))))

                    duration (- (System/currentTimeMillis) start-time)]

                (log/info logger :reviewer :reviewer/review-complete
                          {:data {:decision final-decision
                                  :llm-decision llm-decision
                                  :gates-passed (:passed counts)
                                  :gates-failed (:failed counts)
                                  :llm-issues (count llm-issues)
                                  :duration-ms duration}})

                ;; Phase lifecycle: mark review exit with decision
                (leave-review logger {:review/decision final-decision
                                      :duration-ms duration
                                      :gates-passed (:passed counts)
                                      :gates-failed (:failed counts)
                                      :llm? true})

                (build-review-result review counts duration tokens :cost-usd cost-usd)))

            ;; No LLM — gate-only fallback
            (let [gate-feedbacks (run-gates-on-artifact gates artifact context logger)
                  {:keys [decision blocking-issues warnings]} (make-review-decision gate-feedbacks config)
                  counts (calculate-gate-counts gate-feedbacks)
                  review (build-review-artifact gate-feedbacks decision blocking-issues warnings artifact-id counts)
                  duration (- (System/currentTimeMillis) start-time)]

              (log/info logger :reviewer :reviewer/review-complete
                        {:data {:decision decision
                                :gates-passed (:passed counts)
                                :gates-failed (:failed counts)
                                :duration-ms duration
                                :mode :gate-only}})

              ;; Phase lifecycle: mark review exit with decision
              (leave-review logger {:review/decision decision
                                    :duration-ms duration
                                    :gates-passed (:passed counts)
                                    :gates-failed (:failed counts)
                                    :llm? false})

              (build-review-result review counts duration 0)))))

      :validate-fn validate-review-artifact

      :repair-fn repair-review-artifact})))

(defn review-summary
  "Get a summary of a review artifact for logging/display."
  [artifact]
  {:id (:review/id artifact)
   :decision (:review/decision artifact)
   :gates-passed (:review/gates-passed artifact)
   :gates-failed (:review/gates-failed artifact)
   :gates-total (:review/gates-total artifact)
   :blocking-issues-count (count (:review/blocking-issues artifact))
   :warnings-count (count (:review/warnings artifact))
   :llm-issues-count (count (:review/issues artifact))})

(defn approved?
  "Check if a review artifact represents approval."
  [artifact]
  (= :approved (:review/decision artifact)))

(defn rejected?
  "Check if a review artifact represents rejection."
  [artifact]
  (= :rejected (:review/decision artifact)))

(defn conditionally-approved?
  "Check if a review artifact is conditionally approved."
  [artifact]
  (= :conditionally-approved (:review/decision artifact)))

(defn changes-requested?
  "Check if a review artifact has changes requested."
  [artifact]
  (= :changes-requested (:review/decision artifact)))

(defn get-blocking-issues
  "Extract blocking issues from review artifact."
  [artifact]
  (:review/blocking-issues artifact []))

(defn get-warnings
  "Extract warnings from review artifact."
  [artifact]
  (:review/warnings artifact []))

(defn get-recommendations
  "Extract recommendations from review artifact."
  [artifact]
  (:review/recommendations artifact []))

(defn get-issues
  "Extract LLM review issues from review artifact."
  [artifact]
  (:review/issues artifact []))

(defn get-strengths
  "Extract strengths noted by the LLM from review artifact."
  [artifact]
  (:review/strengths artifact []))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a reviewer with default gates (gate-only mode)
  (def reviewer (create-reviewer))

  ;; Create a reviewer with LLM backend
  #_(def llm-reviewer (create-reviewer {:llm-backend llm-client}))

  ;; Create a reviewer with custom gates
  (def strict-reviewer (create-reviewer {:gates [(loop/syntax-gate)
                                                  (loop/lint-gate)
                                                  (loop/policy-gate :security {:policies [:no-secrets :no-todos]})]
                                         :strict true}))

  ;; Invoke via protocol (works because FunctionalAgent implements Agent)
  (require '[ai.miniforge.agent.interface :as agent])
  (agent/invoke reviewer
                {:task/description "Review this code"
                 :task/artifact {:code/id (random-uuid)
                                 :code/files [{:path "src/example.clj"
                                               :content "(ns example)\n(defn hello [] \"world\")"
                                               :action :create}]}}
                {})

  ;; Check review result (bind result from invoke call above)
  #_(approved? (:artifact result))
  #_(get-issues (:artifact result))
  #_(get-strengths (:artifact result))
  #_(get-recommendations (:artifact result))

  ;; Validate a review artifact
  (validate-review-artifact
   {:review/id (random-uuid)
    :review/decision :approved
    :review/gate-results []
    :review/summary "All checks passed"
    :review/gates-passed 3
    :review/gates-failed 0
    :review/gates-total 3})

  :leave-this-here)
