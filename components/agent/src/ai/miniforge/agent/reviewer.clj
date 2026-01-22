(ns ai.miniforge.agent.reviewer
  "Reviewer agent implementation.
   Runs static analysis gates (syntax, lint, policy) on code artifacts.
   Does not use LLM - purely deterministic gate evaluation."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.loop.interface :as loop]
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

(def ReviewArtifact
  "Schema for the reviewer's output."
  [:map
   [:review/id uuid?]
   [:review/decision [:enum :approved :rejected :conditionally-approved]]
   [:review/gate-results [:vector GateFeedback]]
   [:review/summary [:string {:min 1}]]
   [:review/artifact-id {:optional true} uuid?]
   [:review/gates-passed [:int {:min 0}]]
   [:review/gates-failed [:int {:min 0}]]
   [:review/gates-total [:int {:min 0}]]
   [:review/blocking-issues {:optional true} [:vector :string]]
   [:review/warnings {:optional true} [:vector :string]]
   [:review/recommendations {:optional true} [:vector :string]]
   [:review/created-at {:optional true} inst?]])

;------------------------------------------------------------------------------ Layer 1
;; Gate running and feedback

(defn- gate-result->feedback
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

(defn- create-exception-feedback
  "Create error feedback when gate throws exception."
  [gate idx exception duration]
  {:gate-id (or (:gate/id gate) (keyword (str "gate-" idx)))
   :gate-type :unknown
   :passed? false
   :errors [{:type :gate-exception
             :message (str "Gate execution failed: " (.getMessage exception))}]
   :duration-ms duration})

(defn- run-single-gate
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
                             :error (.getMessage e)}})
          (create-exception-feedback gate idx e duration))))))

(defn- run-gates-on-artifact
  "Run all gates on the artifact and collect results.
   Returns vector of GateFeedback maps."
  [gates artifact context logger]
  (log/info logger :reviewer :reviewer/running-gates
            {:data {:gate-count (count gates)}})
  (->> gates
       (map-indexed (fn [idx gate]
                      (run-single-gate gate idx artifact context logger)))
       vec))

(defn- extract-blocking-issues
  "Extract blocking errors from failed gates."
  [failed-gates]
  (->> failed-gates
       (mapcat :errors)
       (filter #(= :blocking (:severity % :blocking)))
       vec))

(defn- extract-warning-messages
  "Extract warning messages from all gates."
  [gate-feedbacks]
  (->> gate-feedbacks
       (mapcat :warnings)
       (map :message)
       vec))

(defn- extract-error-messages
  "Extract error messages from failed gates."
  [failed-gates]
  (->> failed-gates
       (mapcat :errors)
       (map :message)
       vec))

(defn- decide-on-failures
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

(defn- make-review-decision
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

(defn- generate-summary
  "Generate human-readable summary of review."
  [decision gate-feedbacks]
  (let [passed (filter :passed? gate-feedbacks)
        failed (filter (complement :passed?) gate-feedbacks)
        total (count gate-feedbacks)]
    (case decision
      :approved
      (format "✓ Review approved: All %d gates passed" total)

      :rejected
      (format "✗ Review rejected: %d/%d gates failed with blocking issues"
              (count failed) total)

      :conditionally-approved
      (format "⚠ Conditionally approved: %d/%d gates passed, %d non-blocking issues"
              (count passed) total (count failed)))))

(defn- generate-recommendations
  "Generate recommendations based on gate results."
  [gate-feedbacks]
  (->> gate-feedbacks
       (filter (complement :passed?))
       (mapcat (fn [feedback]
                 (map (fn [error]
                        (str "[" (name (:gate-id feedback)) "] "
                             (:message error)
                             (when-let [fix (:fix-suggestion error)]
                               (str " → " fix))))
                      (:errors feedback))))
       vec))

;------------------------------------------------------------------------------ Layer 2
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

(defn- repair-review-artifact
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

;------------------------------------------------------------------------------ Layer 3
;; Public API - Helper functions

(defn- extract-artifact-and-id
  "Extract artifact and its ID from input."
  [input]
  (let [artifact (or (:artifact input) input)
        artifact-id (or (:artifact/id artifact)
                        (:code/id artifact)
                        (random-uuid))]
    [artifact artifact-id]))

(defn- calculate-gate-counts
  "Calculate passed, failed, and total gate counts."
  [gate-feedbacks]
  (let [passed (count (filter :passed? gate-feedbacks))
        failed (count (filter (complement :passed?) gate-feedbacks))
        total (count gate-feedbacks)]
    {:passed passed :failed failed :total total}))

(defn- build-review-artifact
  "Build the review artifact from gate results and decision."
  [gate-feedbacks decision blocking-issues warnings artifact-id counts]
  {:review/id (random-uuid)
   :review/decision decision
   :review/gate-results gate-feedbacks
   :review/summary (generate-summary decision gate-feedbacks)
   :review/artifact-id artifact-id
   :review/gates-passed (:passed counts)
   :review/gates-failed (:failed counts)
   :review/gates-total (:total counts)
   :review/blocking-issues blocking-issues
   :review/warnings warnings
   :review/recommendations (generate-recommendations gate-feedbacks)
   :review/created-at (java.util.Date.)})

(defn- build-review-result
  "Build the final result map with metrics."
  [review counts duration]
  {:status :success
   :output review
   :artifact review
   :metrics {:decision (:review/decision review)
             :gates-passed (:passed counts)
             :gates-failed (:failed counts)
             :gates-total (:total counts)
             :duration-ms duration
             :tokens 0}})

(defn create-reviewer
  "Create a Reviewer agent with optional configuration overrides.

   The Reviewer agent runs static analysis gates on code artifacts.
   It does NOT use an LLM - all checks are deterministic.

   Options:
   - :gates - Vector of gate implementations (default: syntax, lint, policy)
   - :strict - If true, any gate failure causes rejection (default: false)
   - :logger - Logger instance

   Example:
     (create-reviewer)
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
        config {:strict (or (:strict opts) false)}]
    (core/create-base-agent
     {:role :reviewer
      :system-prompt ""  ; No LLM used - empty prompt
      :config config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [[artifact artifact-id] (extract-artifact-and-id input)
              start-time (System/currentTimeMillis)]
          (log/info logger :reviewer :reviewer/review-start
                    {:data {:artifact-id artifact-id
                            :gate-count (count gates)}})

          (let [gate-feedbacks (run-gates-on-artifact gates artifact context logger)
                {:keys [decision blocking-issues warnings]} (make-review-decision gate-feedbacks config)
                counts (calculate-gate-counts gate-feedbacks)
                review (build-review-artifact gate-feedbacks decision blocking-issues warnings artifact-id counts)
                duration (- (System/currentTimeMillis) start-time)]

            (log/info logger :reviewer :reviewer/review-complete
                      {:data {:decision decision
                              :gates-passed (:passed counts)
                              :gates-failed (:failed counts)
                              :duration-ms duration}})

            (build-review-result review counts duration))))

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
   :warnings-count (count (:review/warnings artifact))})

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

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.loop.interface :as loop])

  ;; Create a reviewer with default gates
  (def reviewer (create-reviewer))

  ;; Create a reviewer with custom gates
  (def strict-reviewer (create-reviewer {:gates [(loop/syntax-gate)
                                                  (loop/lint-gate)
                                                  (loop/policy-gate :security {:policies [:no-secrets :no-todos]})]
                                         :strict true}))

  ;; Invoke with a code artifact
  (def result
    (core/invoke reviewer
                 {}
                 {:artifact {:artifact/id (random-uuid)
                             :artifact/type :code
                             :artifact/content {:code/files [{:path "src/example.clj"
                                                              :content "(ns example)\n(defn hello [] \"world\")"
                                                              :action :create}]}}}))
  ;; => {:status :success, :output {:review/id ..., :review/decision :approved, ...}}

  ;; Check review result
  (approved? (:artifact result))
  ;; => true or false

  (get-recommendations (:artifact result))
  ;; => ["[lint] Unused binding ..." ...]

  ;; Validate a review artifact
  (validate-review-artifact
   {:review/id (random-uuid)
    :review/decision :approved
    :review/gate-results []
    :review/summary "All checks passed"
    :review/gates-passed 3
    :review/gates-failed 0
    :review/gates-total 3})
  ;; => {:valid? true, :errors nil}

  :leave-this-here)
