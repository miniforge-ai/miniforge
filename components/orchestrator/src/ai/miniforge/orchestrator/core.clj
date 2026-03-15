(ns ai.miniforge.orchestrator.core
  "Core orchestrator (Control Plane) implementation.

   The orchestrator is the Control Plane for miniforge.ai:
   - Task routing: directing tasks to appropriate agents
   - Budget management: tracking and enforcing resource budgets
   - Knowledge coordination: injecting knowledge, capturing learnings
   - Policy enforcement: escalation rules, approval gates

   Workflow execution is delegated to the workflow component.
   Meta-loop management is delegated to the operator component."
  (:require
   [ai.miniforge.orchestrator.messages :as messages]
   [ai.miniforge.orchestrator.protocol :as proto]
   [ai.miniforge.workflow.interface :as wf]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(defn build-repair-learning
  "Build a learning capture map from a repair history entry."
  [agent-role task repair-history]
  (let [last-repair (last repair-history)]
    {:agent agent-role
     :task-id (:task/id task)
     :title (messages/t :repair/title {:error-type (name (:error-type last-repair))})
     :content (messages/t :repair/content
                          {:task (:task/title task)
                           :agent (name agent-role)
                           :iterations (count repair-history)
                           :fix-description (:fix-description last-repair)})
     :tags [:repair :inner-loop (keyword (name agent-role))]
     :confidence 0.7}))

(def default-config
  "Default control plane configuration."
  {:default-budget {:max-tokens 100000
                    :max-cost-usd 10.0
                    :timeout-ms (* 30 60 1000)} ; 30 minutes
   :knowledge-injection? true
   :learning-capture? true
   :escalation-threshold 3
   :log-level :info})

(def task-type->agent-role
  "Mapping of task types to agent roles."
  {:plan       :planner
   :design     :planner
   :implement  :implementer
   :test       :tester
   :review     :reviewer})

;------------------------------------------------------------------------------ Layer 1
;; Task Router implementation

(defrecord SimpleTaskRouter [config]
  proto/TaskRouter

  (route-task [_this task _context]
    (let [task-type (:task/type task)
          agent-role (get task-type->agent-role task-type :implementer)]
      {:agent-role agent-role
       :reason (messages/t :route/reason {:task-type task-type :agent-role agent-role})}))

  (can-handle? [_this task agent-role]
    (let [task-type (:task/type task)
          expected-role (get task-type->agent-role task-type)]
      (= expected-role agent-role))))

(defn create-router
  "Create a task router."
  ([] (create-router {}))
  ([config] (->SimpleTaskRouter config)))

;------------------------------------------------------------------------------ Layer 2
;; Budget Manager implementation

(defrecord SimpleBudgetManager [budgets usage]
  proto/BudgetManager

  (track-usage [_this workflow-id new-usage]
    (swap! usage update workflow-id
           (fn [current]
             (let [curr (or current {:tokens 0 :cost-usd 0.0 :duration-ms 0})]
               {:tokens (+ (:tokens curr) (get new-usage :tokens 0))
                :cost-usd (+ (:cost-usd curr) (get new-usage :cost-usd 0.0))
                :duration-ms (+ (:duration-ms curr) (get new-usage :duration-ms 0))}))))

  (check-budget [_this workflow-id]
    (let [budget (get @budgets workflow-id (:default-budget default-config))
          used (get @usage workflow-id {:tokens 0 :cost-usd 0.0 :duration-ms 0})]
      {:within-budget? (and (<= (:tokens used) (:max-tokens budget))
                            (<= (:cost-usd used) (:max-cost-usd budget))
                            (<= (:duration-ms used) (:timeout-ms budget)))
       :remaining {:tokens (- (:max-tokens budget) (:tokens used))
                   :cost-usd (- (:max-cost-usd budget) (:cost-usd used))
                   :time-ms (- (:timeout-ms budget) (:duration-ms used))}
       :used used
       :budget budget}))

  (set-budget [_this workflow-id budget]
    (swap! budgets assoc workflow-id budget)))

(defn create-budget-manager
  "Create a budget manager."
  []
  (->SimpleBudgetManager (atom {}) (atom {})))

;------------------------------------------------------------------------------ Layer 3
;; Knowledge Coordinator implementation

(defn format-zettel-for-context
  "Format a zettel for inclusion in agent context."
  [zettel]
  (str "### " (:zettel/title zettel)
       (when-let [dewey (:zettel/dewey zettel)]
         (str " [" dewey "]"))
       "\n"
       (:zettel/content zettel)
       "\n"))

(defn format-knowledge-block
  "Format injected knowledge as a context block."
  [zettels agent-role]
  (when (seq zettels)
    (str (messages/t :knowledge/header {:role (name agent-role)})
         (messages/t :knowledge/preamble)
         (apply str (map format-zettel-for-context zettels))
         (messages/t :knowledge/separator))))

(defrecord SimpleKnowledgeCoordinator [knowledge-store config]
  proto/KnowledgeCoordinator

  (inject-for-agent [_this agent-role task context]
    (when (:knowledge-injection? config)
      (let [task-tags (or (:task/tags task) [])
            context-tags (get context :tags [])
            query-context {:tags (distinct (concat task-tags context-tags))}
            zettels (knowledge/inject-knowledge knowledge-store agent-role query-context)]
        {:formatted (format-knowledge-block zettels agent-role)
         :zettels zettels
         :count (count zettels)})))

  (capture-execution-learning [this execution-result]
    (when (and (:learning-capture? config)
               (:repaired? execution-result))
      (let [{:keys [agent-role task repair-history]} execution-result]
        (when (seq repair-history)
          (let [learning (knowledge/capture-inner-loop-learning
                          knowledge-store
                          (build-repair-learning agent-role task repair-history))]
            ;; Check if learning should be promoted to a rule
            (when learning
              (let [learning-id (or (:zettel/id learning) (:id learning))
                    promotion-check (proto/should-promote-learning? this learning)]
                (when (and (:promote? promotion-check) learning-id)
                  (try
                    (knowledge/promote-learning knowledge-store learning-id {})
                    (catch Exception _e nil)))))
            learning)))))

  (should-promote-learning? [_this learning]
    (let [confidence (get-in learning [:zettel/source :source/confidence] 0)
          promotable? (>= confidence 0.85)]
      {:promote? promotable?
       :confidence confidence
       :reason (if promotable?
                 (messages/t :promote/ready)
                 (messages/t :promote/below-threshold {:threshold 0.85}))})))

(defn create-knowledge-coordinator
  "Create a knowledge coordinator."
  ([knowledge-store] (create-knowledge-coordinator knowledge-store default-config))
  ([knowledge-store config]
   (->SimpleKnowledgeCoordinator knowledge-store config)))

;------------------------------------------------------------------------------ Layer 4
;; Control Plane implementation

(defrecord ControlPlane [config router budget-mgr knowledge-coord
                          workflow-mgr operator llm-backend artifact-store
                          active-workflows logger]
  proto/Orchestrator

  (execute-workflow [_this spec context]
    (let [workflow-id (wf/start workflow-mgr spec context)]

      ;; Set budget if provided
      (when-let [budget (:budget context)]
        (proto/set-budget budget-mgr workflow-id budget))

      (log/info logger :control-plane :control-plane/workflow-started
                {:data {:workflow-id workflow-id
                        :spec-title (:title spec)}})

      ;; Track active workflow
      (swap! active-workflows assoc workflow-id
             {:started-at (System/currentTimeMillis)
              :spec spec
              :context context})

      ;; Execute workflow through the workflow component
      (let [wf-context (merge context
                              {:llm-backend llm-backend
                               :router router
                               :budget-manager budget-mgr
                               :knowledge-coordinator knowledge-coord
                               :knowledge-store (:knowledge-store knowledge-coord)
                               :artifact-store artifact-store})
            result (wf/run-workflow workflow-mgr spec wf-context)]

        ;; Track final budget usage from workflow metrics
        (when-let [metrics (:workflow/metrics result)]
          (proto/track-usage budget-mgr workflow-id metrics))

        ;; Remove from active
        (swap! active-workflows dissoc workflow-id)

        (log/info logger :control-plane :control-plane/workflow-completed
                  {:data {:workflow-id workflow-id
                          :status (:workflow/status result)}})

        {:workflow-id workflow-id
         :status (:workflow/status result)
         :results {:artifacts (:workflow/artifacts result)
                   :learnings []}
         :metrics (:workflow/metrics result)})))

  (get-workflow-status [_this workflow-id]
    (when-let [state (wf/get-state workflow-mgr workflow-id)]
      {:workflow-id workflow-id
       :status (:workflow/status state)
       :phase (:workflow/phase state)
       :task-count (count (:workflow/history state))
       :artifact-count (count (:workflow/artifacts state))
       :errors (:workflow/errors state)
       :budget (proto/check-budget budget-mgr workflow-id)}))

  (cancel-workflow [_this workflow-id]
    (when (wf/get-state workflow-mgr workflow-id)
      (wf/fail workflow-mgr workflow-id {:type :cancelled :message (messages/t :workflow/cancelled)})
      (swap! active-workflows dissoc workflow-id)
      true))

  (get-workflow-results [_this workflow-id]
    (when-let [state (wf/get-state workflow-mgr workflow-id)]
      {:artifacts (:workflow/artifacts state)
       :learnings []
       :metrics (merge (:workflow/metrics state)
                       (:used (proto/check-budget budget-mgr workflow-id)))}))

  (pause-workflow [_this workflow-id]
    (when-let [state (wf/get-state workflow-mgr workflow-id)]
      (when (= :running (:workflow/status state))
        ;; Workflow component doesn't have pause/resume yet
        ;; This is a stub for future implementation
        (log/info logger :control-plane :control-plane/workflow-paused
                  {:data {:workflow-id workflow-id}})
        true)))

  (resume-workflow [_this workflow-id]
    (when-let [state (wf/get-state workflow-mgr workflow-id)]
      (when (= :paused (:workflow/status state))
        (log/info logger :control-plane :control-plane/workflow-resumed
                  {:data {:workflow-id workflow-id}})
        true))))

(defn create-control-plane
  "Create a control plane (orchestrator) with all components.

   Arguments:
   - llm-backend      - LLM backend for agent execution
   - knowledge-store  - Knowledge store for injection/learning
   - artifact-store   - Artifact store for results

   Options:
   - :config   - Override default configuration
   - :logger   - Logger instance
   - :operator - Operator instance for meta-loop integration"
  [llm-backend knowledge-store artifact-store & [{:keys [config logger operator]}]]
  (let [merged-config (merge default-config config)
        router (create-router merged-config)
        budget-mgr (create-budget-manager)
        knowledge-coord (create-knowledge-coordinator knowledge-store merged-config)
        workflow-mgr (wf/create-workflow)
        log (or logger (log/create-logger {:min-level :info}))]

    ;; Add operator as workflow observer if provided
    (when operator
      (wf/add-observer workflow-mgr operator))

    (->ControlPlane
     merged-config
     router
     budget-mgr
     knowledge-coord
     workflow-mgr
     operator
     llm-backend
     artifact-store
     (atom {})
     log)))

;; Alias for backward compatibility
(def create-orchestrator create-control-plane)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.llm.interface :as llm]
           '[ai.miniforge.artifact.interface :as artifact])

  ;; Create components
  (def llm-client (llm/mock-client {:output "Test response"}))
  (def k-store (knowledge/create-store))
  (def a-store (artifact/create-store))

  ;; Create control plane
  (def cp (create-control-plane llm-client k-store a-store))

  ;; Execute workflow
  (proto/execute-workflow cp
                          {:title "Test workflow"
                           :description "A test workflow"}
                          {})

  :end)
