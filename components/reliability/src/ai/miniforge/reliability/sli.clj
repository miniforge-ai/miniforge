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

(defn- percentile
  "Compute percentile from sorted values."
  [sorted-vals p]
  (if (empty? sorted-vals)
    0.0
    (let [n (count sorted-vals)
          idx (min (dec n) (int (Math/floor (* p (dec n)))))]
      (double (nth sorted-vals idx)))))

(defn- terminal-workflow?
  "Returns true if the workflow has reached a terminal status."
  [workflow]
  (#{:completed :failed :escalated} (:status workflow)))

(defn- successful-workflow?
  "Returns true if the workflow completed successfully or escalated."
  [workflow]
  (#{:completed :escalated} (:status workflow)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-1: Workflow Success Rate

(defn compute-workflow-success-rate
  [workflow-metrics window]
  (let [windowed (filter-by-window workflow-metrics window)
        terminal (filter terminal-workflow? windowed)
        total (count terminal)
        successful (count (filter successful-workflow? terminal))]
    (safe-ratio successful total)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-2: Phase Completion Latency

(defn- has-duration?
  "Returns the duration-ms metric if present, nil otherwise."
  [metric]
  (get-in metric [:metrics :duration-ms]))

(defn compute-phase-latency
  [phase-metrics window]
  (let [windowed (filter-by-window phase-metrics window)
        durations (->> windowed
                       (map has-duration?)
                       (filter some?)
                       sort
                       vec)]
    {:p50 (percentile durations 0.50)
     :p95 (percentile durations 0.95)
     :p99 (percentile durations 0.99)}))

;------------------------------------------------------------------------------ Layer 0
;; SLI-3: Inner Loop Convergence Rate

(defn compute-inner-loop-convergence
  [phase-metrics window]
  (let [windowed (filter-by-window phase-metrics window)
        with-loops (filter :iterations windowed)
        total (count with-loops)
        converged (count (filter :success? with-loops))]
    (safe-ratio converged total)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-4: Gate Pass Rate

(defn compute-gate-pass-rate
  [gate-metrics window]
  (let [windowed (filter-by-window gate-metrics window)
        total (count windowed)
        passed (count (filter :passed? windowed))]
    (safe-ratio passed total)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-5: Tool Invocation Success Rate

(defn compute-tool-success-rate
  [tool-metrics window]
  (let [windowed (filter-by-window tool-metrics window)
        total (count windowed)
        successful (count (filter :success? windowed))]
    (safe-ratio successful total)))

;------------------------------------------------------------------------------ Layer 0
;; SLI-6: Failure Class Distribution

(defn compute-failure-distribution
  [failure-events window]
  (let [windowed (filter-by-window failure-events window)
        total (count windowed)]
    (if (zero? total)
      {}
      (let [class-fraction (fn [[cls cnt]] [cls (safe-ratio cnt total)])]
        (->> windowed
             (map :failure/class)
             frequencies
             (map class-fraction)
             (into {}))))))

(defn compute-unknown-failure-rate
  [failure-events window]
  (get (compute-failure-distribution failure-events window)
       :failure.class/unknown
       0.0))

;------------------------------------------------------------------------------ Layer 0
;; SLI-7: Context Staleness Rate

(defn compute-context-staleness-rate
  [context-metrics window]
  (let [windowed (filter-by-window context-metrics window)
        total (count windowed)
        stale (count (filter :stale? windowed))]
    (safe-ratio stale total)))

;------------------------------------------------------------------------------ Layer 1
;; Aggregate SLI computation

(defn compute-all-slis
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
