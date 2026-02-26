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
  {:intent/type (or (:intent/type workflow-spec) :update)
   :intent/description (or (:description workflow-spec) (:title workflow-spec) "")
   :intent/business-reason (or (:business-reason workflow-spec)
                               "No business reason provided")
   :intent/constraints (or (:constraints workflow-spec) [])
   :intent/declared-at (java.time.Instant/now)
   :intent/author (or (:author workflow-spec) "system")})

;------------------------------------------------------------------------------ Layer 1
;; Phase Evidence Collection

(defn build-phase-evidence
  "Build phase evidence from phase execution context.
   Returns phase evidence map per N6 spec."
  [phase-name agent-id phase-result]
  {:phase/name phase-name
   :phase/agent agent-id
   :phase/agent-instance-id (or (:agent-instance-id phase-result) (random-uuid))
   :phase/started-at (or (:started-at phase-result) (java.time.Instant/now))
   :phase/completed-at (or (:completed-at phase-result) (java.time.Instant/now))
   :phase/duration-ms (or (:duration-ms phase-result)
                          (- (.toEpochMilli (:completed-at phase-result))
                             (.toEpochMilli (:started-at phase-result))))
   :phase/output (or (:output phase-result) {})
   :phase/artifacts (vec (or (:artifacts phase-result) []))
   :phase/inner-loop-iterations (or (:inner-loop-iterations phase-result) 0)
   :phase/event-stream-range (or (:event-stream-range phase-result)
                                 {:start-seq 0 :end-seq 0})})

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

;------------------------------------------------------------------------------ Layer 2
;; Policy Check Evidence

(defn build-policy-check-evidence
  "Build policy check evidence from gate result.
   Returns policy check evidence per N6 spec."
  [gate-result]
  {:policy-check/pack-id (or (:pack-id gate-result) "unknown")
   :policy-check/pack-version (or (:pack-version gate-result) "1.0.0")
   :policy-check/phase (or (:phase gate-result) :unknown)
   :policy-check/checked-at (or (:checked-at gate-result) (java.time.Instant/now))
   :policy-check/violations (vec (or (:violations gate-result) []))
   :policy-check/passed? (or (:passed? gate-result) true)
   :policy-check/duration-ms (or (:duration-ms gate-result) 0)})

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
    {:pack/id (or (:pack-id promotion-record) "unknown")
     :pack/type (or (:pack-type promotion-record) :knowledge)
     :from-trust (or (:from-trust promotion-record) :untrusted)
     :to-trust (or (:to-trust promotion-record) :trusted)
     :promoted-by (or (:promoted-by promotion-record) "system")
     :promoted-at (or (:promoted-at promotion-record) (java.time.Instant/now))
     :promotion-policy (or (:promotion-policy promotion-record) "knowledge-safety")
     :promotion-justification (or (:promotion-justification promotion-record)
                                  "No justification provided")
     :pack-hash (or (:pack-hash promotion-record) "")
     :pack-signature (or (:pack-signature promotion-record) "")}))

(defn collect-pack-promotions
  "Collect all pack promotion evidence from workflow state.
   Returns vector of pack promotion evidence."
  [workflow-state]
  (let [promotions (get workflow-state :workflow/pack-promotions [])]
    (mapv build-pack-promotion-evidence promotions)))

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
  [workflow-id workflow-state artifact-store]
  (let [workflow-spec (:workflow/spec workflow-state)
        intent (extract-intent workflow-spec)
        phase-evidence (collect-all-phases workflow-state)
        policy-checks (collect-policy-checks workflow-state)
        pack-promotions (collect-pack-promotions workflow-state)
        outcome (build-outcome-evidence workflow-state)
        tool-invocations (collect-tool-invocations workflow-state)

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
                                (validator intent impl-artifacts)))]

    (let [bundle (merge
                  (schema/create-evidence-bundle-template)
                  {:evidence-bundle/id (random-uuid)
                   :evidence-bundle/workflow-id workflow-id
                   :evidence-bundle/created-at (java.time.Instant/now)
                   :evidence/intent intent
                   :evidence/policy-checks policy-checks
                   :evidence/outcome outcome}
                  phase-evidence
                  (when (seq tool-invocations)
                    {:evidence/tool-invocations tool-invocations})
                  (when (seq pack-promotions)
                    {:evidence/pack-promotions pack-promotions})
                  (when semantic-validation
                    {:evidence/semantic-validation semantic-validation}))]
      (assoc bundle :evidence/content-hash (hash/content-hash bundle)))))

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
