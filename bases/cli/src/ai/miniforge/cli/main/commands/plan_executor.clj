(ns ai.miniforge.cli.main.commands.plan-executor
  "Execute pre-planned DAG or plan files directly, skipping explore/plan phases."
  (:require
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.registry :as phase-reg])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0
;; Format normalization

(defn deterministic-uuid
  "Generate a deterministic UUID from a string id (for DAG task-id → UUID conversion)."
  [s]
  (UUID/nameUUIDFromBytes (.getBytes (str s) "UTF-8")))

(defn normalize-dag-task
  "Normalize a DAG-format task to plan-format task."
  [task]
  (let [task-id (if (uuid? (:task/id task))
                  (:task/id task)
                  (deterministic-uuid (:task/id task)))
        deps (or (:task/dependencies task)
                 (when-let [d (:task/deps task)]
                   (set (map #(if (uuid? %) % (deterministic-uuid %)) d)))
                 #{})
        description (or (:task/description task) (:description task))
        criteria (let [ac (or (:task/acceptance-criteria task)
                              (:acceptance-criteria task))]
                   (cond
                     (vector? ac) ac
                     (string? ac) [ac]
                     :else []))]
    {:task/id task-id
     :task/dependencies deps
     :task/description description
     :task/acceptance-criteria criteria
     :task/type (:task/type task :implement)}))

(defn normalize-dag-to-plan
  "Convert DAG format ({:dag-id, :tasks}) to plan format ({:plan/id, :plan/tasks})."
  [dag]
  {:plan/id (:dag-id dag)
   :plan/title (or (:description dag) (:dag-id dag))
   :plan/tasks (mapv normalize-dag-task (:tasks dag))})

(defn detect-plan-format
  "Detect whether input is already plan format or needs conversion."
  [parsed]
  (cond
    (:plan/id parsed) :plan
    (:dag-id parsed)  :dag
    :else             nil))

;------------------------------------------------------------------------------ Layer 1
;; Execution context setup

(defn build-execution-workflow
  "Build a workflow definition for plan execution (implement → verify → done)."
  [plan-id]
  {:workflow/id (keyword (str "plan-exec-" plan-id))
   :workflow/version "2.0.0"
   :workflow/name (str "Plan execution: " plan-id)
   :workflow/pipeline [{:phase :implement} {:phase :verify} {:phase :done}]
   :workflow/config {:max-tokens 20000 :max-iterations 50}})

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn execute-plan
  "Execute a pre-planned DAG or plan file directly via dag-orchestrator.

   Normalizes DAG format to plan format if needed, sets up execution context,
   and delegates to execute-plan-as-dag."
  [parsed opts]
  (let [format-type (detect-plan-format parsed)
        plan (case format-type
               :dag (normalize-dag-to-plan parsed)
               :plan parsed)
        plan-id (or (:plan/id plan) (str (random-uuid)))
        task-count (count (:plan/tasks plan))
        quiet (:quiet opts false)
        workflow (build-execution-workflow plan-id)
        event-stream (es/create-event-stream)
        workflow-id (random-uuid)
        control-state (es/create-control-state)
        command-poller-cleanup (dashboard/start-command-poller! workflow-id control-state)
        llm-client (context/create-llm-client workflow nil quiet)
        callbacks {:on-phase-start (fn [_ctx interceptor]
                                     (when-not quiet
                                       (display/print-info
                                        (str "Phase: " (get-in interceptor [:config :phase])))))
                   :on-phase-complete (fn [_ctx _interceptor _result]
                                        nil)}
        ctx (context/create-workflow-context {:callbacks callbacks
                                             :event-stream event-stream
                                             :workflow-id workflow-id
                                             :workflow-type (:workflow/id workflow)
                                             :workflow-version (:workflow/version workflow)
                                             :llm-client llm-client
                                             :quiet quiet
                                             :spec-title (:plan/title plan)
                                             :control-state control-state
                                             :skip-lifecycle-events false})
        ;; Enrich context with workflow definition for sub-workflow construction
        ctx (assoc ctx :execution/workflow workflow
                       :workflow-id workflow-id
                       :pre-completed-ids (get opts :pre-completed-dag-tasks #{}))]
    (when-not quiet
      (display/print-info (str "Executing plan: " plan-id " (" task-count " tasks)"))
      (display/print-info (str "Format: " (name format-type))))
    (try
      (let [execute-dag (requiring-resolve 'ai.miniforge.workflow.dag-orchestrator/execute-plan-as-dag)
            result (execute-dag plan ctx)]
        (when-not quiet
          (let [completed (count (filter phase-reg/succeeded? (vals result)))
                failed (count (filter phase-reg/failed? (vals result)))]
            (display/print-info (str "Plan execution complete: "
                                     completed " completed, " failed " failed"))))
        result)
      (catch Exception e
        (display/print-error (str "Plan execution failed: " (ex-message e)))
        (throw e))
      (finally
        (when command-poller-cleanup (command-poller-cleanup))))))
