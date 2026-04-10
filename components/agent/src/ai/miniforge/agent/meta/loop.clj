;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.agent.meta.loop
  "Meta-agent loop orchestration per N1 §3.3.

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
  "Create the context required to run meta-loop cycles.

   Arguments - all are instances of the respective component interfaces:
     :reliability-engine    - from reliability/create-engine
     :degradation-manager   - from reliability/create-degradation-manager
     :diagnosis-config      - optional config for signal extraction
     :improvement-pipeline  - from improvement/create-pipeline
     :event-stream          - event stream atom
     :knowledge-store       - optional knowledge store for learning capture"
  [opts]
  (merge {:cycle-count (atom 0)} opts))

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
  "Execute one complete meta-loop cycle.

   Arguments:
     ctx     - meta-loop context from create-meta-loop-context
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
