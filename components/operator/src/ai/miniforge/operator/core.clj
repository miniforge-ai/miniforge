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

(ns ai.miniforge.operator.core
  "Core operator implementation.
   Manages the learning loop: observe signals, detect patterns, propose improvements."
  (:require
   [ai.miniforge.operator.protocol :as proto]
   [ai.miniforge.workflow.interface :as wf]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(def default-config
  "Default operator configuration."
  {:signal-retention-ms (* 24 60 60 1000) ; 24 hours
   :pattern-window-ms (* 60 60 1000)       ; 1 hour
   :min-pattern-occurrences 3
   :auto-apply-threshold 0.95
   :shadow-period-ms (* 60 60 1000)})      ; 1 hour shadow mode

;------------------------------------------------------------------------------ Layer 1
;; Signal management

(defn create-signal
  "Create a signal record."
  [type data]
  {:signal/id (random-uuid)
   :signal/type type
   :signal/data data
   :signal/timestamp (System/currentTimeMillis)})

(defn prune-old-signals
  "Remove signals older than retention period."
  [signals retention-ms]
  (let [cutoff (- (System/currentTimeMillis) retention-ms)]
    (filterv #(> (:signal/timestamp %) cutoff) signals)))

;------------------------------------------------------------------------------ Layer 2
;; Pattern detection

(defn detect-repeated-failures
  "Detect repeated failure patterns."
  [signals min-occurrences]
  (let [failures (->> signals
                      (filter #(= :workflow-failed (:signal/type %)))
                      (group-by #(get-in % [:signal/data :phase])))]
    (->> failures
         (filter (fn [[_phase sigs]] (>= (count sigs) min-occurrences)))
         (map (fn [[phase sigs]]
                {:pattern/type :repeated-phase-failure
                 :pattern/phase phase
                 :pattern/occurrences (count sigs)
                 :pattern/confidence (min 1.0 (/ (count sigs) 10.0))
                 :pattern/signals (map :signal/id sigs)})))))

(defn detect-rollback-patterns
  "Detect rollback patterns."
  [signals min-occurrences]
  (let [rollbacks (->> signals
                       (filter #(= :phase-rollback (:signal/type %)))
                       (group-by #(get-in % [:signal/data :from-phase])))]
    (->> rollbacks
         (filter (fn [[_phase sigs]] (>= (count sigs) min-occurrences)))
         (map (fn [[phase sigs]]
                {:pattern/type :frequent-rollback
                 :pattern/from-phase phase
                 :pattern/to-phases (->> sigs (map #(get-in % [:signal/data :to-phase])) distinct vec)
                 :pattern/occurrences (count sigs)
                 :pattern/confidence (min 1.0 (/ (count sigs) 5.0))
                 :pattern/signals (map :signal/id sigs)})))))

(defn detect-repair-patterns
  "Detect repair patterns from inner loop."
  [signals min-occurrences]
  (let [repairs (->> signals
                     (filter #(= :repair-pattern (:signal/type %)))
                     (group-by #(get-in % [:signal/data :error-type])))]
    (->> repairs
         (filter (fn [[_error-type sigs]] (>= (count sigs) min-occurrences)))
         (map (fn [[error-type sigs]]
                {:pattern/type :recurring-repair
                 :pattern/error-type error-type
                 :pattern/occurrences (count sigs)
                 :pattern/confidence (min 1.0 (/ (count sigs) 5.0))
                 :pattern/signals (map :signal/id sigs)})))))

(defrecord SimplePatternDetector [config]
  proto/PatternDetector

  (detect [_this signals]
    (let [min-occ (:min-pattern-occurrences config)]
      (concat
       (detect-repeated-failures signals min-occ)
       (detect-rollback-patterns signals min-occ)
       (detect-repair-patterns signals min-occ))))

  (get-pattern-types [_this]
    #{:repeated-phase-failure :frequent-rollback :recurring-repair}))

(defn create-pattern-detector
  "Create a pattern detector."
  ([] (create-pattern-detector default-config))
  ([config] (->SimplePatternDetector config)))

;------------------------------------------------------------------------------ Layer 3
;; Improvement generation

(defn generate-for-repeated-failure
  "Generate improvement for repeated phase failures."
  [pattern]
  {:improvement/id (random-uuid)
   :improvement/type :gate-adjustment
   :improvement/target (:pattern/phase pattern)
   :improvement/change {:action :add-validation
                        :gate-type :pre-check
                        :description "Add pre-check to catch issues earlier"}
   :improvement/rationale (str "Phase " (name (:pattern/phase pattern))
                               " has failed " (:pattern/occurrences pattern)
                               " times. Adding pre-validation may catch issues earlier.")
   :improvement/confidence (:pattern/confidence pattern)
   :improvement/source-pattern pattern
   :improvement/status :proposed
   :improvement/created-at (System/currentTimeMillis)})

(defn generate-for-frequent-rollback
  "Generate improvement for frequent rollbacks."
  [pattern]
  {:improvement/id (random-uuid)
   :improvement/type :workflow-modification
   :improvement/target (:pattern/from-phase pattern)
   :improvement/change {:action :adjust-phase-order
                        :suggestion "Consider adding intermediate validation"
                        :rollback-targets (:pattern/to-phases pattern)}
   :improvement/rationale (str "Frequent rollbacks from " (name (:pattern/from-phase pattern))
                               ". Consider workflow modification.")
   :improvement/confidence (:pattern/confidence pattern)
   :improvement/source-pattern pattern
   :improvement/status :proposed
   :improvement/created-at (System/currentTimeMillis)})

(defn generate-for-recurring-repair
  "Generate improvement for recurring repair patterns."
  [pattern]
  {:improvement/id (random-uuid)
   :improvement/type :rule-addition
   :improvement/target :knowledge-base
   :improvement/change {:action :add-rule
                        :error-type (:pattern/error-type pattern)
                        :description "Add rule to prevent this error type"}
   :improvement/rationale (str "Error type " (name (:pattern/error-type pattern))
                               " has been repaired " (:pattern/occurrences pattern)
                               " times. Adding a rule may prevent future occurrences.")
   :improvement/confidence (:pattern/confidence pattern)
   :improvement/source-pattern pattern
   :improvement/status :proposed
   :improvement/created-at (System/currentTimeMillis)})

(defrecord SimpleImprovementGenerator []
  proto/ImprovementGenerator

  (generate-improvements [_this patterns _context]
    (mapcat
     (fn [pattern]
       (case (:pattern/type pattern)
         :repeated-phase-failure [(generate-for-repeated-failure pattern)]
         :frequent-rollback [(generate-for-frequent-rollback pattern)]
         :recurring-repair [(generate-for-recurring-repair pattern)]
         []))
     patterns))

  (get-supported-patterns [_this]
    #{:repeated-phase-failure :frequent-rollback :recurring-repair}))

(defn create-improvement-generator
  "Create an improvement generator."
  []
  (->SimpleImprovementGenerator))

;------------------------------------------------------------------------------ Layer 4
;; Governance

(defrecord SimpleGovernance [config]
  proto/Governance

  (requires-approval? [_this improvement]
    (let [imp-type (or (:improvement/type improvement) (:type improvement))
          confidence (or (:improvement/confidence improvement) (:confidence improvement) 0.0)]
      (boolean
       (or (contains? #{:workflow-modification :policy-update} imp-type)
           (< confidence (:auto-apply-threshold config))))))

  (can-auto-apply? [this improvement]
    (not (proto/requires-approval? this improvement)))

  (get-approval-policy [_this improvement-type]
    (case improvement-type
      :prompt-change {:auto-approve? false
                      :required-confidence 0.9
                      :shadow-period-ms (:shadow-period-ms config)}
      :gate-adjustment {:auto-approve? true
                        :required-confidence 0.8
                        :shadow-period-ms 0}
      :policy-update {:auto-approve? false
                      :required-confidence 0.95
                      :shadow-period-ms (* 24 60 60 1000)}
      :rule-addition {:auto-approve? true
                      :required-confidence 0.85
                      :shadow-period-ms 0}
      :budget-adjustment {:auto-approve? true
                          :required-confidence 0.9
                          :shadow-period-ms 0}
      :workflow-modification {:auto-approve? false
                              :required-confidence 0.95
                              :shadow-period-ms (* 24 60 60 1000)}
      ;; Default: require approval
      {:auto-approve? false
       :required-confidence 0.95
       :shadow-period-ms (* 24 60 60 1000)})))

(defn create-governance
  "Create a governance manager."
  ([] (create-governance default-config))
  ([config] (->SimpleGovernance config)))

;------------------------------------------------------------------------------ Layer 5
;; Main Operator implementation

(defrecord SimpleOperator [config signals proposals pattern-detector
                           improvement-generator governance
                           knowledge-store logger]
  proto/Operator

  (observe-signal [_this signal]
    (let [sig (if (:signal/id signal)
                signal
                (create-signal (:type signal) (:data signal)))]
      (swap! signals
             (fn [sigs]
               (-> (conj sigs sig)
                   (prune-old-signals (:signal-retention-ms config)))))

      (log/debug logger :operator :operator/signal-observed
                 {:data {:signal-type (:signal/type sig)
                         :signal-id (:signal/id sig)}})
      sig))

  (get-signals [_this query]
    (let [all-signals @signals
          type-filter (if-let [t (:type query)]
                        #(= t (:signal/type %))
                        identity)
          since-filter (if-let [s (:since query)]
                         #(> (:signal/timestamp %) s)
                         identity)
          filtered (->> all-signals
                        (filter type-filter)
                        (filter since-filter)
                        (sort-by :signal/timestamp >))]
      (if-let [limit (:limit query)]
        (take limit filtered)
        filtered)))

  (analyze-patterns [this window-ms]
    (let [since (- (System/currentTimeMillis) window-ms)
          recent-signals (proto/get-signals this {:since since})
          patterns (proto/detect pattern-detector recent-signals)
          improvements (proto/generate-improvements improvement-generator patterns {})]

      (log/info logger :operator :operator/pattern-analysis
                {:data {:signals-analyzed (count recent-signals)
                        :patterns-found (count patterns)
                        :improvements-proposed (count improvements)}})

      {:patterns patterns
       :recommendations improvements}))

  (propose-improvement [_this improvement]
    ;; Normalize keys to improvement/ namespace
    (let [normalized {:improvement/type (or (:improvement/type improvement) (:type improvement))
                      :improvement/target (or (:improvement/target improvement) (:target improvement))
                      :improvement/change (or (:improvement/change improvement) (:change improvement))
                      :improvement/rationale (or (:improvement/rationale improvement) (:rationale improvement))
                      :improvement/confidence (or (:improvement/confidence improvement) (:confidence improvement) 0.5)}
          imp (merge normalized
                     {:improvement/id (or (:improvement/id improvement) (random-uuid))
                      :improvement/status :proposed
                      :improvement/created-at (System/currentTimeMillis)
                      :improvement/requires-approval? (proto/requires-approval?
                                                       governance normalized)})]
      (swap! proposals assoc (:improvement/id imp) imp)

      (log/info logger :operator :operator/improvement-proposed
                {:data {:improvement-id (:improvement/id imp)
                        :improvement-type (:improvement/type imp)
                        :requires-approval? (:improvement/requires-approval? imp)}})

      {:proposal-id (:improvement/id imp)
       :status :proposed}))

  (get-proposals [_this query]
    (let [all-proposals (vals @proposals)
          status-filter (if-let [s (:status query)]
                          #(= s (:improvement/status %))
                          identity)
          type-filter (if-let [t (:type query)]
                        #(= t (:improvement/type %))
                        identity)]
      (->> all-proposals
           (filter status-filter)
           (filter type-filter)
           (sort-by :improvement/created-at >))))

  (apply-improvement [_this proposal-id]
    (when-let [proposal (get @proposals proposal-id)]
      (let [applied-proposal (assoc proposal
                                    :improvement/status :applied
                                    :improvement/applied-at (System/currentTimeMillis))]
        (swap! proposals assoc proposal-id applied-proposal)

        ;; If it's a rule addition, add to knowledge base
        (when (and (= :rule-addition (:improvement/type proposal))
                   knowledge-store)
          (let [rule (knowledge/create-zettel
                      (str "auto-rule-" (subs (str proposal-id) 0 8))
                      (:improvement/rationale proposal)
                      :rule
                      {:tags #{:auto-generated :operator}
                       :source {:type :operator
                                :proposal-id proposal-id}})]
            (knowledge/put-zettel knowledge-store rule)))

        (log/info logger :operator :operator/improvement-applied
                  {:data {:improvement-id proposal-id
                          :improvement-type (:improvement/type proposal)}})

        {:success? true
         :applied applied-proposal})))

  (reject-improvement [_this proposal-id reason]
    (when-let [proposal (get @proposals proposal-id)]
      (let [rejected-proposal (assoc proposal
                                     :improvement/status :rejected
                                     :improvement/rejected-at (System/currentTimeMillis)
                                     :improvement/rejection-reason reason)]
        (swap! proposals assoc proposal-id rejected-proposal)

        (log/info logger :operator :operator/improvement-rejected
                  {:data {:improvement-id proposal-id
                          :reason reason}})

        rejected-proposal))))

;; Implement WorkflowObserver to receive workflow events
(extend-type SimpleOperator
  wf/WorkflowObserver

  (on-phase-start [this workflow-id phase context]
    (proto/observe-signal this
                          {:type :phase-started
                           :data {:workflow-id workflow-id
                                  :phase phase
                                  :context-keys (keys context)}}))

  (on-phase-complete [this workflow-id phase result]
    (proto/observe-signal this
                          {:type (if (:success? result)
                                   :phase-complete
                                   :phase-failed)
                           :data {:workflow-id workflow-id
                                  :phase phase
                                  :success? (:success? result)
                                  :artifact-count (count (:artifacts result))}}))

  (on-phase-error [this workflow-id phase error]
    (proto/observe-signal this
                          {:type :workflow-failed
                           :data {:workflow-id workflow-id
                                  :phase phase
                                  :error error}}))

  (on-workflow-complete [this workflow-id final-state]
    (proto/observe-signal this
                          {:type :workflow-complete
                           :data {:workflow-id workflow-id
                                  :status (:workflow/status final-state)
                                  :phases-completed (count (:workflow/history final-state))
                                  :artifacts-produced (count (:workflow/artifacts final-state))}}))

  (on-rollback [this workflow-id from-phase to-phase reason]
    (proto/observe-signal this
                          {:type :phase-rollback
                           :data {:workflow-id workflow-id
                                  :from-phase from-phase
                                  :to-phase to-phase
                                  :reason reason}})))

(defn create-llm-pattern-detector*
  "Create an LLM-powered pattern detector via requiring-resolve.
   Returns nil if the namespace cannot be loaded."
  [llm-backend]
  (try
    (let [create-fn (requiring-resolve
                     'ai.miniforge.operator.llm-pattern-detector/create-llm-pattern-detector)]
      (create-fn {:llm-client llm-backend}))
    (catch Exception _e nil)))

(defn create-llm-improvement-generator*
  "Create an LLM-powered improvement generator via requiring-resolve.
   Returns nil if the namespace cannot be loaded."
  [llm-backend]
  (try
    (let [create-fn (requiring-resolve
                     'ai.miniforge.operator.llm-improvement-generator/create-llm-improvement-generator)]
      (create-fn {:llm-client llm-backend}))
    (catch Exception _e nil)))

(defn create-operator
  "Create an operator for the learning loop.

   Options:
   - :knowledge-store - Knowledge store for rule storage
   - :config          - Override default configuration
   - :llm-backend     - LLM client (implements LLMClient protocol).
                        When provided, uses LLMPatternDetector and
                        LLMImprovementGenerator instead of the simple
                        rule-based implementations. Falls back to simple
                        implementations if the LLM namespaces cannot be loaded."
  ([] (create-operator {}))
  ([{:keys [knowledge-store config llm-backend]}]
   (let [merged-config (merge default-config config)
         pattern-detector    (or (when llm-backend
                                   (create-llm-pattern-detector* llm-backend))
                                 (create-pattern-detector merged-config))
         improvement-generator (or (when llm-backend
                                     (create-llm-improvement-generator* llm-backend))
                                   (create-improvement-generator))]
     (->SimpleOperator
      merged-config
      (atom [])
      (atom {})
      pattern-detector
      improvement-generator
      (create-governance merged-config)
      knowledge-store
      (log/create-logger {:min-level :info})))))

;------------------------------------------------------------------------------ Rich Comment
(comment

  ;; Create operator
  (def op (create-operator))

  ;; Observe some signals
  (proto/observe-signal op {:type :workflow-failed
                            :data {:phase :implement
                                   :error "Test error"}})

  ;; Get signals
  (proto/get-signals op {:type :workflow-failed})

  ;; Analyze patterns
  (proto/analyze-patterns op (* 60 60 1000))

  ;; Integrate with workflow
  (def workflow (wf/create-workflow))
  (wf/add-observer workflow op)

  :end)
