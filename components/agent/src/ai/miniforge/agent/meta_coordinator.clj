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

(ns ai.miniforge.agent.meta-coordinator
  "Coordinator for meta-agent team.

   The coordinator is a lightweight message router, NOT a controller.
   It routes workflow state to meta-agents and aggregates their decisions.
   Any meta-agent can halt the workflow - coordinator just enforces it."
  (:require [ai.miniforge.agent.meta-protocol :as mp]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.reliability.interface :as reliability]
            [ai.miniforge.diagnosis.interface :as diagnosis]
            [ai.miniforge.improvement.interface :as improvement]
            [ai.miniforge.event-stream.interface.stream :as stream]
            [ai.miniforge.event-stream.interface.events :as events]))

(defrecord MetaCoordinator
  [agents           ; Vector of MetaAgent instances
   last-checks      ; Atom of {agent-id -> last-check-result}
   check-history])  ; Atom of [{:timestamp :agent-id :result}...]

(defn create-coordinator
  "Create a meta-agent coordinator.

   Arguments:
   - agents: Vector of MetaAgent protocol instances

   Returns: MetaCoordinator record"
  [agents]
  (->MetaCoordinator
   agents
   (atom {})
   (atom [])))

(defn should-check-agent?
  "Determine if an agent should be checked now based on interval."
  [agent last-check-time]
  (if-not last-check-time
    true  ; Never checked, do it now
    (let [config (mp/get-meta-config agent)
          interval (:check-interval-ms config)
          now (System/currentTimeMillis)
          elapsed (- now last-check-time)]
      (>= elapsed interval))))

(defn record-check!
  "Record a health check result."
  [coordinator agent-id result]
  (let [now (System/currentTimeMillis)]
    ;; Update last check time
    (swap! (:last-checks coordinator) assoc agent-id now)
    ;; Add to history (keep last 1000)
    (swap! (:check-history coordinator)
           (fn [history]
             (let [entry {:timestamp now
                          :agent-id agent-id
                          :result result}]
               (take 1000 (cons entry history)))))
    result))

(defn check-all-agents
  "Run health checks on all enabled agents that are due for checking.

   Arguments:
   - coordinator: MetaCoordinator instance
   - workflow-state: Current workflow state

   Returns:
   {:status :healthy|:warning|:halt
    :checks [{:agent-id :progress-monitor :status :healthy ...}]
    :halt-reason \"...\" (if status is :halt)
    :halting-agent :progress-monitor (if status is :halt)}"
  [coordinator workflow-state]
  (let [agents (:agents coordinator)
        last-checks @(:last-checks coordinator)

        ;; Run checks on agents that are due
        checks (for [agent agents
                     :let [config (mp/get-meta-config agent)]
                     :when (:enabled? config)
                     :when (should-check-agent? agent (get last-checks (:id config)))]
                 (try
                   (let [result (mp/check-health agent workflow-state)]
                     (record-check! coordinator (:id config) result)
                     result)
                   (catch Exception e
                     ;; Return warning status on error - logging handled by caller if needed
                     (mp/create-health-check
                      (:id config)
                      :warning
                      (str "Health check error: " (ex-message e))
                      {:error (ex-data e)}))))

        ;; Find any halt signals
        halt-check (first (filter mp/halt? checks))

        ;; Find any warnings
        warning-checks (filter mp/warning? checks)

        ;; Aggregate status
        overall-status (cond
                         halt-check :halt
                         (seq warning-checks) :warning
                         :else :healthy)]

    (response/status-check overall-status
                           (cond-> {:checks checks}
                             halt-check (assoc :halt-reason (:message halt-check)
                                               :halting-agent (:agent/id halt-check))
                             (seq warning-checks) (assoc :warnings (mapv :message warning-checks))))))

(defn reset-all-agents!
  "Reset state of all meta-agents. Called when workflow restarts."
  [coordinator]
  (doseq [agent (:agents coordinator)]
    (try
      (mp/reset-state! agent)
      (catch Exception _e
        ;; Silently continue on reset errors - caller can check if needed
        nil)))
  (reset! (:last-checks coordinator) {})
  coordinator)

(defn get-check-history
  "Get recent health check history.

   Options:
   - :limit - Max number of checks to return (default: 100)
   - :agent-id - Filter by specific agent
   - :status - Filter by status (:healthy, :warning, :halt)"
  ([coordinator]
   (get-check-history coordinator {}))
  ([coordinator {:keys [limit agent-id status]
                 :or {limit 100}}]
   (cond->> @(:check-history coordinator)
     agent-id (filter #(= agent-id (:agent-id %)))
     status (filter #(= status (get-in % [:result :status])))
     limit (take limit))))

(defn get-agent-stats
  "Get statistics for all meta-agents.

   Returns:
   {:agents [{:id :progress-monitor
              :name \"Progress Monitor\"
              :checks-run 42
              :last-check inst
              :last-status :healthy
              :halt-count 2
              :warning-count 5}]}"
  [coordinator]
  (let [history @(:check-history coordinator)
        agents (:agents coordinator)]
    {:agents
     (for [agent agents
           :let [config (mp/get-meta-config agent)
                 agent-id (:id config)
                 agent-checks (filter #(= agent-id (:agent-id %)) history)
                 last-check (first agent-checks)]]
       {:id agent-id
        :name (:name config)
        :enabled? (:enabled? config)
        :can-halt? (:can-halt? config)
        :checks-run (count agent-checks)
        :last-check (:timestamp last-check)
        :last-status (get-in last-check [:result :status])
        :halt-count (count (filter #(= :halt (get-in % [:result :status])) agent-checks))
        :warning-count (count (filter #(= :warning (get-in % [:result :status])) agent-checks))})}))

(defn add-agent!
  "Dynamically add a meta-agent to the coordinator.

   Returns: Updated coordinator"
  [coordinator agent]
  (update coordinator :agents conj agent))

(defn remove-agent!
  "Remove a meta-agent by ID.

   Returns: Updated coordinator"
  [coordinator agent-id]
  (update coordinator :agents
          (fn [agents]
            (vec (remove #(= agent-id (-> % mp/get-meta-config :id))
                         agents)))))

;;----------------------------------------------------------------------------- Learning Layer
;; Meta-loop cycle — N1 §3.3

(defn run-meta-loop-cycle!
  "Execute one meta-loop learning cycle per N1 §3.3.

   Orchestrates:
   1. Compute SLIs, check SLOs, update error budgets (reliability layer)
   2. Evaluate degradation mode — may transition :nominal → :degraded → :safe-mode
   3. Run full diagnosis pipeline: extract signals → correlate → diagnose
   4. Generate improvement proposals from diagnoses
   5. Store proposals in pipeline (pending operator approval)
   6. Emit :meta-loop/cycle-completed event

   Proposal generation is skipped when degradation mode is :degraded or :safe-mode
   to avoid generating noise during active incidents.

   Arguments:
     ctx - map with keys:
       :event-stream         - event stream atom for emitting cycle events
       :reliability-engine   - ReliabilityEngine (reliability/create-engine)
       :degradation-manager  - DegradationManager (reliability/create-degradation-manager)
       :improvement-pipeline - pipeline atom (improvement/create-pipeline)
       :metrics              - collected metrics map (see reliability/compute-all-slis):
                                 :workflow-metrics :phase-metrics :gate-metrics
                                 :tool-metrics :failure-events :context-metrics
       :training-examples    - vector of training records (optional)

   Returns: cycle result map with :cycle/* keys."
  [{:keys [event-stream reliability-engine degradation-manager
           improvement-pipeline metrics training-examples]}]
  (let [started-at (java.util.Date.)]
    (try
      (let [;; 1. Compute SLIs, check SLOs, update error budgets
            {:keys [slo-checks budgets breaches recommendation] :as cycle-result}
            (reliability/compute-cycle! reliability-engine (or metrics {}))

            ;; 2. Evaluate and possibly transition degradation mode
            current-mode (reliability/evaluate-degradation! degradation-manager budgets)

            ;; 3. Run full diagnosis: signals → correlations → diagnoses
            {:keys [signals correlations diagnoses]}
            (diagnosis/run-diagnosis
             {:slo-checks        slo-checks
              :failure-events    (get metrics :failure-events [])
              :training-examples (or training-examples [])
              :phase-metrics     (get metrics :phase-metrics [])})

            ;; 4. Generate proposals only in nominal mode — avoid noise during incidents
            proposals (when (= :nominal current-mode)
                        (improvement/generate-proposals diagnoses))

            ;; 5. Store proposals
            _ (when (and improvement-pipeline (seq proposals))
                (doseq [p proposals]
                  (improvement/store-proposal! improvement-pipeline p)))

            duration-ms (- (System/currentTimeMillis) (.getTime started-at))

            result {:cycle/started-at       started-at
                    :cycle/duration-ms      duration-ms
                    :cycle/sli-count        (count (:slis cycle-result))
                    :cycle/breach-count     (count breaches)
                    :cycle/signal-count     (count signals)
                    :cycle/correlation-count (count correlations)
                    :cycle/diagnosis-count  (count diagnoses)
                    :cycle/proposal-count   (count (or proposals []))
                    :cycle/degradation-mode current-mode
                    :cycle/recommendation   recommendation}]

        ;; 6. Emit summary event
        (when event-stream
          (stream/publish! event-stream
                           (events/meta-loop-cycle-completed event-stream result)))

        result)

      (catch Exception e
        (when event-stream
          (stream/publish! event-stream
                           (events/meta-loop-cycle-failed event-stream e)))
        (throw e)))))

;;----------------------------------------------------------------------------- Meta-loop context

(defrecord MetaLoopContext
  [event-stream
   reliability-engine
   degradation-manager
   improvement-pipeline
   metrics-store])    ; atom: {:workflow-metrics [] :failure-events [] :phase-metrics []}

(defn create-meta-loop-context
  "Create a MetaLoopContext that bundles all stateful objects needed for a cycle.

   Arguments:
     event-stream - event stream atom
     config       - optional:
       :reliability   - config for reliability/create-engine
       :degradation   - config for reliability/create-degradation-manager

   Returns: MetaLoopContext record."
  [event-stream & [config]]
  (->MetaLoopContext
   event-stream
   (reliability/create-engine event-stream (:reliability config))
   (reliability/create-degradation-manager event-stream (:degradation config))
   (improvement/create-pipeline)
   (atom {:workflow-metrics []
          :failure-events   []
          :phase-metrics    []
          :gate-metrics     []
          :tool-metrics     []
          :context-metrics  []})))

(defn record-workflow-outcome!
  "Record a workflow completion for use in the next meta-loop cycle.

   Arguments:
     ctx           - MetaLoopContext
     workflow-id   - uuid
     status        - :completed | :failed | :escalated
     failure-class - optional :failure.class/* keyword (when failed)"
  [ctx workflow-id status & [failure-class]]
  (let [now (java.util.Date.)]
    (swap! (:metrics-store ctx) update :workflow-metrics conj
           {:workflow/id workflow-id
            :status      status
            :timestamp   now})
    (when failure-class
      (swap! (:metrics-store ctx) update :failure-events conj
             {:failure/class failure-class
              :workflow/id   workflow-id
              :timestamp     now})))
  nil)

(defn run-cycle-from-context!
  "Run one meta-loop cycle using the accumulated metrics in ctx.

   Arguments:
     ctx               - MetaLoopContext
     training-examples - optional vector of training records

   Returns: cycle result map."
  [ctx & [training-examples]]
  (run-meta-loop-cycle!
   {:event-stream         (:event-stream ctx)
    :reliability-engine   (:reliability-engine ctx)
    :degradation-manager  (:degradation-manager ctx)
    :improvement-pipeline (:improvement-pipeline ctx)
    :metrics              @(:metrics-store ctx)
    :training-examples    training-examples}))

(comment
  ;; Usage example
  ;; (require '[ai.miniforge.agent.meta.progress-monitor :as pm])
  ;; (def progress-monitor (pm/create-progress-monitor-agent))
  ;; (def test-quality (create-test-quality-agent))  ; TODO: implement
  ;; (def conflict-detector (create-conflict-detector-agent))  ; TODO: implement

  ;; (def coordinator (create-coordinator
  ;;                   [progress-monitor test-quality conflict-detector]))

  ;; Check all agents
  ;; (def workflow-state
  ;;   {:workflow/id (random-uuid)
  ;;    :workflow/phase :implement
  ;;    :workflow/iterations 3
  ;;    :workflow/streaming-activity [...]
  ;;    :workflow/files-written [...]})

  ;; (def result (check-all-agents coordinator workflow-state))
  ;; ;; => {:status :healthy
  ;; ;;     :checks [{:agent/id :progress-monitor :status :healthy ...}
  ;; ;;              {:agent/id :test-quality :status :healthy ...}
  ;; ;;              {:agent/id :conflict-detector :status :halt ...}]
  ;; ;;     :halt-reason "Merge conflict detected"
  ;; ;;     :halting-agent :conflict-detector}

  ;; ;; If any agent signals halt, workflow stops
  ;; (when (= :halt (:status result))
  ;;   (println "Workflow halted by" (:halting-agent result))
  ;;   (println "Reason:" (:halt-reason result)))

  ;; ;; Get statistics
  ;; (get-agent-stats coordinator)

  ;; ;; Get history
  ;; (get-check-history coordinator {:limit 50 :agent-id :progress-monitor})

  ;; Process-level meta-loop context
  ;; (def stream (ai.miniforge.event-stream.interface/create-event-stream))
  ;; (def ctx (create-meta-loop-context stream))
  ;;
  ;; ;; After workflow runs:
  ;; (record-workflow-outcome! ctx wf-id :completed nil)
  ;; (record-workflow-outcome! ctx wf-id :failed :failure.class/timeout)
  ;;
  ;; ;; Run a learning cycle:
  ;; (run-cycle-from-context! ctx)
  ;; ;; => {:cycle/sli-count 7 :cycle/signal-count 2 :cycle/degradation-mode :nominal ...}
  )
