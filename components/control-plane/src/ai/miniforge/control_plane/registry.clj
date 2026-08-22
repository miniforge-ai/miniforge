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
(ns ai.miniforge.control-plane.registry
  "Atom-backed agent registry for the control plane.

   Manages the lifecycle of registered agents from any vendor.
   Each agent gets a control-plane-assigned UUID and is tracked
   with normalized state, heartbeat timestamps, and metadata."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.control-plane.messages :as messages]
   [ai.miniforge.control-plane.state-machine :as sm]))

;------------------------------------------------------------------------------ Layer 0

;; Registry creation
(defn ^{:stratum 0} create-registry
  "Create a new agent registry.

   Returns: Atom containing {:agents {} :by-vendor {} :by-external-id {}}.

   Example:
     (def reg (create-registry))"
  []
  (atom {:agents {}
         :by-vendor {}
         :by-external-id {}}))

;; Agent CRUD
(defn ^{:stratum 0} register-agent!
  "Register a new agent with the control plane.

   Arguments:
   - registry - Registry atom
   - agent-info - Map with:
     - :agent/vendor      - Keyword adapter key (e.g., :claude-code)
     - :agent/external-id - Vendor-specific identifier (string)
     - :agent/name        - Human-readable name (string)
     - :agent/capabilities - Set of capability keywords (optional)
     - :agent/heartbeat-interval-ms - Expected heartbeat interval (optional, default 30000)
     - :agent/metadata    - Vendor-specific opaque data (optional)
     - :agent/tags        - Set of user-defined grouping tags (optional)

   Returns: Complete agent record with :agent/id assigned.

   Example:
     (register-agent! reg {:agent/vendor :claude-code
                           :agent/external-id \"session-abc\"
                           :agent/name \"PR Review Agent\"})"
  [registry agent-info]
  (let [agent-id (random-uuid)
        now (java.util.Date.)
        agent-record (merge {:agent/id agent-id
                             :agent/status :unknown
                             :agent/capabilities #{}
                             :agent/heartbeat-interval-ms 30000
                             :agent/metadata {}
                             :agent/tags #{}
                             :agent/decisions []
                             :agent/registered-at now
                             :agent/last-heartbeat now}
                            agent-info
                            {:agent/id agent-id
                             :agent/registered-at now
                             :agent/last-heartbeat now})
        vendor (:agent/vendor agent-record)
        ext-id (:agent/external-id agent-record)]
    (swap! registry
           (fn [state]
             (-> state
                 (assoc-in [:agents agent-id] agent-record)
                 (update-in [:by-vendor vendor] (fnil conj #{}) agent-id)
                 (cond-> ext-id (assoc-in [:by-external-id ext-id] agent-id)))))
    agent-record))

(defn ^{:stratum 0} deregister-agent!
  "Remove an agent from the registry.

   Arguments:
   - registry - Registry atom
   - agent-id - UUID of the agent to remove

   Returns: The removed agent record, or nil if not found."
  [registry agent-id]
  (let [agent-record (get-in @registry [:agents agent-id])]
    (when agent-record
      (let [vendor (:agent/vendor agent-record)
            ext-id (:agent/external-id agent-record)]
        (swap! registry
               (fn [state]
                 (-> state
                     (update :agents dissoc agent-id)
                     (update-in [:by-vendor vendor] disj agent-id)
                     (cond-> ext-id (update :by-external-id dissoc ext-id))))))
      agent-record)))

(defn ^{:stratum 0} update-agent!
  "Update fields on an existing agent record.

   Arguments:
   - registry - Registry atom
   - agent-id - UUID of the agent
   - updates  - Map of fields to merge into the agent record

   Returns: Updated agent record, or nil if not found."
  [registry agent-id updates]
  (let [result (atom nil)]
    (swap! registry
           (fn [state]
             (if (get-in state [:agents agent-id])
               (let [updated (update-in state [:agents agent-id] merge updates)]
                 (reset! result (get-in updated [:agents agent-id]))
                 updated)
               (do (reset! result nil)
                   state))))
    @result))

(defn ^{:stratum 0} update-agent-atomic!
  "Update an agent record atomically using a pure transformation fn.

   The transform fn is called INSIDE the swap! callback, so FSM validation
   and the state write are never separated by a concurrent write. swap! retries
   the callback on CAS failure, re-running the transform each time — the final
   committed state is always consistent with the final transform result.

   transform must be pure: it takes the current agent record and returns either:
   - A new agent record — the registry is updated to this value.
   - An anomaly map (anomaly/anomaly? returns true) — the registry is NOT
     modified; the anomaly is returned to the caller.

   If the agent is absent, the registry is not modified and nil is returned.

   The volatile used to propagate the result out of swap! is set on every
   invocation of the callback; under CAS retry the volatile holds the value
   from the final (winning) invocation, which matches the committed state.

   Arguments:
   - registry  - Registry atom
   - agent-id  - UUID of the agent
   - transform - Pure fn: current-agent -> new-agent | anomaly

   Returns: new agent record, anomaly map, or nil."
  [registry agent-id transform]
  (let [result-box (volatile! nil)]
    (swap! registry
           (fn [state]
             (let [current (get-in state [:agents agent-id])]
               (if (nil? current)
                 (do (vreset! result-box nil)                 ;; clear any stale value from a prior retry
                     state)
                 (let [new-val (transform current)]
                   (vreset! result-box new-val)
                   (if (anomaly/anomaly? new-val)
                     state                                    ;; rejected: leave state untouched
                     (assoc-in state [:agents agent-id] new-val)))))))
    @result-box))

;; Query operations
(defn ^{:stratum 0} get-agent
  "Get an agent record by its control-plane UUID.

   Returns: Agent record map, or nil if not found."
  [registry agent-id]
  (get-in @registry [:agents agent-id]))

(defn ^{:stratum 0} get-agent-by-external-id
  "Get an agent record by its vendor-specific external ID.

   Returns: Agent record map, or nil if not found."
  [registry external-id]
  (when-let [agent-id (get-in @registry [:by-external-id external-id])]
    (get-in @registry [:agents agent-id])))

(defn ^{:stratum 0} list-agents
  "List all registered agents.

   Options:
   - :vendor - Filter by vendor keyword
   - :status - Filter by status keyword
   - :tag    - Filter by tag keyword

   Returns: Seq of agent record maps."
  [registry & [opts]]
  (let [agents (vals (:agents @registry))
        {:keys [vendor status tag]} opts]
    (cond->> agents
      vendor (filter #(= vendor (:agent/vendor %)))
      status (filter #(= status (:agent/status %)))
      tag    (filter #(contains? (:agent/tags %) tag)))))

(defn ^{:stratum 0} agents-by-status
  "Group agents by their current status.

   Returns: Map of status keyword → seq of agent records."
  [registry]
  (group-by :agent/status (vals (:agents @registry))))

;; Heartbeat updates
(defn- ^{:stratum 0} agent-not-found-anomaly
  [agent-id]
  (anomaly/anomaly :not-found
                   (messages/t :registry/agent-not-found)
                   {:agent/id agent-id}))

(defn- ^{:stratum 0} apply-heartbeat-fields
  "Build an updated agent record from a heartbeat, applying the FSM check
   for status changes inside the swap! callback.

   Called as the transform fn for update-agent-atomic! — must be pure."
  [profile heartbeat now current]
  (let [base (cond-> (assoc current :agent/last-heartbeat now)
               (:task heartbeat)    (assoc :agent/task (:task heartbeat))
               (:metrics heartbeat) (assoc :agent/metrics (:metrics heartbeat)))
        new-status (:status heartbeat)]
    (if (nil? new-status)
      base
      (let [current-status (:agent/status current)]
        (if (or (nil? current-status)
                (sm/valid-transition? profile current-status new-status))
          (assoc base :agent/status new-status)
          base)))))

(defn- ^{:stratum 1} apply-fsm-transition
  "Build an updated agent record after validating a status transition,
   or return an anomaly if the transition is invalid.

   Called as the transform fn for update-agent-atomic! — must be pure.
   Stratum 1: depends on sm/validate-transition-result (stratum 1)."
  [profile new-status current]
  (let [current-status (:agent/status current)
        validation-anomaly (sm/validate-transition-result profile current-status new-status)]
    (if validation-anomaly
      validation-anomaly
      (assoc current :agent/status new-status))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} count-agents
  "Count agents, optionally filtered by status.

   Returns: Integer count."
  [registry & [status]]
  (if status
    (count (list-agents registry {:status status}))
    (count (:agents @registry))))

(defn ^{:stratum 1} record-heartbeat!
  "Record a heartbeat from an agent, updating timestamp and optional fields.

   The FSM validity check for status changes runs inside the swap! callback
   so the read-validate-write sequence is atomic and safe under concurrent
   access (e.g., a parallel heartbeat poll loop and external transition calls).

   Arguments:
   - registry - Registry atom
   - agent-id - UUID of the agent
   - heartbeat - Map with optional keys:
     - :status  - New normalized status (only applied if FSM allows)
     - :task    - Current task description
     - :metrics - Cost/token metrics map

   Returns: Updated agent record, or nil if agent not found."
  [registry agent-id heartbeat]
  (let [now (java.util.Date.)
        profile (sm/get-profile)]
    (update-agent-atomic! registry agent-id
                          (partial apply-heartbeat-fields profile heartbeat now))))

(defn ^{:stratum 1} transition-agent!
  "Transition an agent to a new status with FSM validation.

   The FSM check runs inside the swap! callback so the read-validate-write
   sequence is atomic and safe under concurrent access.

   Arguments:
   - registry   - Registry atom
   - agent-id   - UUID of the agent
   - new-status - Target status keyword

   Returns: Updated agent record, or an anomaly map with:
   - :anomaly/type :not-found    — agent absent from registry
   - :anomaly/type :invalid-input — FSM transition rejected"
  [registry agent-id new-status]
  (let [profile (sm/get-profile)
        result (update-agent-atomic! registry agent-id
                                     (partial apply-fsm-transition profile new-status))]
    (if (nil? result)
      (agent-not-found-anomaly agent-id)
      result)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def reg (create-registry))
  (def agent (register-agent! reg {:agent/vendor :claude-code
                                    :agent/external-id "session-123"
                                    :agent/name "Test Agent"}))
  (:agent/id agent)
  (get-agent reg (:agent/id agent))
  (list-agents reg)
  (record-heartbeat! reg (:agent/id agent) {:status :running :task "Reviewing PR"})
  (transition-agent! reg (:agent/id agent) :blocked)
  (deregister-agent! reg (:agent/id agent))
  :end)
