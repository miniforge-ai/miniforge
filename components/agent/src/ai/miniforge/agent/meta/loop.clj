;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.agent.meta.loop
  "Learning-loop orchestration per N1 §3.3.

   Wires the complete closed loop:
     reliability → diagnosis → improvement → evaluation → deployment → learning

   Layer 0: Cycle context and pipeline stages (pure where possible)
   Layer 1: Meta-loop cycle (stateful)"
  (:require
   [ai.miniforge.reliability.interface :as reliability]
   [ai.miniforge.diagnosis.interface :as diagnosis]
   [ai.miniforge.improvement.interface :as improvement]
   [ai.miniforge.event-stream.interface.stream :as stream]
   [ai.miniforge.event-stream.interface.events :as events]))

;------------------------------------------------------------------------------ Layer 0
;; Cycle context

(defn create-meta-loop-context
  "Create the context required to run learning-loop cycles.

   Supported call shapes:
   - (create-meta-loop-context {:event-stream ... :reliability-engine ...})
   - (create-meta-loop-context event-stream)
   - (create-meta-loop-context event-stream {:reliability ... :degradation ...})

   When passed an event stream directly, the helper constructs the default
   reliability engine, degradation manager, improvement pipeline, and metrics
   store expected by the CLI workflow runner."
  ([opts-or-event-stream]
   (if (map? opts-or-event-stream)
     (merge {:cycle-count (atom 0)} opts-or-event-stream)
     (create-meta-loop-context opts-or-event-stream nil)))
  ([event-stream config]
   {:cycle-count (atom 0)
    :event-stream event-stream
    :reliability-engine (reliability/create-engine event-stream (:reliability config))
    :degradation-manager (reliability/create-degradation-manager event-stream (:degradation config))
    :diagnosis-config (:diagnosis-config config)
    :improvement-pipeline (improvement/create-pipeline)
    :knowledge-store (:knowledge-store config)
    :metrics-store (atom {:workflow-metrics []
                          :failure-events []
                          :phase-metrics []
                          :gate-metrics []
                          :tool-metrics []
                          :context-metrics []})}))

;------------------------------------------------------------------------------ Layer 0
;; Pipeline stages

(defn- compute-reliability
  "Step 1: Compute SLIs, check SLOs, update error budgets."
  [reliability-engine metrics]
  (when reliability-engine
    (reliability/compute-cycle! reliability-engine metrics)))

(defn- evaluate-degradation
  "Step 2: Evaluate budget state and transition degradation mode if warranted."
  [degradation-manager reliability-result]
  (when (and degradation-manager reliability-result)
    (reliability/evaluate-degradation! degradation-manager (:budgets reliability-result))))

(defn- run-diagnosis
  "Steps 3-4: Extract signals, correlate symptoms, generate diagnoses."
  [diagnosis-data reliability-result diagnosis-config]
  (let [input (merge diagnosis-data
                     (when reliability-result
                       {:slo-checks (:slo-checks reliability-result)}))]
    (diagnosis/run-diagnosis input diagnosis-config)))

(defn- generate-proposals
  "Steps 5-6: Generate improvement proposals and store in pipeline.
   Skipped when in safe-mode."
  [diagnoses degradation-mode improvement-pipeline]
  (when (and (not= degradation-mode :safe-mode)
             (seq diagnoses))
    (let [props (improvement/generate-proposals diagnoses)]
      (when improvement-pipeline
        (doseq [p props]
          (improvement/store-proposal! improvement-pipeline p)))
      props)))

(defn- emit-cycle-event
  "Step 7: Emit :meta-loop/cycle-completed summary event."
  [event-stream summary]
  (when event-stream
    (stream/publish! event-stream
                     (events/meta-loop-cycle-completed event-stream summary))))

;------------------------------------------------------------------------------ Layer 1
;; Meta-loop cycle

(defn run-meta-loop-cycle!
  "Execute one complete learning-loop cycle.

   Arguments:
     ctx     - learning-loop context from create-meta-loop-context
     metrics - map of collected metrics for SLI computation:
               {:workflow-metrics :phase-metrics :gate-metrics
                :tool-metrics :failure-events :context-metrics}
     diagnosis-data - map for diagnosis engine:
                      {:slo-checks :failure-events :training-examples :phase-metrics}

   Returns:
     {:cycle-number int
      :reliability {:slis :slo-checks :budgets :breaches :recommendation}
      :degradation-mode keyword
      :signals int
      :diagnoses int
      :proposals int}"
  [ctx metrics diagnosis-data]
  (let [{:keys [reliability-engine degradation-manager
                diagnosis-config improvement-pipeline
                event-stream cycle-count]} ctx

        cycle-num          (swap! cycle-count inc)
        reliability-result (compute-reliability reliability-engine metrics)
        deg-mode           (evaluate-degradation degradation-manager reliability-result)
        diag-result        (run-diagnosis diagnosis-data reliability-result diagnosis-config)
        proposals          (generate-proposals (:diagnoses diag-result)
                                              (or deg-mode :nominal)
                                              improvement-pipeline)
        summary            {:cycle-number    cycle-num
                            :reliability     reliability-result
                            :degradation-mode (or deg-mode :nominal)
                            :signals         (count (:signals diag-result))
                            :correlations    (count (:correlations diag-result))
                            :diagnoses       (count (:diagnoses diag-result))
                            :proposals       (count (or proposals []))}]

    (emit-cycle-event event-stream summary)
    summary))

(defn record-workflow-outcome!
  "Record a workflow completion for use in a later learning-loop cycle."
  [ctx workflow-id status & [failure-class]]
  (when-let [metrics-store (:metrics-store ctx)]
    (let [now (java.util.Date.)]
      (swap! metrics-store update :workflow-metrics conj
             {:workflow/id workflow-id
              :status status
              :timestamp now})
      (when failure-class
        (swap! metrics-store update :failure-events conj
               {:failure/class failure-class
                :workflow/id workflow-id
                :timestamp now}))))
  nil)

(defn run-cycle-from-context!
  "Run one learning-loop cycle using metrics accumulated in the context."
  [ctx & [training-examples]]
  (let [metrics (or (some-> ctx :metrics-store deref) {})]
    (run-meta-loop-cycle!
     ctx
     metrics
     {:failure-events (get metrics :failure-events [])
      :phase-metrics (get metrics :phase-metrics [])
      :training-examples training-examples})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create context with all components wired up
  (def stream (stream/create-event-stream {:sinks []}))
  (def engine (reliability/create-engine stream))
  (def deg-mgr (reliability/create-degradation-manager stream))
  (def pipeline (improvement/create-pipeline))

  (def ctx (create-meta-loop-context
            {:reliability-engine engine
             :degradation-manager deg-mgr
             :improvement-pipeline pipeline
             :event-stream stream}))

  ;; Run one cycle with sample metrics
  (run-meta-loop-cycle!
   ctx
   {:workflow-metrics [{:status :completed :timestamp (java.util.Date.)}
                       {:status :failed :timestamp (java.util.Date.)}]}
   {:failure-events [{:failure/class :failure.class/timeout
                      :timestamp (java.util.Date.)}]})

  :leave-this-here)
