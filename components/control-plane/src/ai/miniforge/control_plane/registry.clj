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
   with normalized state, heartbeat timestamps, and metadata.

   Layer 0: Registry creation
   Layer 1: Agent CRUD
   Layer 2: Query operations
   Layer 3: Heartbeat updates"
  (:require
   [ai.miniforge.control-plane.messages :as messages]
   [ai.miniforge.control-plane.state-machine :as sm]))

;------------------------------------------------------------------------------ Layer 0
;; Registry creation

(defn create-registry
  "Create a new agent registry.

   Returns: Atom containing {:agents {} :by-vendor {} :by-external-id {}}.

   Example:
     (def reg (create-registry))"
  []
  (atom {:agents {}
         :by-vendor {}
         :by-external-id {}}))

;------------------------------------------------------------------------------ Layer 1
;; Agent CRUD

(defn register-agent!
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

(defn deregister-agent!
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

(defn update-agent!
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

;------------------------------------------------------------------------------ Layer 2
;; Query operations

(defn get-agent
  "Get an agent record by its control-plane UUID.

   Returns: Agent record map, or nil if not found."
  [registry agent-id]
  (get-in @registry [:agents agent-id]))

(defn get-agent-by-external-id
  "Get an agent record by its vendor-specific external ID.

   Returns: Agent record map, or nil if not found."
  [registry external-id]
  (when-let [agent-id (get-in @registry [:by-external-id external-id])]
    (get-in @registry [:agents agent-id])))

(defn list-agents
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

(defn count-agents
  "Count agents, optionally filtered by status.

   Returns: Integer count."
  [registry & [status]]
  (if status
    (count (list-agents registry {:status status}))
    (count (:agents @registry))))

(defn agents-by-status
  "Group agents by their current status.

   Returns: Map of status keyword → seq of agent records."
  [registry]
  (group-by :agent/status (vals (:agents @registry))))

;------------------------------------------------------------------------------ Layer 3
;; Heartbeat updates

(defn record-heartbeat!
  "Record a heartbeat from an agent, updating timestamp and optional fields.

   Arguments:
   - registry - Registry atom
   - agent-id - UUID of the agent
   - heartbeat - Map with optional keys:
     - :status  - New normalized status
     - :task    - Current task description
     - :metrics - Cost/token metrics map

   Returns: Updated agent record."
  [registry agent-id heartbeat]
  (let [now (java.util.Date.)
        profile (sm/get-profile)
        updates (cond-> {:agent/last-heartbeat now}
                  (:task heartbeat)    (assoc :agent/task (:task heartbeat))
                  (:metrics heartbeat) (assoc :agent/metrics (:metrics heartbeat)))
        ;; Only apply status change if it's a valid transition
        updates (if-let [new-status (:status heartbeat)]
                  (let [current (get-in @registry [:agents agent-id :agent/status])]
                    (if (or (nil? current)
                            (sm/valid-transition? profile current new-status))
                      (assoc updates :agent/status new-status)
                      updates))
                  updates)]
    (update-agent! registry agent-id updates)))

(defn transition-agent!
  "Transition an agent to a new status with validation.

   Arguments:
   - registry - Registry atom
   - agent-id - UUID of the agent
   - new-status - Target status keyword

   Returns: Updated agent record.
   Throws: ExceptionInfo if transition is invalid."
  [registry agent-id new-status]
  (let [profile (sm/get-profile)
        current-status (get-in @registry [:agents agent-id :agent/status])]
    (when-not current-status
      (throw (ex-info (messages/t :registry/agent-not-found) {:agent/id agent-id})))
    (sm/validate-transition profile current-status new-status)
    (update-agent! registry agent-id {:agent/status new-status})))

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
