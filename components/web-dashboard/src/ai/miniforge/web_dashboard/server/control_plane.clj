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

(ns ai.miniforge.web-dashboard.server.control-plane
  "HTTP handlers for the control plane API.

   Provides REST endpoints for agent registration, heartbeat,
   status queries, and decision resolution.

   Layer 0: JSON helpers
   Layer 1: Agent API handlers
   Layer 2: Decision API handlers
   Layer 3: Command handlers"
  (:require
   [cheshire.core :as json]
   [ai.miniforge.web-dashboard.server.responses :as responses]
   [ai.miniforge.control-plane.interface :as cp]))

;------------------------------------------------------------------------------ Layer 0
;; JSON helpers

(defn- parse-json-body
  "Parse JSON request body. Returns parsed map or nil on error."
  [body-str]
  (try
    (json/parse-string body-str true)
    (catch Exception _ nil)))

(defn- agent->json
  "Convert agent record to JSON-safe map (stringify UUIDs and dates)."
  [agent-record]
  (when agent-record
    (-> agent-record
        (update :agent/id str)
        (update :agent/registered-at str)
        (update :agent/last-heartbeat str)
        (update :agent/capabilities #(mapv name %))
        (update :agent/tags #(mapv name %))
        (update :agent/decisions #(mapv str %)))))

(defn- decision->json
  "Convert decision record to JSON-safe map."
  [decision]
  (when decision
    (-> decision
        (update :decision/id str)
        (update :decision/agent-id str)
        (update :decision/created-at str)
        (cond->
          (:decision/resolved-at decision) (update :decision/resolved-at str)
          (:decision/deadline decision)    (update :decision/deadline str)))))

(defn- error-response [status message]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:error message})})

;------------------------------------------------------------------------------ Layer 1
;; Agent API handlers

(defn handle-register-agent
  "POST /api/control-plane/agents/register
   Register a new agent with the control plane."
  [state body-str]
  (if-let [body (parse-json-body body-str)]
    (let [registry (get-in @state [:control-plane :registry])
          agent-info {:agent/vendor (keyword (:vendor body))
                      :agent/external-id (:external_id body)
                      :agent/name (:name body)
                      :agent/capabilities (set (map keyword (or (:capabilities body) [])))
                      :agent/heartbeat-interval-ms (or (:heartbeat_interval_ms body) 30000)
                      :agent/metadata (or (:metadata body) {})}
          ;; Check for existing agent with same external ID
          existing (when (:agent/external-id agent-info)
                     (cp/get-agent-by-external-id registry (:agent/external-id agent-info)))
          agent (or existing (cp/register-agent! registry agent-info))]
      (responses/json-response
       {:agent_id (str (:agent/id agent))
        :heartbeat_endpoint (str "/api/control-plane/agents/" (:agent/id agent) "/heartbeat")
        :decision_poll_endpoint (str "/api/control-plane/agents/" (:agent/id agent) "/decisions")
        :already_registered (some? existing)}))
    (error-response 400 "Invalid JSON body")))

(defn handle-agent-heartbeat
  "POST /api/control-plane/agents/:id/heartbeat
   Record a heartbeat and optionally submit decisions."
  [state agent-id-str body-str]
  (if-let [body (parse-json-body body-str)]
    (let [registry (get-in @state [:control-plane :registry])
          decision-manager (get-in @state [:control-plane :decision-manager])
          agent-id (try (java.util.UUID/fromString agent-id-str) (catch Exception _ nil))]
      (if-not agent-id
        (error-response 400 "Invalid agent ID")
        (if-not (cp/get-agent registry agent-id)
          (error-response 404 "Agent not found")
          (let [;; Update heartbeat
                heartbeat {:status (when (:status body) (keyword (:status body)))
                           :task (:task body)
                           :metrics (:metrics body)}
                _ (cp/record-heartbeat! registry agent-id heartbeat)
                ;; Process any new decisions
                new-decisions (or (:decisions_needed body) [])
                submitted (mapv (fn [d]
                                  (let [decision (cp/create-decision
                                                  agent-id
                                                  (:summary d)
                                                  {:type (keyword (or (:type d) "choice"))
                                                   :priority (keyword (or (:priority d) "medium"))
                                                   :context (:context d)
                                                   :options (:options d)})]
                                    (cp/submit-decision! decision-manager decision)
                                    ;; Transition to blocked if decision submitted
                                    (try (cp/transition-agent! registry agent-id :blocked)
                                         (catch Exception _))
                                    (str (:decision/id decision))))
                                new-decisions)
                ;; Return any resolved decisions
                agent-decisions (cp/decisions-for-agent decision-manager agent-id
                                                        {:status :resolved})
                resolved (mapv (fn [d]
                                 {:decision_id (str (:decision/id d))
                                  :choice (:decision/resolution d)
                                  :comment (:decision/comment d)})
                               agent-decisions)]
            (responses/json-response
             {:ack true
              :decisions_submitted submitted
              :decisions_resolved resolved})))))
    (error-response 400 "Invalid JSON body")))

(defn handle-list-agents
  "GET /api/control-plane/agents
   List all registered agents, optionally filtered."
  [state params]
  (let [registry (get-in @state [:control-plane :registry])
        opts (cond-> {}
               (:vendor params) (assoc :vendor (keyword (:vendor params)))
               (:status params) (assoc :status (keyword (:status params))))
        agents (cp/list-agents registry opts)]
    (responses/json-response
     {:agents (mapv agent->json agents)
      :total (count agents)})))

(defn handle-get-agent
  "GET /api/control-plane/agents/:id
   Get a single agent's details."
  [state agent-id-str]
  (let [registry (get-in @state [:control-plane :registry])
        agent-id (try (java.util.UUID/fromString agent-id-str) (catch Exception _ nil))]
    (if-not agent-id
      (error-response 400 "Invalid agent ID")
      (if-let [agent (cp/get-agent registry agent-id)]
        (let [decision-manager (get-in @state [:control-plane :decision-manager])
              pending (cp/decisions-for-agent decision-manager agent-id {:status :pending})]
          (responses/json-response
           (assoc (agent->json agent)
                  :pending_decisions (mapv decision->json pending))))
        (error-response 404 "Agent not found")))))

(defn handle-delete-agent
  "DELETE /api/control-plane/agents/:id
   Deregister an agent."
  [state agent-id-str]
  (let [registry (get-in @state [:control-plane :registry])
        agent-id (try (java.util.UUID/fromString agent-id-str) (catch Exception _ nil))]
    (if-not agent-id
      (error-response 400 "Invalid agent ID")
      (if-let [removed (cp/deregister-agent! registry agent-id)]
        (responses/json-response {:removed (str (:agent/id removed))})
        (error-response 404 "Agent not found")))))

;------------------------------------------------------------------------------ Layer 2
;; Decision API handlers

(defn handle-list-decisions
  "GET /api/control-plane/decisions
   List pending decisions, sorted by priority."
  [state _params]
  (let [registry (get-in @state [:control-plane :registry])
        decision-manager (get-in @state [:control-plane :decision-manager])
        blocked-ids (->> (cp/list-agents registry {:status :blocked})
                         (map :agent/id)
                         set)
        pending (cp/pending-decisions decision-manager blocked-ids)]
    (responses/json-response
     {:decisions (mapv decision->json pending)
      :total (count pending)})))

(defn handle-resolve-decision
  "POST /api/control-plane/decisions/:id/resolve
   Resolve a pending decision."
  [state decision-id-str body-str]
  (if-let [body (parse-json-body body-str)]
    (let [decision-manager (get-in @state [:control-plane :decision-manager])
          registry (get-in @state [:control-plane :registry])
          decision-id (try (java.util.UUID/fromString decision-id-str) (catch Exception _ nil))]
      (if-not decision-id
        (error-response 400 "Invalid decision ID")
        (if-let [resolved (cp/resolve-decision! decision-manager decision-id
                                                 (:resolution body)
                                                 (:comment body))]
          (do
            ;; Try to unblock the agent
            (try
              (cp/transition-agent! registry (:decision/agent-id resolved) :running)
              (catch Exception _))
            (responses/json-response (decision->json resolved)))
          (error-response 404 "Decision not found or already resolved"))))
    (error-response 400 "Invalid JSON body")))

;------------------------------------------------------------------------------ Layer 3
;; Command handlers

(defn handle-agent-command
  "POST /api/control-plane/agents/:id/command
   Send a control command to an agent."
  [state agent-id-str body-str]
  (if-let [body (parse-json-body body-str)]
    (let [registry (get-in @state [:control-plane :registry])
          agent-id (try (java.util.UUID/fromString agent-id-str) (catch Exception _ nil))
          command (keyword (:command body))]
      (if-not agent-id
        (error-response 400 "Invalid agent ID")
        (if-not (cp/get-agent registry agent-id)
          (error-response 404 "Agent not found")
          (do
            ;; Apply state transition based on command
            (try
              (case command
                :pause     (cp/transition-agent! registry agent-id :paused)
                :resume    (cp/transition-agent! registry agent-id :running)
                :terminate (cp/transition-agent! registry agent-id :terminated)
                nil)
              (catch Exception _))
            (responses/json-response
             {:success true
              :agent (agent->json (cp/get-agent registry agent-id))})))))
    (error-response 400 "Invalid JSON body")))

(defn handle-cp-summary
  "GET /api/control-plane/summary
   Get control plane overview stats."
  [state]
  (let [registry (get-in @state [:control-plane :registry])
        decision-manager (get-in @state [:control-plane :decision-manager])
        agents (cp/list-agents registry)
        by-status (cp/agents-by-status registry)]
    (responses/json-response
     {:total_agents (count agents)
      :agents_by_status (into {} (map (fn [[k v]] [(name k) (count v)]) by-status))
      :pending_decisions (cp/count-pending decision-manager)
      :agents_needing_attention (count (filter #(#{:blocked :unreachable} (:agent/status %)) agents))})))
