(ns ai.miniforge.evidence-bundle.collector
  "Utilities for collecting evidence during workflow execution.
   Provides helpers for gathering phase results, artifacts, and metadata."
  (:require
   [ai.miniforge.evidence-bundle.hash :as hash]
   [ai.miniforge.evidence-bundle.schema :as schema]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Intent Collection

(defn extract-intent
  "Extract intent from workflow specification.
   Returns intent evidence map."
  [workflow-spec]
  {:intent/type (get workflow-spec :intent/type :update)
   :intent/description (or (:description workflow-spec)
                            (get workflow-spec :title ""))
   :intent/business-reason (get workflow-spec :business-reason
                                "No business reason provided")
   :intent/constraints (get workflow-spec :constraints [])
   :intent/declared-at (java.time.Instant/now)
   :intent/author (get workflow-spec :author "system")})

;------------------------------------------------------------------------------ Layer 1
;; Phase Evidence Collection

(defn- build-phase-output
  "Extract the output map for phase evidence.

   Handles both:
   - Legacy shape: {:output {...}} — extracts :output directly
   - New environment model: {:environment-id ... :summary ... :metrics ...}
     — synthesizes an output map from the provenance metadata

   In the new model, :evidence/implement captures summary + metrics (not code).
   :evidence/verify captures test output. :evidence/release captures PR metadata."
  [phase-result]
  (or
   ;; Legacy: explicit :output key
   (get phase-result :output)
   ;; New environment model: synthesize output from provenance metadata
   (when (or (:summary phase-result) (:metrics phase-result)
             (:environment-id phase-result))
     (cond-> {}
       (:summary phase-result)        (assoc :summary        (:summary phase-result))
       (:metrics phase-result)        (assoc :metrics        (:metrics phase-result))
       (:environment-id phase-result) (assoc :environment-id (:environment-id phase-result))))
   {}))\n\n(defn build-phase-evidence
  "Build phase evidence from phase execution context.
   Returns phase evidence map per N6 spec.

   Handles both legacy (:output map) and new-model (:environment-id, :summary,
   :metrics) phase result shapes. In the new model, code is NOT captured here;
   it is derived from the PR diff at release time."
  [phase-name agent-id phase-result]
  (let [started-at   (get phase-result :started-at (java.time.Instant/now))
        completed-at (get phase-result :completed-at (java.time.Instant/now))]
    {:phase/name                phase-name
     :phase/agent               agent-id
     :phase/agent-instance-id   (get phase-result :agent-instance-id (random-uuid))
     :phase/started-at          started-at
     :phase/completed-at        completed-at
     :phase/duration-ms         (get phase-result :duration-ms
                                     (- (.toEpochMilli completed-at)
                                        (.toEpochMilli started-at)))
     :phase/output              (build-phase-output phase-result)
     :phase/artifacts           (vec (get phase-result :artifacts []))
     :phase/inner-loop-iterations (get phase-result :inner-loop-iterations 0)
     :phase/event-stream-range  (get phase-result :event-stream-range
                                     {:start-seq 0 :end-seq 0})}))

(defn collect-phase-evidence
  "Collect evidence for a single phase from workflow state.
   Returns phase evidence map or nil if phase not executed."
  [workflow-state phase-name]
  (when-let [phase-data (get-in workflow-state [:workflow/phases phase-name])]
    (build-phase-evidence
     phase-name
     (or (:agent phase-data) :unknown)
     phase-data)))

(defn collect-all-phases
  "Collect evidence for all executed phases.
   Returns map of evidence keys to phase evidence."
  [workflow-state]
  (let [phases [:plan :design :implement :verify :review :release :observe]]
    (reduce
     (fn [acc phase-name]
       (if-let [evidence (collect-phase-evidence workflow-state phase-name)]
         (assoc acc (keyword "evidence" (name phase-name)) evidence)
         acc))
     {}
     phases)))

(defn collect-tool-invocations
  "Collect tool invocation records from workflow state."
  [workflow-state]
  (vec (or (:workflow/tool-invocations workflow-state) [])))

;------------------------------------------------------------------------------ Layer 1.5
;; Rules Applied Evidence

(defn- build-rule-applied-entry
  "Normalize a manifest entry into the expected rule-applied shape.
   Ensures all fields have valid defaults and annotates with phase name."
  [entry phase-name]
  {:id (or (:id entry) (random-uuid))
   :title (get entry :title "unknown")
   :role (get entry :role :unknown)
   :tags-matched (vec (get entry :tags-matched []))
   :score (double (get entry :score 0.0))
   :phase (get entry :phase phase-name)})

(defn collect-rules-applied
  "Collect rules-applied evidence from phase results.
   Each phase that captured a rules-manifest has its entries normalized
   and annotated with the phase name.
   Returns empty vector when no phases have a rules manifest."
  [workflow-state]
  (let [phases (get workflow-state :workflow/phases {})]
    (vec (mapcat (fn [[phase-name phase-data]]
                   (when-let [manifest (:rules-manifest phase-data)]
                     (mapv #(build-rule-applied-entry % phase-name) manifest)))
                 phases))))

;------------------------------------------------------------------------------ Layer 2
;; Policy Check Evidence

(defn build-policy-check-evidence
  "Build policy check evidence from gate result.
   Returns policy check evidence per N6 spec."
  [gate-result]
  {:policy-check/pack-id (get gate-result :pack-id "unknown")
   :policy-check/pack-version (get gate-result :pack-version "1.0.0")
   :policy-check/phase (get gate-result :phase :unknown)
   :policy-check/checked-at (get gate-result :checked-at (java.time.Instant/now))
   :policy-check/violations (vec (get gate-result :violations []))
   :policy-check/passed? (get gate-result :passed? true)
   :policy-check/duration-ms (get gate-result :duration-ms 0)})

(defn collect-policy-checks
  "Collect all policy check evidence from workflow state.
   Returns vector of policy check evidence."
  [workflow-state]
  (let [gate-results (get workflow-state :workflow/gate-results [])]
    (mapv build-policy-check-evidence gate-results)))

;------------------------------------------------------------------------------ Layer 3
;; Pack Promotion Evidence

(defn build-pack-promotion-evidence
  "Build pack promotion evidence from promotion record.
   Returns pack promotion evidence per N6 section 2.1.

   If the promotion record already has the correct format (with :pack/id),
   return it as-is. Otherwise, build from legacy format."
  [promotion-record]
  ;; If already in correct format, return as-is
  (if (contains? promotion-record :pack/id)
    promotion-record
    ;; Otherwise, build from legacy format
    {:pack/id (get promotion-record :pack-id "unknown")
     :pack/type (get promotion-record :pack-type :knowledge)
     :from-trust (get promotion-record :from-trust :untrusted)
     :to-trust (get promotion-record :to-trust :trusted)
     :promoted-by (get promotion-record :promoted-by "system")
     :promoted-at (get promotion-record :promoted-at (java.time.Instant/now))
     :promotion-policy (get promotion-record :promotion-policy "knowledge-safety")
     :promotion-justification (get promotion-record :promotion-justification
                                   "No justification provided")
     :pack-hash (get promotion-record :pack-hash "")
     :pack-signature (get promotion-record :pack-signature "")}))

(defn collect-pack-promotions
  "Collect all pack promotion evidence from workflow state.
   Returns vector of pack promotion evidence."
  [workflow-state]
  (let [promotions (get workflow-state :workflow/pack-promotions [])]
    (mapv build-pack-promotion-evidence promotions)))

;------------------------------------------------------------------------------ Layer 3.5
;; Supervision Decision Evidence (N6)

(defn collect-supervision-decisions
  "Collect supervision decision events from the event stream.

   Filters for :supervision/tool-use-evaluated events and transforms
   them into evidence records.

   Arguments:
   - event-stream: Event stream atom
   - workflow-id: UUID of the workflow

   Returns vector of supervision decision evidence maps."
  [event-stream workflow-id]
  (try
    (when event-stream
      (let [get-events-fn (requiring-resolve 'ai.miniforge.event-stream.core/get-events)
            events (get-events-fn event-stream
                                  {:workflow-id workflow-id
                                   :event-type :supervision/tool-use-evaluated})]
        (mapv (fn [event]
                (cond-> {:supervision/tool-name (get event :tool/name "unknown")
                         :supervision/decision (get event :supervision/decision "allow")
                         :supervision/timestamp (get event :event/timestamp
                                                     (java.util.Date.))}
                  (:supervision/reasoning event)
                  (assoc :supervision/reasoning (:supervision/reasoning event))

                  (:supervision/meta-eval? event)
                  (assoc :supervision/meta-eval? true)

                  (:supervision/confidence event)
                  (assoc :supervision/confidence (:supervision/confidence event))

                  (:workflow/phase event)
                  (assoc :supervision/phase (:workflow/phase event))))
              events)))
    (catch Exception _e
      ;; event-stream dependency might not be loaded
      [])))

(defn collect-control-actions
  "Collect control action events from the event stream.

   Pairs :control-action/requested with :control-action/executed events.

   Arguments:
   - event-stream: Event stream atom
   - workflow-id: UUID of the workflow

   Returns vector of control action evidence maps."
  [event-stream workflow-id]
  (try
    (when event-stream
      (let [get-events-fn (requiring-resolve 'ai.miniforge.event-stream.core/get-events)
            requested (get-events-fn event-stream
                                     {:workflow-id workflow-id
                                      :event-type :control-action/requested})
            executed (get-events-fn event-stream
                                    {:workflow-id workflow-id
                                     :event-type :control-action/executed})
            executed-by-id (into {} (map (fn [e] [(:action/id e) e]) executed))]
        (mapv (fn [req-event]
                (let [action-id (:action/id req-event)
                      exec-event (get executed-by-id action-id)]
                  (cond-> {:control-action/id action-id
                           :control-action/type (:action/type req-event)
                           :control-action/requester (get req-event :action/requester {})
                           :control-action/timestamp (get req-event :event/timestamp
                                                         (java.util.Date.))
                           :control-action/result (if exec-event :executed :pending)}
                    (:action/justification req-event)
                    (assoc :control-action/justification (:action/justification req-event))

                    (:action/target req-event)
                    (assoc :control-action/target (:action/target req-event)))))
              requested)))
    (catch Exception _e
      [])))

;------------------------------------------------------------------------------ Layer 4
;; Outcome Evidence

(defn build-outcome-evidence
  "Build outcome evidence from workflow final state.
   Uses anomaly->outcome-evidence when anomaly maps are available.
   Returns outcome evidence per N6 spec."
  [workflow-state]
  (let [status (:workflow/status workflow-state)
        pr-info (:workflow/pr-info workflow-state)
        error-info (:workflow/error workflow-state)
        ;; Check for anomaly map in error-info or workflow state
        anomaly-map (cond
                      (response/anomaly-map? error-info) error-info
                      (response/anomaly-map? (:anomaly error-info)) (:anomaly error-info)
                      :else nil)]
    (merge
     {:outcome/success (= status :completed)}
     (when pr-info
       {:outcome/pr-number (:number pr-info)
        :outcome/pr-url (:url pr-info)
        :outcome/pr-status (:status pr-info)
        :outcome/pr-merged-at (:merged-at pr-info)})
     (if anomaly-map
       ;; Use boundary translator for anomaly maps
       (response/anomaly->outcome-evidence anomaly-map)
       ;; Fall back to legacy error shape
       (when error-info
         {:outcome/error-message (:message error-info)
          :outcome/error-phase (:phase error-info)
          :outcome/error-details error-info})))))

;------------------------------------------------------------------------------ Layer 5
;; Complete Bundle Assembly

(defn assemble-evidence-bundle
  "Assemble complete evidence bundle from workflow state and context.
   Returns evidence bundle ready for storage."
  [workflow-id workflow-state artifact-store & [opts]]
  (let [workflow-spec (:workflow/spec workflow-state)
        event-stream (:event-stream opts)
        intent (extract-intent workflow-spec)
        phase-evidence (collect-all-phases workflow-state)
        policy-checks (collect-policy-checks workflow-state)
        pack-promotions (collect-pack-promotions workflow-state)
        outcome (build-outcome-evidence workflow-state)
        tool-invocations (collect-tool-invocations workflow-state)
        rules-applied (collect-rules-applied workflow-state)
        supervision-decisions (collect-supervision-decisions event-stream workflow-id)
        control-actions (collect-control-actions event-stream workflow-id)

        ;; Get artifacts for semantic validation
        artifacts (when artifact-store
                    (let [query-fn (requiring-resolve 'ai.miniforge.artifact.interface/query)]
                      (filter
                       #(= workflow-id (get-in % [:artifact/provenance :provenance/workflow-id]))
                       (query-fn artifact-store {}))))

        ;; Perform semantic validation if implementer artifacts exist
        impl-artifacts (filter #(= :implement (get-in % [:artifact/provenance :provenance/phase]))
                               artifacts)
        semantic-validation (when (seq impl-artifacts)
                              (let [validator (requiring-resolve
                                               'ai.miniforge.evidence-bundle.protocols.impl.semantic-validator/validate-intent-impl)]
                                (validator intent impl-artifacts)))

        base-bundle (merge
                     (schema/create-evidence-bundle-template)
                     {:evidence-bundle/id (random-uuid)
                      :evidence-bundle/workflow-id workflow-id
                      :evidence-bundle/created-at (java.time.Instant/now)
                      :evidence/intent intent
                      :evidence/policy-checks policy-checks
                      :evidence/outcome outcome}
                     phase-evidence)
        bundle (cond-> base-bundle
                 (seq tool-invocations)
                 (assoc :evidence/tool-invocations tool-invocations)
                 (seq pack-promotions)
                 (assoc :evidence/pack-promotions pack-promotions)
                 (seq supervision-decisions)
                 (assoc :evidence/supervision-decisions supervision-decisions)
                 (seq control-actions)
                 (assoc :evidence/control-actions control-actions)
                 (seq rules-applied)
                 (assoc :evidence/rules-applied rules-applied)
                 semantic-validation
                 (assoc :evidence/semantic-validation semantic-validation))

        ;; Run sensitive data scanner before hashing
        scan-result (try
                      (let [scan-fn (requiring-resolve
                                      'ai.miniforge.evidence-bundle.scanner/scan-artifact)
                            compliance-fn (requiring-resolve
                                            'ai.miniforge.evidence-bundle.scanner/compliance-metadata)]
                        (compliance-fn (scan-fn bundle)))
                      (catch Exception _e nil))

        ;; Merge compliance metadata
        bundle (cond-> bundle
                 scan-result (merge scan-result))]
    (assoc bundle :evidence/content-hash (hash/content-hash bundle))))

;------------------------------------------------------------------------------ Layer 6
;; Workflow Integration Helpers

(defn should-create-bundle?
  "Check if evidence bundle should be created for workflow.
   Always create bundle at completion, even on failure (per N6 spec)."
  [workflow-state]
  (contains? #{:completed :failed} (:workflow/status workflow-state)))

(defn auto-collect-evidence
  "Automatically collect evidence when workflow reaches terminal state.
   Returns evidence bundle or nil if not ready."
  [workflow-id workflow-state artifact-store]
  (when (should-create-bundle? workflow-state)
    (assemble-evidence-bundle workflow-id workflow-state artifact-store)))
