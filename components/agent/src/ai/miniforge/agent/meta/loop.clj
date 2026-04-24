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

(defn- empty-metrics-store
  []
  {:workflow-metrics []
   :failure-events []
   :phase-metrics []
   :gate-metrics []
   :tool-metrics []
   :context-metrics []})

(defn- context-map
  [opts]
  (merge {:cycle-count (atom 0)
          :metrics-store (atom (empty-metrics-store))}
         opts))

(defn- event-stream-context
  [event-stream config]
  (context-map
   {:event-stream event-stream
    :reliability-engine (reliability/create-engine event-stream (:reliability config))
    :degradation-manager (reliability/create-degradation-manager event-stream (:degradation config))
    :diagnosis-config (:diagnosis config)
    :improvement-pipeline (improvement/create-pipeline)
    :knowledge-store (:knowledge-store config)}))

(defn create-meta-loop-context
  "Create the context required to run meta-loop cycles.

   Arguments - all are instances of the respective component interfaces:
     :reliability-engine    - from reliability/create-engine
     :degradation-manager   - from reliability/create-degradation-manager
     :diagnosis-config      - optional config for signal extraction
     :improvement-pipeline  - from improvement/create-pipeline
     :event-stream          - event stream atom
     :knowledge-store       - optional knowledge store for learning capture"
  ([context-or-event-stream]
   (if (map? context-or-event-stream)
     (context-map context-or-event-stream)
     (event-stream-context context-or-event-stream nil)))
  ([event-stream config]
   (event-stream-context event-stream config)))

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

(defn- execute-cycle
  [ctx metrics diagnosis-data]
  (let [{:keys [reliability-engine degradation-manager
                diagnosis-config improvement-pipeline]} ctx
        reliability-result (compute-reliability reliability-engine metrics)
        degradation-mode (or (evaluate-degradation degradation-manager reliability-result)
                             :nominal)
        diagnosis-result (run-diagnosis diagnosis-data reliability-result diagnosis-config)
        proposals (generate-proposals (:diagnoses diagnosis-result)
                                      degradation-mode
                                      improvement-pipeline)]
    {:degradation-mode degradation-mode
     :diagnosis-result diagnosis-result
     :proposals proposals
     :reliability-result reliability-result}))

(defn- cycle-summary
  [cycle-number cycle-data]
  (let [{:keys [degradation-mode diagnosis-result proposals reliability-result]} cycle-data]
    {:cycle-number cycle-number
     :reliability reliability-result
     :degradation-mode degradation-mode
     :signals (count (:signals diagnosis-result))
     :correlations (count (:correlations diagnosis-result))
     :diagnoses (count (:diagnoses diagnosis-result))
     :proposals (count (or proposals []))}))

(defn- cycle-result
  [started-at duration-ms cycle-data]
  (let [{:keys [degradation-mode diagnosis-result proposals reliability-result]} cycle-data
        breaches (get reliability-result :breaches [])
        recommendation (get reliability-result :recommendation)
        slis (get reliability-result :slis [])
        diagnoses (:diagnoses diagnosis-result)
        signals (:signals diagnosis-result)
        correlations (:correlations diagnosis-result)]
    {:cycle/started-at started-at
     :cycle/duration-ms duration-ms
     :cycle/sli-count (count slis)
     :cycle/breach-count (count breaches)
     :cycle/signal-count (count signals)
     :cycle/correlation-count (count correlations)
     :cycle/diagnosis-count (count diagnoses)
     :cycle/proposal-count (count (or proposals []))
     :cycle/degradation-mode degradation-mode
     :cycle/recommendation recommendation}))

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
  ([ctx]
   (let [started-at (java.util.Date.)
         metrics (get ctx :metrics {})
         diagnosis-data {:failure-events (get metrics :failure-events [])
                         :training-examples (get ctx :training-examples [])
                         :phase-metrics (get metrics :phase-metrics [])}
         completed-cycle (execute-cycle ctx metrics diagnosis-data)
         duration-ms (- (System/currentTimeMillis) (.getTime started-at))
         result (cycle-result started-at duration-ms completed-cycle)]
     (emit-cycle-event (:event-stream ctx) result)
     result))
  ([ctx metrics diagnosis-data]
   (let [cycle-number (swap! (:cycle-count ctx) inc)
         completed-cycle (execute-cycle ctx metrics diagnosis-data)
         summary (cycle-summary cycle-number completed-cycle)]
     (emit-cycle-event (:event-stream ctx) summary)
     summary)))

(defn record-workflow-outcome!
  "Record a workflow completion for use in the next meta-loop cycle."
  [ctx workflow-id status & [failure-class]]
  (let [now (java.util.Date.)]
    (swap! (:metrics-store ctx) update :workflow-metrics conj
           {:workflow/id workflow-id
            :status status
            :timestamp now})
    (when failure-class
      (swap! (:metrics-store ctx) update :failure-events conj
             {:failure/class failure-class
              :workflow/id workflow-id
              :timestamp now})))
  nil)

(defn run-cycle-from-context!
  "Run one meta-loop cycle using the accumulated metrics in `ctx`."
  [ctx & [training-examples]]
  (run-meta-loop-cycle!
   {:event-stream (:event-stream ctx)
    :reliability-engine (:reliability-engine ctx)
    :degradation-manager (:degradation-manager ctx)
    :diagnosis-config (:diagnosis-config ctx)
    :improvement-pipeline (:improvement-pipeline ctx)
    :metrics @(:metrics-store ctx)
    :training-examples training-examples}))

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
