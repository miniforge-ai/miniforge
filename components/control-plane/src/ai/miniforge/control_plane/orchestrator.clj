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

(ns ai.miniforge.control-plane.orchestrator
  "Orchestrator loop for the control plane.

   Coordinates adapters, the agent registry, decision queue,
   and heartbeat watchdog into a unified runtime. Periodically
   runs adapter discovery and status polling.

   Layer 0: Orchestrator creation
   Layer 1: Discovery and polling loop
   Layer 2: Full lifecycle management"
  (:require
   [ai.miniforge.control-plane.registry :as registry]
   [ai.miniforge.control-plane.decision-queue :as dq]
   [ai.miniforge.control-plane.heartbeat :as heartbeat]
   [ai.miniforge.control-plane-adapter.protocol :as adapter]
   [ai.miniforge.event-stream.interface.events :as events]
   [ai.miniforge.event-stream.interface.stream :as stream]))

;------------------------------------------------------------------------------ Layer 0
;; Orchestrator state

(def ^:const default-discovery-interval-ms
  "How often to run adapter discovery."
  30000)

(def ^:const default-poll-interval-ms
  "How often to poll agent status."
  10000)

(defn- publish-event!
  [event-stream event]
  (when (and event-stream event)
    (stream/publish! event-stream event)))

(defn- normalize-status-update
  [status-update]
  (cond-> {}
    (or (:status status-update) (:agent/status status-update))
    (assoc :status (or (:status status-update) (:agent/status status-update)))

    (or (:task status-update) (:agent/task status-update))
    (assoc :task (or (:task status-update) (:agent/task status-update)))

    (or (:metrics status-update) (:agent/metrics status-update))
    (assoc :metrics (or (:metrics status-update) (:agent/metrics status-update)))))

(defn- normalize-decision-opts
  [opts]
  (cond-> {}
    (or (:type opts) (:decision/type opts))
    (assoc :type (or (:type opts) (:decision/type opts)))

    (or (:priority opts) (:decision/priority opts))
    (assoc :priority (or (:priority opts) (:decision/priority opts)))

    (or (:context opts) (:decision/context opts))
    (assoc :context (or (:context opts) (:decision/context opts)))

    (or (:options opts) (:decision/options opts))
    (assoc :options (or (:options opts) (:decision/options opts)))

    (or (:deadline opts) (:decision/deadline opts))
    (assoc :deadline (or (:deadline opts) (:decision/deadline opts)))

    (or (:tags opts) (:decision/tags opts))
    (assoc :tags (or (:tags opts) (:decision/tags opts)))

    (or (:agent-confidence opts) (:decision/agent-confidence opts))
    (assoc :agent-confidence (or (:agent-confidence opts) (:decision/agent-confidence opts)))))

(defn create-orchestrator
  "Create a control plane orchestrator.

   Arguments:
   - opts - Map with:
     - :registry         - Agent registry atom (or creates new)
     - :decision-manager - Decision manager atom (or creates new)
     - :adapters         - Seq of ControlPlaneAdapter instances
     - :discovery-interval-ms - Discovery loop interval (default 30000)
     - :poll-interval-ms      - Status poll interval (default 10000)
     - :on-agent-discovered   - Callback (fn [agent-record])
     - :on-agent-unreachable  - Callback (fn [agent-id])
     - :on-decision-created   - Callback (fn [decision])

   Returns: Orchestrator state map (not yet started).

   Example:
     (def orch (create-orchestrator {:adapters [claude-adapter]}))"
  [opts]
  {:registry (or (:registry opts) (registry/create-registry))
   :decision-manager (or (:decision-manager opts) (dq/create-decision-manager))
   :adapters (vec (or (:adapters opts) []))
   :event-stream (:event-stream opts)
   :workflow-id (or (:workflow-id opts) (random-uuid))
   :discovery-interval-ms (get opts :discovery-interval-ms default-discovery-interval-ms)
   :poll-interval-ms (get opts :poll-interval-ms default-poll-interval-ms)
   :on-agent-discovered (get opts :on-agent-discovered (fn [_]))
   :on-agent-unreachable (get opts :on-agent-unreachable (fn [_]))
   :on-decision-created (get opts :on-decision-created (fn [_]))
   :running (atom false)
   :futures (atom [])})

;------------------------------------------------------------------------------ Layer 1
;; Discovery and polling

(defn- run-discovery-pass
  "Run one discovery pass across all adapters.
   Registers any newly discovered agents."
  [{:keys [registry adapters on-agent-discovered event-stream workflow-id]}]
  (doseq [adapter adapters]
    (try
      (let [discovered (adapter/discover-agents
                        adapter {})]
        (doseq [agent-info discovered]
          (let [ext-id (:agent/external-id agent-info)
                existing (registry/get-agent-by-external-id registry ext-id)]
            (when-not existing
              (let [registered (registry/register-agent! registry agent-info)]
                (on-agent-discovered registered)
                (when (and event-stream workflow-id)
                  (publish-event! event-stream
                                  (events/cp-agent-registered
                                   event-stream
                                   workflow-id
                                   (:agent/id registered)
                                   (:agent/vendor registered)
                                   {:name (:agent/name registered)}))))))))
      (catch Exception _e nil))))

(defn- run-poll-pass
  "Run one status poll pass for all non-terminal agents."
  [{:keys [registry adapters event-stream workflow-id]}]
  (let [agents (registry/list-agents registry)
        by-vendor (group-by :agent/vendor agents)
        adapter-map (into {} (map (fn [a]
                                    [(adapter/adapter-id a) a])
                                  adapters))]
    (doseq [[vendor agents-for-vendor] by-vendor]
      (when-let [adapter (get adapter-map vendor)]
        (doseq [agent-record agents-for-vendor]
          (when-not (#{:completed :failed :terminated} (:agent/status agent-record))
            (try
              (when-let [status-update (adapter/poll-agent-status
                                        adapter agent-record)]
                (let [old-status (:agent/status agent-record)
                      normalized-update (normalize-status-update status-update)
                      updated-agent (registry/record-heartbeat! registry
                                                               (:agent/id agent-record)
                                                               normalized-update)
                      new-status (:agent/status updated-agent)]
                  (when (and workflow-id
                             event-stream
                             new-status
                             (not= new-status :initializing)
                             (not= old-status new-status))
                    (publish-event! event-stream
                                    (events/cp-agent-state-changed
                                     event-stream
                                     workflow-id
                                     (:agent/id agent-record)
                                     old-status
                                     new-status)))))
              (catch Exception _e nil))))))))

;------------------------------------------------------------------------------ Layer 2
;; Full lifecycle

(defn start!
  "Start the orchestrator discovery and polling loops.

   Arguments:
   - orchestrator - Orchestrator map from create-orchestrator

   Returns: Updated orchestrator with running futures."
  [orchestrator]
  (let [{:keys [running futures discovery-interval-ms poll-interval-ms
                registry on-agent-unreachable]} orchestrator]
    (reset! running true)

    ;; Discovery loop
    (swap! futures conj
           (future
             (while @running
               (try
                 (run-discovery-pass orchestrator)
                 (catch Exception _e nil))
               (Thread/sleep discovery-interval-ms))))

    ;; Poll loop
    (swap! futures conj
           (future
             (while @running
               (try
                 (run-poll-pass orchestrator)
                 (catch Exception _e nil))
               (Thread/sleep poll-interval-ms))))

    ;; Heartbeat watchdog
    (let [wd (heartbeat/start-watchdog registry
               {:check-interval-ms poll-interval-ms
                :on-unreachable on-agent-unreachable})]
      (swap! futures conj (:future wd))
      (assoc orchestrator :watchdog wd))))

(defn stop!
  "Stop the orchestrator and all background loops.

   Arguments:
   - orchestrator - Running orchestrator."
  [orchestrator]
  (reset! (:running orchestrator) false)
  (doseq [f @(:futures orchestrator)]
    (future-cancel f))
  (when-let [wd (:watchdog orchestrator)]
    (heartbeat/stop-watchdog wd))
  (reset! (:futures orchestrator) [])
  orchestrator)

(defn submit-decision-from-agent!
  "Submit a decision from an agent to the queue.
   Transitions the agent to :blocked.

   Arguments:
   - orchestrator  - Orchestrator map
   - agent-id      - UUID of the agent
   - summary       - Decision summary string
   - opts          - Decision options (see decision-queue/create-decision)

   Returns: The submitted decision."
  [orchestrator agent-id summary & [opts]]
  (let [{:keys [registry decision-manager on-decision-created
                event-stream workflow-id]} orchestrator
        normalized-opts (normalize-decision-opts (or opts {}))
        decision (dq/create-decision agent-id summary normalized-opts)]
    (dq/submit-decision! decision-manager decision)
    ;; Try to transition agent to blocked (may fail if already terminal)
    (try
      (registry/transition-agent! registry agent-id :blocked)
      (catch Exception _))
    (on-decision-created decision)
    (when (and event-stream workflow-id)
      (publish-event! event-stream
                      (events/cp-decision-created
                       event-stream
                       workflow-id
                       agent-id
                       (:decision/id decision)
                       summary
                       (:decision/priority decision))))
    decision))

(defn resolve-and-deliver!
  "Resolve a decision and deliver the result back to the agent.

   Arguments:
   - orchestrator - Orchestrator map
   - decision-id  - UUID of the decision
   - resolution   - The human's choice
   - comment      - Optional comment

   Returns: {:resolved decision :delivered? bool}"
  [orchestrator decision-id resolution & [comment]]
  (let [{:keys [registry decision-manager adapters event-stream workflow-id]} orchestrator
        resolved (dq/resolve-decision! decision-manager decision-id resolution comment)]
    (when resolved
      (let [agent-id (:decision/agent-id resolved)
            agent-record (registry/get-agent registry agent-id)
            adapter-map (into {} (map (fn [a]
                                        [(adapter/adapter-id a) a])
                                      adapters))
            adapter (get adapter-map (:agent/vendor agent-record))
            delivery (when adapter
                       (adapter/deliver-decision
                        adapter agent-record resolved))]
        ;; Try to transition agent back to running
        (try
          (registry/transition-agent! registry agent-id :running)
          (catch Exception _))
        (when (and event-stream workflow-id)
          (publish-event! event-stream
                          (events/cp-decision-resolved
                           event-stream
                           workflow-id
                           decision-id
                           resolution)))
        {:resolved resolved
         :delivered? (get delivery :delivered? false)}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example usage:
  ;; (def orch (create-orchestrator {:adapters [(claude-code/create-adapter)]}))
  ;; (start! orch)
  ;; ... agents discovered automatically ...
  ;; (submit-decision-from-agent! orch agent-id "Merge PR?")
  ;; (resolve-and-deliver! orch decision-id "yes")
  ;; (stop! orch)
  :end)
