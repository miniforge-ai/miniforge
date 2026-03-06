(ns ai.miniforge.observer.core
  "Core observer implementation for metrics collection and analysis.

   Pure metrics collector (NO LLM) that observes workflow execution,
   collects metrics, and generates performance reports."
  (:require
   [ai.miniforge.observer.protocol :as proto]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.interface :as wf]
   [clojure.string :as str]))

;; ============================================================================
;; Forward Declarations
;; ============================================================================

(declare analyze-duration-stats)
(declare analyze-cost-stats)
(declare analyze-token-stats)
(declare analyze-phase-stats)
(declare analyze-failure-patterns)
(declare analyze-trends)
(declare generate-summary-report)
(declare generate-detailed-report)
(declare generate-recommendations-report)

;; ============================================================================
;; Simple Observer Implementation
;; ============================================================================

(defrecord SimpleObserver [state]
  ;; State structure:
  ;; {:metrics {workflow-id -> workflow-metrics}
  ;;  :phase-metrics {workflow-id -> [phase-metrics...]}
  ;;  :metadata {:created inst :total-workflows int}}

  proto/Observer
  (collect-workflow-metrics [_this workflow-id workflow-state]
    (let [metrics (get workflow-state :workflow/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})
          status (get workflow-state :workflow/status :unknown)
          history (get workflow-state :workflow/history [])
          errors (get workflow-state :workflow/errors [])

          workflow-metrics (proto/workflow-metrics
                            {:workflow-id workflow-id
                             :metrics metrics
                             :status status
                             :timestamp (java.util.Date.)
                             :history history
                             :errors errors
                             :phases (get-in @state [:phase-metrics workflow-id] [])})]

      (swap! state assoc-in [:metrics workflow-id] workflow-metrics)
      (swap! state update-in [:metadata :total-workflows] (fnil inc 0))
      nil))

  (collect-phase-metrics [_this workflow-id phase-name phase-result]
    (let [metrics (get phase-result :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})
          success? (get phase-result :success? false)
          errors (get phase-result :errors [])

          phase-metrics (proto/phase-metrics
                         {:phase phase-name
                          :metrics metrics
                          :success? success?
                          :errors errors
                          :timestamp (java.util.Date.)})]

      (swap! state update-in [:phase-metrics workflow-id] (fnil conj []) phase-metrics)
      nil))

  (get-workflow-metrics [_this workflow-id]
    (get-in @state [:metrics workflow-id]))

  (get-all-metrics [_this opts]
    (let [limit (get opts :limit 100)
          since (get opts :since)
          status-filter (get opts :status)

          all-metrics (vals (get @state :metrics {}))

          filtered (cond->> all-metrics
                     since
                     (filter #(when-let [ts (:timestamp %)]
                                (.after ts since)))

                     status-filter
                     (filter #(= status-filter (:status %)))

                     true
                     (sort-by :timestamp #(compare %2 %1))

                     true
                     (take limit))]

      (vec filtered)))

  (analyze-metrics [this analysis-type opts]
    (case analysis-type
      :duration-stats (analyze-duration-stats this opts)
      :cost-stats (analyze-cost-stats this opts)
      :token-stats (analyze-token-stats this opts)
      :phase-stats (analyze-phase-stats this opts)
      :failure-patterns (analyze-failure-patterns this opts)
      :trends (analyze-trends this opts)
      {:error "Unknown analysis type" :type analysis-type}))

  (generate-report [this report-type opts]
    (case report-type
      :summary (generate-summary-report this opts)
      :detailed (generate-detailed-report this opts)
      :recommendations (generate-recommendations-report this opts)
      {:error "Unknown report type" :type report-type}))

  (create-telemetry-artifact [this _artifact-store]
    (let [all-metrics (proto/get-all-metrics this {:limit 1000})
          summary-stats (proto/analyze-metrics this :duration-stats {})

          telemetry-data {:type :telemetry
                          :collected-at (java.util.Date.)
                          :total-workflows (count all-metrics)
                          :summary summary-stats
                          :metrics all-metrics}

          artifact {:artifact/id (random-uuid)
                    :artifact/type :telemetry
                    :artifact/content (pr-str telemetry-data)
                    :artifact/metadata {:format :edn
                                       :workflows (count all-metrics)}}]

      ;; TODO: Save to artifact-store when integrated
      artifact))

  ;; Workflow Observer Protocol Implementation
  wf/WorkflowObserver
  (on-phase-start [_this _workflow-id _phase _context]
    ;; Phase start - no metrics to collect yet
    nil)

  (on-phase-complete [this workflow-id phase result]
    ;; Collect phase metrics on completion
    (proto/collect-phase-metrics this workflow-id phase result)
    nil)

  (on-phase-error [this workflow-id phase error]
    ;; Collect phase metrics for error
    (proto/collect-phase-metrics this workflow-id phase
                                 {:success? false
                                  :errors [error]
                                  :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}})
    nil)

  (on-workflow-complete [this workflow-id final-state]
    ;; Collect final workflow metrics
    (proto/collect-workflow-metrics this workflow-id final-state)
    nil)

  (on-rollback [_this _workflow-id _from-phase _to-phase _reason]
    ;; Rollback event - could track rollback patterns
    ;; Currently just logging via history in workflow state
    nil))

;; ============================================================================
;; Constructor
;; ============================================================================

(defn create-observer
  "Create a new SimpleObserver instance.

   Options:
   - :initial-state - Initial state map (default: empty state)

   Returns: SimpleObserver instance"
  ([]
   (create-observer {}))

  ([{:keys [initial-state]}]
   (->SimpleObserver
    (atom (merge {:metrics {}
                  :phase-metrics {}
                  :metadata {:created (java.util.Date.)
                            :total-workflows 0}}
                 initial-state)))))

;; ============================================================================
;; Analysis Functions
;; ============================================================================

(defn calculate-percentile
  "Calculate percentile from sorted values."
  [sorted-values percentile]
  (when (seq sorted-values)
    (let [idx (int (* (/ percentile 100.0) (count sorted-values)))]
      (nth sorted-values (min idx (dec (count sorted-values)))))))

(defn calculate-stats
  "Calculate statistics for a sequence of numeric values."
  [values]
  (when (seq values)
    (let [sorted (sort values)
          n (count sorted)
          sum (reduce + sorted)
          avg (double (/ sum n))
          p50 (calculate-percentile sorted 50)
          p95 (calculate-percentile sorted 95)
          p99 (calculate-percentile sorted 99)
          min-val (first sorted)
          max-val (last sorted)]
      {:count n
       :sum sum
       :avg avg
       :min min-val
       :max max-val
       :p50 p50
       :p95 p95
       :p99 p99})))

(defn analyze-duration-stats
  "Analyze workflow duration statistics."
  [observer opts]
  (let [limit (get opts :limit 100)
        metrics (proto/get-all-metrics observer {:limit limit})
        durations (map #(get-in % [:metrics :duration-ms]) metrics)
        durations (remove nil? durations)

        stats (calculate-stats durations)]

    (proto/analysis-result
     {:analysis-type :duration-stats
      :data stats
      :summary (when stats
                 (format "Analyzed %d workflows: avg=%.0fms, p50=%.0fms, p95=%.0fms, p99=%.0fms"
                         (:count stats)
                         (double (:avg stats))
                         (double (:p50 stats))
                         (double (:p95 stats))
                         (double (:p99 stats))))})))

(defn analyze-cost-stats
  "Analyze workflow cost statistics."
  [observer opts]
  (let [limit (get opts :limit 100)
        metrics (proto/get-all-metrics observer {:limit limit})
        costs (map #(get-in % [:metrics :cost-usd]) metrics)
        costs (remove nil? costs)

        stats (calculate-stats costs)]

    (proto/analysis-result
     {:analysis-type :cost-stats
      :data stats
      :summary (when stats
                 (format "Analyzed %d workflows: avg=$%.4f, p50=$%.4f, p95=$%.4f, total=$%.4f"
                         (:count stats)
                         (:avg stats)
                         (:p50 stats)
                         (:p95 stats)
                         (:sum stats)))})))

(defn analyze-token-stats
  "Analyze workflow token usage statistics."
  [observer opts]
  (let [limit (get opts :limit 100)
        metrics (proto/get-all-metrics observer {:limit limit})
        tokens (map #(get-in % [:metrics :tokens]) metrics)
        tokens (remove nil? tokens)

        stats (calculate-stats tokens)]

    (proto/analysis-result
     {:analysis-type :token-stats
      :data stats
      :summary (when stats
                 (format "Analyzed %d workflows: avg=%d tokens, p50=%d, p95=%d, total=%d"
                         (:count stats)
                         (int (:avg stats))
                         (:p50 stats)
                         (:p95 stats)
                         (:sum stats)))})))

(defn analyze-phase-stats
  "Analyze per-phase performance statistics."
  [observer opts]
  (let [limit (get opts :limit 100)
        metrics (proto/get-all-metrics observer {:limit limit})

        ;; Group phase metrics by phase name
        phase-groups (reduce
                      (fn [acc workflow-metrics]
                        (reduce
                         (fn [acc2 phase-metrics]
                           (update acc2 (:phase phase-metrics) (fnil conj []) phase-metrics))
                         acc
                         (:phases workflow-metrics [])))
                      {}
                      metrics)

        ;; Calculate stats for each phase
        phase-stats (into {}
                          (map (fn [[phase-name phase-list]]
                                 (let [durations (map #(get-in % [:metrics :duration-ms]) phase-list)
                                       tokens (map #(get-in % [:metrics :tokens]) phase-list)
                                       success-count (count (filter :success? phase-list))
                                       total-count (count phase-list)
                                       success-rate (if (pos? total-count)
                                                     (double (/ success-count total-count))
                                                     0.0)]
                                   [phase-name {:duration (calculate-stats (remove nil? durations))
                                               :tokens (calculate-stats (remove nil? tokens))
                                               :success-rate success-rate
                                               :total-executions total-count}]))
                               phase-groups))]

    (proto/analysis-result
     {:analysis-type :phase-stats
      :data phase-stats
      :summary (format "Analyzed %d unique phases across %d workflows"
                       (count phase-stats)
                       (count metrics))})))

(defn analyze-failure-patterns
  "Analyze workflow failure patterns."
  [observer opts]
  (let [limit (get opts :limit 100)
        metrics (proto/get-all-metrics observer {:limit limit})
        failed (filter phase/failed? metrics)

        ;; Analyze which phases fail most often
        failed-phases (reduce
                       (fn [acc workflow-metrics]
                         (let [failed-phases (filter #(not (:success? %)) (:phases workflow-metrics []))]
                           (reduce
                            (fn [acc2 phase-metrics]
                              (update acc2 (:phase phase-metrics) (fnil inc 0)))
                            acc
                            failed-phases)))
                       {}
                       failed)

        total-workflows (count metrics)
        failure-rate (if (pos? total-workflows)
                      (double (/ (count failed) total-workflows))
                      0.0)]

    (proto/analysis-result
     {:analysis-type :failure-patterns
      :data {:total-workflows total-workflows
             :failed-workflows (count failed)
             :failure-rate failure-rate
             :failed-phases (sort-by val > failed-phases)}
      :summary (format "Failure rate: %.1f%% (%d/%d workflows failed)"
                       (* 100.0 failure-rate)
                       (count failed)
                       total-workflows)
      :recommendations
      (when (seq failed-phases)
        [(str "Most problematic phases: "
              (str/join ", " (map first (take 3 (sort-by val > failed-phases)))))])})))

(defn analyze-trends
  "Analyze metrics trends over time."
  [observer opts]
  (let [limit (get opts :limit 100)
        metrics (proto/get-all-metrics observer {:limit limit})

        ;; Sort by timestamp (oldest first for trend analysis)
        sorted-metrics (sort-by :timestamp metrics)

        ;; Split into first half and second half
        n (count sorted-metrics)
        first-half (take (quot n 2) sorted-metrics)
        second-half (drop (quot n 2) sorted-metrics)

        ;; Calculate stats for each half
        first-durations (map #(get-in % [:metrics :duration-ms]) first-half)
        second-durations (map #(get-in % [:metrics :duration-ms]) second-half)

        first-costs (map #(get-in % [:metrics :cost-usd]) first-half)
        second-costs (map #(get-in % [:metrics :cost-usd]) second-half)

        duration-trend (when (and (seq first-durations) (seq second-durations))
                        {:first-avg (double (/ (reduce + first-durations) (count first-durations)))
                         :second-avg (double (/ (reduce + second-durations) (count second-durations)))})

        cost-trend (when (and (seq first-costs) (seq second-costs))
                    {:first-avg (double (/ (reduce + first-costs) (count first-costs)))
                     :second-avg (double (/ (reduce + second-costs) (count second-costs)))})]

    (proto/analysis-result
     {:analysis-type :trends
      :data {:duration-trend duration-trend
             :cost-trend cost-trend
             :sample-size n}
      :summary (cond
                 (and duration-trend cost-trend)
                 (format "Duration %.0f -> %.0fms (%.1f%% change), Cost $%.4f -> $%.4f (%.1f%% change)"
                         (:first-avg duration-trend)
                         (:second-avg duration-trend)
                         (* 100.0 (/ (- (:second-avg duration-trend) (:first-avg duration-trend))
                                    (:first-avg duration-trend)))
                         (:first-avg cost-trend)
                         (:second-avg cost-trend)
                         (* 100.0 (/ (- (:second-avg cost-trend) (:first-avg cost-trend))
                                    (:first-avg cost-trend))))

                 :else
                 "Insufficient data for trend analysis")})))

;; ============================================================================
;; Report Generation
;; ============================================================================

(defn generate-summary-report
  "Generate a summary performance report."
  [observer opts]
  (let [format (get opts :format :markdown)
        limit (get opts :limit 100)

        duration-analysis (proto/analyze-metrics observer :duration-stats {:limit limit})
        cost-analysis (proto/analyze-metrics observer :cost-stats {:limit limit})
        token-analysis (proto/analyze-metrics observer :token-stats {:limit limit})
        failure-analysis (proto/analyze-metrics observer :failure-patterns {:limit limit})

        report-data {:duration duration-analysis
                     :cost cost-analysis
                     :tokens token-analysis
                     :failures failure-analysis
                     :generated-at (java.util.Date.)}]

    (case format
      :edn report-data

      :markdown
      (str "# Workflow Performance Summary\n\n"
           "**Generated:** " (:generated-at report-data) "\n\n"
           "## Duration Statistics\n"
           (:summary duration-analysis) "\n\n"
           "## Cost Statistics\n"
           (:summary cost-analysis) "\n\n"
           "## Token Usage Statistics\n"
           (:summary token-analysis) "\n\n"
           "## Failure Analysis\n"
           (:summary failure-analysis) "\n"
           (when-let [recs (:recommendations failure-analysis)]
             (str "\n**Recommendations:**\n"
                  (str/join "\n" (map #(str "- " %) recs)) "\n")))

      report-data)))

(defn generate-detailed-report
  "Generate a detailed metrics breakdown report."
  [observer opts]
  (let [format (get opts :format :edn)
        limit (get opts :limit 50)

        all-metrics (proto/get-all-metrics observer {:limit limit})
        phase-analysis (proto/analyze-metrics observer :phase-stats {:limit limit})

        report-data {:workflows all-metrics
                     :phase-breakdown phase-analysis
                     :total-workflows (count all-metrics)
                     :generated-at (java.util.Date.)}]

    (case format
      :edn report-data
      :markdown (str "# Detailed Workflow Metrics\n\n"
                     "**Generated:** " (:generated-at report-data) "\n"
                     "**Total Workflows:** " (:total-workflows report-data) "\n\n"
                     "## Phase Performance\n"
                     (:summary phase-analysis) "\n\n"
                     "## Recent Workflows\n"
                     (str/join "\n" (map #(format "- %s: %s (%.0fms, $%.4f)"
                                                   (:workflow-id %)
                                                   (:status %)
                                                   (get-in % [:metrics :duration-ms])
                                                   (get-in % [:metrics :cost-usd]))
                                         (take 10 all-metrics))))
      report-data)))

(defn generate-recommendations-report
  "Generate recommendations for workflow improvements."
  [observer opts]
  (let [format (get opts :format :markdown)
        limit (get opts :limit 100)

        failure-analysis (proto/analyze-metrics observer :failure-patterns {:limit limit})
        phase-analysis (proto/analyze-metrics observer :phase-stats {:limit limit})
        trends-analysis (proto/analyze-metrics observer :trends {:limit limit})

        ;; Generate recommendations based on analysis
        recommendations []

        ;; Failure-based recommendations
        recommendations (if-let [recs (:recommendations failure-analysis)]
                         (into recommendations recs)
                         recommendations)

        ;; Phase performance recommendations
        phase-data (:data phase-analysis)
        slow-phases (filter (fn [[_phase stats]]
                             (when-let [avg-duration (get-in stats [:duration :avg])]
                               (> avg-duration 30000))) ;; > 30 seconds
                           phase-data)

        recommendations (if (seq slow-phases)
                         (conj recommendations
                               (str "Optimize slow phases: "
                                    (str/join ", " (map first slow-phases))))
                         recommendations)

        ;; Trend-based recommendations
        duration-trend (get-in trends-analysis [:data :duration-trend])
        recommendations (if (and duration-trend
                                (> (:second-avg duration-trend)
                                   (* 1.2 (:first-avg duration-trend)))) ;; 20% increase
                         (conj recommendations
                               "Warning: Workflow duration increasing over time - investigate performance regression")
                         recommendations)

        report-data {:recommendations recommendations
                     :failure-analysis failure-analysis
                     :phase-analysis phase-analysis
                     :trends-analysis trends-analysis
                     :generated-at (java.util.Date.)}]

    (case format
      :edn report-data
      :markdown (str "# Workflow Improvement Recommendations\n\n"
                     "**Generated:** " (:generated-at report-data) "\n\n"
                     "## Recommendations\n"
                     (if (seq recommendations)
                       (str/join "\n" (map #(str "- " %) recommendations))
                       "No specific recommendations at this time. System performing well!")
                     "\n\n"
                     "## Supporting Analysis\n\n"
                     "### Failures\n" (:summary failure-analysis) "\n\n"
                     "### Trends\n" (:summary trends-analysis) "\n")
      report-data)))
