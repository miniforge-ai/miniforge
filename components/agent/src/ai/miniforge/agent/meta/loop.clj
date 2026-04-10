;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.agent.meta.loop
  "Meta-agent loop orchestration per N1 §3.3.

   Wires the complete closed loop:
     reliability → diagnosis → improvement → evaluation → deployment → learning

   Layer 0: Cycle context
   Layer 1: Meta-loop cycle (stateful)")

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

;------------------------------------------------------------------------------ Layer 1
;; Meta-loop cycle

(defn run-meta-loop-cycle!
  "Execute one complete meta-loop cycle.

   Steps:
   1. Compute reliability state (SLIs, SLOs, error budgets)
   2. Evaluate degradation mode transitions
   3. Extract improvement signals
   4. Correlate symptoms → generate diagnoses
   5. Generate improvement proposals
   6. Store proposals in pipeline
   7. Return cycle summary

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

        cycle-num (swap! cycle-count inc)

        ;; Step 1: Compute reliability (uses requiring-resolve to avoid circular deps)
        reliability-result
        (when reliability-engine
          (let [compute-cycle! (requiring-resolve 'ai.miniforge.reliability.interface/compute-cycle!)]
            (compute-cycle! reliability-engine metrics)))

        ;; Step 2: Evaluate degradation
        degradation-mode
        (when (and degradation-manager reliability-result)
          (let [evaluate! (requiring-resolve 'ai.miniforge.reliability.interface/evaluate-degradation!)]
            (evaluate! degradation-manager (:budgets reliability-result))))

        ;; Step 3-4: Diagnosis (skip deployment if in safe-mode)
        diagnosis-input (merge diagnosis-data
                               (when reliability-result
                                 {:slo-checks (:slo-checks reliability-result)}))

        diagnosis-result
        (let [run-diagnosis (requiring-resolve 'ai.miniforge.diagnosis.interface/run-diagnosis)]
          (run-diagnosis diagnosis-input diagnosis-config))

        {:keys [signals correlations diagnoses]} diagnosis-result

        ;; Step 5-6: Generate proposals (skip if degraded/safe-mode)
        proposals
        (when (and (not= degradation-mode :safe-mode)
                   (seq diagnoses))
          (let [gen-proposals (requiring-resolve 'ai.miniforge.improvement.interface/generate-proposals)
                store-proposal! (requiring-resolve 'ai.miniforge.improvement.interface/store-proposal!)]
            (let [props (gen-proposals diagnoses)]
              (when improvement-pipeline
                (doseq [p props]
                  (store-proposal! improvement-pipeline p)))
              props)))

        ;; Step 7: Emit summary event
        summary {:cycle-number cycle-num
                 :reliability reliability-result
                 :degradation-mode (or degradation-mode :nominal)
                 :signals (count signals)
                 :correlations (count correlations)
                 :diagnoses (count diagnoses)
                 :proposals (count (or proposals []))}]

    ;; Emit meta-loop cycle event
    (when event-stream
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.core/publish!)
            cycle-event (requiring-resolve 'ai.miniforge.event-stream.core/meta-loop-cycle-completed)]
        (publish! event-stream (cycle-event event-stream summary))))

    summary))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Integration example (requires all components wired up)
  ;; See tests for a working example

  :leave-this-here)
