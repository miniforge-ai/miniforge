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

(ns ai.miniforge.dag-executor.state
  "DAG run and task workflow state management.

   Layer 0: State schemas and constructors
   Layer 1: State transitions (pure functions)
   Layer 2: State queries
   Layer 3: State persistence (atom-based)"
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.state-profile :as profiles]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.messages.interface :as messages]
   [clojure.set :as set]))

(def ^:private t
  (messages/create-translator
   "config/dag-executor/state/messages/en-US.edn"
   :dag-executor.state/messages))

;------------------------------------------------------------------------------ Layer 0
;; State schemas and enums

(def task-statuses
  "Valid task workflow statuses.
   Defaults to the kernel profile."
  (:task-statuses (profiles/default-profile)))

(def terminal-statuses
  "Terminal statuses (no further transitions)."
  (:terminal-statuses (profiles/default-profile)))

(def run-statuses
  "Valid DAG run statuses."
  #{:pending    ; Not started
    :running    ; In progress
    :paused     ; Temporarily halted
    :completed  ; All tasks merged
    :failed     ; Unrecoverable failure
    :partial})  ; Some tasks merged, others failed/skipped

(def valid-transitions
  "Valid state transitions for the default task workflow profile."
  (:valid-transitions (profiles/default-profile)))

(defn resolve-task-profile
  [task-state]
  (profiles/resolve-profile (:task/state-profile task-state)))

(defn resolve-run-profile
  [run-state]
  (profiles/resolve-profile (get-in run-state [:run/config :state-profile])))

(defn success-terminal-status?
  [profile status]
  (contains? (:success-terminal-statuses profile) status))

(defn status-bucket-keys
  [profile status]
  (cond-> []
    (success-terminal-status? profile status) (conj :run/completed)
    (= status :merged) (conj :run/merged)
    (= status :failed) (conj :run/failed)
    (= status :skipped) (conj :run/skipped)))

(defn update-run-status-buckets
  [run-state profile task-id status]
  (reduce (fn [state bucket-key]
            (update state bucket-key (fnil conj #{}) task-id))
          run-state
          (status-bucket-keys profile status)))

(declare mark-completed!)

;------------------------------------------------------------------------------ Layer 0
;; PR info schema

(defn create-pr-info
  "Create PR information map."
  [& {:keys [id url branch base-sha head-sha]}]
  {:pr/id id
   :pr/url url
   :pr/branch branch
   :pr/base-sha base-sha
   :pr/head-sha head-sha})

;------------------------------------------------------------------------------ Layer 0
;; Attempt tracking

(defn create-attempt
  "Create an attempt record for tracking implementation/fix attempts."
  [phase & {:keys [metrics]}]
  {:attempt/id (random-uuid)
   :attempt/phase phase ; :implement or :fix
   :attempt/started-at (java.util.Date.)
   :attempt/metrics (or metrics {})})

(defn complete-attempt
  "Mark an attempt as completed with result."
  [attempt result]
  (assoc attempt
         :attempt/result result ; :ok or :err
         :attempt/completed-at (java.util.Date.)))

;------------------------------------------------------------------------------ Layer 0
;; Task Workflow State constructors

(defn create-task-state
  "Create initial task workflow state.

   Arguments:
   - task-id: UUID of the task
   - deps: Set of task IDs this task depends on

   Options:
   - :max-fix-iterations - Max fix attempts (default 5)
   - :max-ci-retries - Max CI retry attempts (default 3)
   - :state-profile - Task lifecycle profile keyword or map
   - :state-profile-provider - Provider map used to resolve keyword profiles"
  [task-id deps & {:keys [max-fix-iterations max-ci-retries
                          state-profile state-profile-provider]
                   :or {max-fix-iterations 5 max-ci-retries 3}}]
  {:task/id task-id
   :task/status :pending
   :task/state-profile (profiles/resolve-profile state-profile-provider state-profile)
   :task/deps (set deps)
   :task/pr nil
   :task/attempts []
   :task/fix-iterations 0
   :task/ci-retries 0
   :task/config {:max-fix-iterations max-fix-iterations
                 :max-ci-retries max-ci-retries}
   :task/created-at (java.util.Date.)
   :task/updated-at (java.util.Date.)})

;------------------------------------------------------------------------------ Layer 0
;; DAG Run State constructors

(defn create-run-state
  "Create initial DAG run state.

   Arguments:
   - dag-id: UUID of the DAG definition
   - tasks: Map of task-id -> TaskWorkflowState

   Options:
   - :budget - Budget constraints map with :max-tokens :max-cost-usd :max-duration-ms
   - :state-profile - Run/task lifecycle profile keyword or map
   - :state-profile-provider - Provider map used to resolve keyword profiles"
  [dag-id tasks & {:keys [budget state-profile state-profile-provider]}]
  (let [resolved-profile (profiles/resolve-profile state-profile-provider state-profile)
        normalized-tasks (into {}
                               (map (fn [[task-id task]]
                                      [task-id (assoc task :task/state-profile
                                                      (or (:task/state-profile task)
                                                          resolved-profile))]))
                               tasks)]
    {:dag/id dag-id
     :run/id (random-uuid)
     :run/status :pending
     :run/tasks normalized-tasks
     :run/completed #{}
     :run/merged #{}
     :run/failed #{}
     :run/skipped #{}
     :run/metrics {:total-tokens 0
                   :total-cost-usd 0.0
                   :total-duration-ms 0}
     :run/config (cond-> {:state-profile resolved-profile}
                   budget (assoc :budget budget))
     :run/checkpoint nil
     :run/created-at (java.util.Date.)
     :run/updated-at (java.util.Date.)}))

;------------------------------------------------------------------------------ Layer 1
;; State transition functions (pure)

(defn valid-transition?
  "Check if a state transition is valid."
  ([from-status to-status]
   (valid-transition? (profiles/default-profile) from-status to-status))
  ([profile from-status to-status]
   (contains? (get (:valid-transitions (profiles/resolve-profile profile)) from-status #{})
              to-status)))

(defn terminal?
  "Check if a status is terminal (no further transitions)."
  ([status]
   (terminal? (profiles/default-profile) status))
  ([profile status]
   (contains? (:terminal-statuses (profiles/resolve-profile profile)) status)))

(defn transition-task
  "Transition a task to a new status.
   Returns updated task state or error result."
  [task-state new-status]
  (let [profile (resolve-task-profile task-state)
        current-status (:task/status task-state)]
    (if (valid-transition? profile current-status new-status)
      (result/ok (-> task-state
                     (assoc :task/status new-status)
                     (assoc :task/updated-at (java.util.Date.))))
      (result/err :invalid-transition
                  (t :task/invalid-transition
                     {:from-status current-status
                      :to-status new-status})
                  {:from current-status
                   :to new-status
                   :valid-targets (get (:valid-transitions profile) current-status)}))))

(defn set-task-pr
  "Set PR information for a task."
  [task-state pr-info]
  (-> task-state
      (assoc :task/pr pr-info)
      (assoc :task/updated-at (java.util.Date.))))

(defn add-task-attempt
  "Add an attempt record to a task."
  [task-state attempt]
  (-> task-state
      (update :task/attempts conj attempt)
      (assoc :task/updated-at (java.util.Date.))))

(defn increment-fix-iterations
  "Increment fix iteration count."
  [task-state]
  (-> task-state
      (update :task/fix-iterations inc)
      (assoc :task/updated-at (java.util.Date.))))

(defn increment-ci-retries
  "Increment CI retry count."
  [task-state]
  (-> task-state
      (update :task/ci-retries inc)
      (assoc :task/updated-at (java.util.Date.))))

(defn max-fix-iterations-exceeded?
  "Check if max fix iterations have been exceeded."
  [task-state]
  (let [max-iter (get-in task-state [:task/config :max-fix-iterations] 5)
        current (:task/fix-iterations task-state 0)]
    (>= current max-iter)))

(defn max-ci-retries-exceeded?
  "Check if max CI retries have been exceeded."
  [task-state]
  (let [max-retries (get-in task-state [:task/config :max-ci-retries] 3)
        current (:task/ci-retries task-state 0)]
    (>= current max-retries)))

;------------------------------------------------------------------------------ Layer 1
;; Run state transitions (pure)

(defn transition-run
  "Transition a run to a new status."
  [run-state new-status]
  (-> run-state
      (assoc :run/status new-status)
      (assoc :run/updated-at (java.util.Date.))))

(defn update-run-task
  "Update a task within the run state."
  [run-state task-id update-fn]
  (-> run-state
      (update-in [:run/tasks task-id] update-fn)
      (assoc :run/updated-at (java.util.Date.))))

(defn mark-task-merged
  "Mark a task as merged in the run state."
  [run-state task-id]
  (let [profile (resolve-run-profile run-state)]
    (-> run-state
        (update-run-status-buckets profile task-id :merged)
        (assoc :run/updated-at (java.util.Date.)))))

(defn mark-task-completed
  "Mark a task as completed in the run state."
  [run-state task-id]
  (let [profile (resolve-run-profile run-state)
        success-status (or (first (:success-terminal-statuses profile)) :completed)]
    (-> run-state
        (update-run-status-buckets profile task-id success-status)
        (assoc :run/updated-at (java.util.Date.)))))

(defn mark-task-failed
  "Mark a task as failed in the run state."
  [run-state task-id]
  (-> run-state
      (update-run-status-buckets (resolve-run-profile run-state) task-id :failed)
      (assoc :run/updated-at (java.util.Date.))))

(defn mark-task-skipped
  "Mark a task as skipped in the run state."
  [run-state task-id]
  (-> run-state
      (update-run-status-buckets (resolve-run-profile run-state) task-id :skipped)
      (assoc :run/updated-at (java.util.Date.))))

(defn update-run-metrics
  "Update run metrics with new values."
  [run-state metric-updates]
  (-> run-state
      (update :run/metrics
              (fn [metrics]
                (merge-with (fn [old new]
                              (if (number? old)
                                (+ old new)
                                new))
                            metrics
                            metric-updates)))
      (assoc :run/updated-at (java.util.Date.))))

(defn set-checkpoint
  "Set checkpoint reference for the run."
  [run-state checkpoint-ref]
  (-> run-state
      (assoc :run/checkpoint {:ref checkpoint-ref
                              :timestamp (java.util.Date.)})
      (assoc :run/updated-at (java.util.Date.))))

;------------------------------------------------------------------------------ Layer 2
;; State queries

(defn ready-tasks
  "Get all tasks that are ready to start (dependencies satisfied).
   Returns set of task IDs."
  [run-state]
  (let [tasks (:run/tasks run-state)
        profile (resolve-run-profile run-state)
        completed-task-ids (->> tasks
                                (filter (fn [[_id task]]
                                          (success-terminal-status? profile (:task/status task))))
                                (map first)
                                set)]
    (->> tasks
         (filter (fn [[_id task]]
                   (and (= :pending (:task/status task))
                        (every? completed-task-ids (:task/deps task)))))
         (map first)
         set)))

(defn running-tasks
  "Get all tasks currently in progress (not pending, not terminal).
   Returns set of task IDs."
  [run-state]
  (let [tasks (:run/tasks run-state)]
    (->> tasks
         (filter (fn [[_id task]]
                   (and (not= :pending (:task/status task))
                        (not (terminal? (resolve-task-profile task)
                                        (:task/status task))))))
         (map first)
         set)))

(defn blocked-tasks
  "Get all tasks blocked on dependencies.
   Returns map of task-id -> set of blocking task IDs."
  [run-state]
  (let [tasks (:run/tasks run-state)
        profile (resolve-run-profile run-state)
        completed-task-ids (->> tasks
                                (filter (fn [[_id task]]
                                          (success-terminal-status? profile (:task/status task))))
                                (map first)
                                set)]
    (->> tasks
         (filter (fn [[_id task]]
                   (and (= :pending (:task/status task))
                        (not (every? completed-task-ids (:task/deps task))))))
         (map (fn [[id task]]
                [id (set/difference (:task/deps task) completed-task-ids)]))
         (into {}))))

(defn all-terminal?
  "Check if all tasks are in terminal states."
  [run-state]
  (let [tasks (:run/tasks run-state)]
    (every? (fn [[_id task]]
              (terminal? (resolve-task-profile task) (:task/status task)))
            tasks)))

(defn compute-run-status
  "Compute the appropriate run status based on task states."
  [run-state]
  (let [profile (resolve-run-profile run-state)
        tasks (:run/tasks run-state)
        task-count (count tasks)
        completed-count (->> tasks
                             vals
                             (filter #(success-terminal-status? profile (:task/status %)))
                             count)
        failed-count (count (:run/failed run-state))
        skipped-count (count (:run/skipped run-state))
        terminal-count (+ completed-count failed-count skipped-count)]
    (cond
      (zero? task-count) :completed
      (= terminal-count task-count)
      (cond
        (= completed-count task-count) :completed
        (pos? completed-count) :partial
        :else :failed)
      :else :running)))

;------------------------------------------------------------------------------ Layer 3
;; Atom-based state management

(defn create-run-atom
  "Create an atom containing run state for mutable operations."
  [run-state]
  (atom run-state))

(defn update-task!
  "Atomically update a task within a run atom."
  [run-atom task-id update-fn logger]
  (let [result (swap! run-atom
                      (fn [run-state]
                        (update-run-task run-state task-id update-fn)))]
    (when logger
      (log/debug logger :dag-executor :task/updated
                 {:message (t :log/task-updated)
                  :data {:task-id task-id
                         :new-status (get-in result [:run/tasks task-id :task/status])}}))
    result))

(defn- task-not-found-result
  [task-id]
  (result/err :task-not-found
              (t :task/not-found {:task-id task-id})
              {:task-id task-id}))

(defn- task-transition-result->run-state
  [current-state transition-result]
  (if (result/ok? transition-result)
    (get-in transition-result [:data :run-state])
    current-state))

(defn- successful-task-transition-data
  [run-state task-id new-status task-update-fn from-status task-state]
  (let [updated-task (task-update-fn task-state)
        updated-run  (update-run-task run-state task-id (constantly updated-task))]
    {:run-state   updated-run
     :task-state  updated-task
     :from-status from-status
     :to-status   new-status}))

(defn- transition-live-task-in-run
  [run-state task-id new-status task-update-fn live-task]
  (let [from-status (:task/status live-task)
        tr          (transition-task live-task new-status)]
    (if (result/ok? tr)
      (result/ok (successful-task-transition-data run-state
                                                  task-id
                                                  new-status
                                                  task-update-fn
                                                  from-status
                                                  (:data tr)))
      tr)))

(defn- transition-task-in-run
  "Transition the task currently present in `run-state`.

   The returned result data includes the updated run state plus transition
   metadata needed by callers for logging and event emission."
  [run-state task-id new-status task-update-fn]
  (let [live-task (get-in run-state [:run/tasks task-id])]
    (cond
      (nil? live-task) (task-not-found-result task-id)
      :else           (transition-live-task-in-run run-state
                                                   task-id
                                                   new-status
                                                   task-update-fn
                                                   live-task))))

(defn- terminal-transition-in-run
  [run-state task-id new-status mark-run-fn task-update-fn]
  (let [transition-result (transition-task-in-run run-state task-id new-status task-update-fn)]
    (if (result/ok? transition-result)
      (let [transition-data (:data transition-result)
            marked-run      (mark-run-fn (:run-state transition-data) task-id)]
        (result/ok (assoc transition-data :run-state marked-run)))
      transition-result)))

(defn- completed-status
  [run-state]
  (or (first (:success-terminal-statuses (resolve-run-profile run-state))) :completed))

(defn- completed-transition-in-run
  [run-state task-id]
  (terminal-transition-in-run run-state
                              task-id
                              (completed-status run-state)
                              mark-task-completed
                              identity))

(defn- merged-status-supported?
  [run-state]
  (contains? (:task-statuses (resolve-run-profile run-state)) :merged))

(defn- merged-transition-in-run
  [run-state task-id]
  (terminal-transition-in-run run-state task-id :merged mark-task-merged identity))

(defn- merged-or-completed-transition-in-run
  [run-state task-id]
  (if (merged-status-supported? run-state)
    (merged-transition-in-run run-state task-id)
    (completed-transition-in-run run-state task-id)))

(defn- failed-transition-in-run
  [run-state task-id error-info]
  (terminal-transition-in-run run-state
                              task-id
                              :failed
                              mark-task-failed
                              #(assoc % :task/error error-info)))

(defn- swap-task-transition!
  [run-atom transition-fn]
  (let [transition-result (volatile! nil)
        run-state         (swap! run-atom
                                 (fn [current-state]
                                   (let [result (transition-fn current-state)]
                                     (vreset! transition-result result)
                                     (task-transition-result->run-state current-state result))))]
    {:run-state run-state
     :transition-result @transition-result}))

(defn- task-not-found-result?
  [transition-result]
  (= :task-not-found (get-in transition-result [:error :code])))

(defn- log-task-transition-failure!
  [logger task-id transition-result]
  (when logger
    (if (task-not-found-result? transition-result)
      (log/warn logger :dag-executor :task/not-found
                {:message (t :log/task-not-found)
                 :data {:task-id task-id}})
      (log/warn logger :dag-executor :task/transition-failed
                {:message (t :log/task-transition-failed)
                 :data {:task-id task-id
                        :error (:error transition-result)}}))))

(defn- log-task-merged!
  [logger task-id]
  (when logger
    (log/info logger :dag-executor :task/merged
              {:message (t :log/task-merged)
               :data {:task-id task-id}})))

(defn- log-task-completed!
  [logger task-id status]
  (when logger
    (log/info logger :dag-executor :task/completed
              {:message (t :log/task-completed)
               :data {:task-id task-id
                      :status status}})))

(defn- log-merged-or-completed!
  [logger task-id status]
  (if (= :merged status)
    (log-task-merged! logger task-id)
    (log-task-completed! logger task-id status)))

(defn emit-task-state-event!
  "Emit task/state-changed event when an event-stream is configured under
   :run/config :event-stream."
  [run-state task-id from-status to-status]
  (try
    (when-let [stream (get-in run-state [:run/config :event-stream])]
      (let [workflow-id (get-in run-state [:run/config :workflow-id])
            dag-id      (:dag/id run-state)]
        (es/publish! stream
                     (es/task-state-changed stream workflow-id dag-id
                                            task-id from-status to-status))))
    (catch Exception _ nil)))

(defn transition-task!
  "Atomically transition a task to a new status within a run atom.
   Returns result with updated run state or error."
  [run-atom task-id new-status logger]
  (let [{:keys [run-state transition-result]}
        (swap-task-transition! run-atom
                               #(transition-task-in-run % task-id new-status identity))]
    (if (result/ok? transition-result)
      (let [{:keys [from-status to-status]} (:data transition-result)]
        (emit-task-state-event! run-state task-id from-status to-status)
        (when logger
          (log/info logger :dag-executor :task/transitioned
                    {:message (t :log/task-status-changed)
                     :data {:task-id task-id
                            :from from-status
                            :to to-status}}))
        (result/ok run-state))
      (do
        (log-task-transition-failure! logger task-id transition-result)
        transition-result))))

(defn mark-merged!
  "Atomically mark a task as merged in a run atom. Falls back to mark-completed!
   when the run profile has no :merged status."
  [run-atom task-id logger]
  (let [{:keys [run-state transition-result]}
        (swap-task-transition! run-atom #(merged-or-completed-transition-in-run % task-id))]
    (if (result/ok? transition-result)
      (let [status (get-in transition-result [:data :to-status])]
        (log-merged-or-completed! logger task-id status)
        (result/ok run-state))
      transition-result)))

(defn mark-completed!
  "Atomically mark a task as completed in a run atom using the run profile's
   success terminal state."
  [run-atom task-id logger]
  (let [{:keys [run-state transition-result]}
        (swap-task-transition! run-atom #(completed-transition-in-run % task-id))]
    (if (result/ok? transition-result)
      (let [success-status (get-in transition-result [:data :to-status])]
        (log-task-completed! logger task-id success-status)
        (result/ok run-state))
      transition-result)))

(defn mark-failed!
  "Atomically mark a task as failed in a run atom, recording `error-info`."
  [run-atom task-id error-info logger]
  (let [{:keys [run-state transition-result]}
        (swap-task-transition! run-atom #(failed-transition-in-run % task-id error-info))]
    (if (result/ok? transition-result)
      (do
        (when logger
          (log/warn logger :dag-executor :task/failed
                    {:message (t :log/task-failed)
                     :data {:task-id task-id :error error-info}}))
        (result/ok run-state))
      transition-result)))

(defn update-metrics!
  "Atomically update run metrics."
  [run-atom metric-updates logger]
  (swap! run-atom update-run-metrics metric-updates)
  (when logger
    (log/debug logger :dag-executor :metrics/updated
               {:message (t :log/run-metrics-updated)
                :data metric-updates}))
  @run-atom)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create task workflow states
  (def task-a-id (random-uuid))
  (def task-b-id (random-uuid))

  (def task-a (create-task-state task-a-id #{}))
  (def task-b (create-task-state task-b-id #{task-a-id}))

  ;; Check initial state
  (:task/status task-a)  ; => :pending

  ;; Transition task
  (def result (transition-task task-a :ready))
  (result/ok? result)    ; => true
  (:task/status (:data result))  ; => :ready

  ;; Invalid transition
  (def bad-result (transition-task task-a :merged))
  (result/err? bad-result)  ; => true

  ;; Create run state
  (def run-state (create-run-state
                  (random-uuid)
                  {task-a-id task-a
                   task-b-id task-b}))

  ;; Query ready tasks
  (ready-tasks run-state)  ; => #{task-a-id}

  ;; Mark task-a as merged, then check
  (def run-state' (-> run-state
                      (update-run-task task-a-id #(assoc % :task/status :merged))
                      (mark-task-merged task-a-id)))
  (ready-tasks run-state')  ; => #{task-b-id}

  ;; Atom-based operations
  (def run-atom (create-run-atom run-state))
  (transition-task! run-atom task-a-id :ready nil)
  (:task/status (get-in @run-atom [:run/tasks task-a-id]))  ; => :ready

  :leave-this-here)
