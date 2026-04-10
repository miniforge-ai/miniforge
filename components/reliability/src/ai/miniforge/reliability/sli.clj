;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.sli
  "Pure SLI computation functions per N1 §5.5.2.

   All functions are pure — they take metrics data and return SLI values.
   No side effects, no event emission.

   Layer 0: Individual SLI computors
   Layer 1: Aggregate computation")

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- safe-ratio
  "Compute numerator/denominator, returning 0.0 when denominator is zero."
  [numerator denominator]
  (if (zero? denominator) 0.0 (double (/ numerator denominator))))

(defn- filter-by-window
  "Filter metrics to those within the time window from now."
  [metrics window]
  (let [window-ms (case window
                    :1h  3600000
                    :7d  (* 7 86400000)
                    :30d (* 30 86400000))
        cutoff (java.util.Date. (- (System/currentTimeMillis) window-ms))]
    (filter (fn [m]
              (when-let [ts (:timestamp m)]
                (.after ^java.util.Date ts cutoff)))
            metrics)))

(defn- filter-by-tier
  "Filter metrics by workflow tier, if specified."
  [metrics tier]
  (if tier
    (filter #(= tier (:tier %)) metrics)
    metrics))

(defn- percentile
  "Compute percentile from sorted values."
  [sorted-vals p]
  (if (empty? sorted-vals)
    0.0
    (let [n (count sorted-vals)
          idx (min (dec n) (int (Math/floor (* p (dec n)))))]
      (double (nth sorted-vals idx)))))

;------------------------------------------------------------------------------ Layer 0
;; SLI-1: Workflow Success Rate

(defn compute-workflow-success-rate
  "Fraction of workflows completing successfully or with explicit escalation.
   Computation: count(completed + escalated) / count(terminal)"
  [workflow-metrics window]
  (let [windowed (filter-by-window workflow-metrics window)
        terminal (filter #(#{:completed :failed :escalated} (:status %)) windowed)
        total (count terminal)
        successful (count (filter #(#{:completed :escalated} (:status %)) terminal))]
    (safe-ratio successful total)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-2: Phase Completion Latency

(defn compute-phase-latency
  "Wall-clock duration per phase type. Returns {:p50 :p95 :p99} in ms."
  [phase-metrics window]
  (let [windowed (filter-by-window phase-metrics window)
        durations (->> windowed
                       (map #(get-in % [:metrics :duration-ms]))
                       (filter some?)
                       sort
                       vec)]
    {:p50 (percentile durations 0.50)
     :p95 (percentile durations 0.95)
     :p99 (percentile durations 0.99)}))

;------------------------------------------------------------------------------ Layer 0
;; SLI-3: Inner Loop Convergence Rate

(defn compute-inner-loop-convergence
  "Fraction of inner loops that converge within retry budget.
   Takes phase metrics where :iterations and :success? are tracked."
  [phase-metrics window]
  (let [windowed (filter-by-window phase-metrics window)
        with-loops (filter :iterations windowed)
        total (count with-loops)
        converged (count (filter :success? with-loops))]
    (safe-ratio converged total)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-4: Gate Pass Rate

(defn compute-gate-pass-rate
  "Fraction of gate evaluations that pass on first attempt.
   Takes gate events with :passed? field."
  [gate-metrics window]
  (let [windowed (filter-by-window gate-metrics window)
        total (count windowed)
        passed (count (filter :passed? windowed))]
    (safe-ratio passed total)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-5: Tool Invocation Success Rate

(defn compute-tool-success-rate
  "Fraction of tool invocations that return success."
  [tool-metrics window]
  (let [windowed (filter-by-window tool-metrics window)
        total (count windowed)
        successful (count (filter :success? windowed))]
    (safe-ratio successful total)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-6: Failure Class Distribution

(defn compute-failure-distribution
  "Percentage of failures per :failure/class.
   Returns map of {failure-class -> fraction}.
   The :failure.class/unknown rate is the key meta-reliability indicator."
  [failure-events window]
  (let [windowed (filter-by-window failure-events window)
        total (count windowed)]
    (if (zero? total)
      {}
      (->> windowed
           (map :failure/class)
           frequencies
           (map (fn [[cls cnt]] [cls (safe-ratio cnt total)]))
           (into {})))))

(defn compute-unknown-failure-rate
  "Convenience: extract the :failure.class/unknown rate from distribution.
   A high unknown rate signals insufficient failure instrumentation."
  [failure-events window]
  (get (compute-failure-distribution failure-events window)
       :failure.class/unknown
       0.0))

;------------------------------------------------------------------------------ Layer 0
;; SLI-7: Context Staleness Rate

(defn compute-context-staleness-rate
  "Fraction of Context Packs that trigger staleness detection."
  [context-metrics window]
  (let [windowed (filter-by-window context-metrics window)
        total (count windowed)
        stale (count (filter :stale? windowed))]
    (safe-ratio stale total)))

;------------------------------------------------------------------------------ Layer 1
;; Aggregate SLI computation

(defn compute-all-slis
  "Compute all SLIs from collected metrics.

   Arguments:
     metrics - map with keys:
       :workflow-metrics  - vector of workflow metric records
       :phase-metrics     - vector of phase metric records
       :gate-metrics      - vector of gate evaluation records
       :tool-metrics      - vector of tool invocation records
       :failure-events    - vector of failure events with :failure/class
       :context-metrics   - vector of context pack records
     window - :1h | :7d | :30d

   Returns: vector of SLI result maps."
  [metrics window]
  (let [{:keys [workflow-metrics phase-metrics gate-metrics
                tool-metrics failure-events context-metrics]} metrics]
    [{:sli/name :SLI-1
      :sli/value (compute-workflow-success-rate (or workflow-metrics []) window)
      :sli/window window}
     {:sli/name :SLI-2
      :sli/value (get (compute-phase-latency (or phase-metrics []) window) :p95 0.0)
      :sli/window window}
     {:sli/name :SLI-3
      :sli/value (compute-inner-loop-convergence (or phase-metrics []) window)
      :sli/window window}
     {:sli/name :SLI-4
      :sli/value (compute-gate-pass-rate (or gate-metrics []) window)
      :sli/window window}
     {:sli/name :SLI-5
      :sli/value (compute-tool-success-rate (or tool-metrics []) window)
      :sli/window window}
     {:sli/name :SLI-6
      :sli/value (compute-unknown-failure-rate (or failure-events []) window)
      :sli/window window}
     {:sli/name :SLI-7
      :sli/value (compute-context-staleness-rate (or context-metrics []) window)
      :sli/window window}]))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def now (java.util.Date.))
  (def sample-workflows
    [{:status :completed :timestamp now}
     {:status :completed :timestamp now}
     {:status :failed :timestamp now}])

  (compute-workflow-success-rate sample-workflows :7d)
  ;; => 0.6666...

  (compute-all-slis {:workflow-metrics sample-workflows} :7d)

  :leave-this-here)
