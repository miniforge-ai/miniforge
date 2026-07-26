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
(ns ai.miniforge.control-plane.interface
  "Public API for the control plane component.

   The control plane is a unified management surface for AI agents
   across vendors. It normalizes agent state, surfaces decision
   requests as a priority queue, and delivers human decisions back
   to agents.

   A flat re-export facade over state machine, agent registry,
   decision queue, heartbeat watchdog, and orchestrator — each def
   just aliases a var from its own component namespace, so there
   are no same-file dependencies among them."
  (:require
   [ai.miniforge.control-plane.messages :as messages]
   [ai.miniforge.control-plane.state-machine :as sm]
   [ai.miniforge.control-plane.registry :as registry]
   [ai.miniforge.control-plane.decision-queue :as dq]
   [ai.miniforge.control-plane.heartbeat :as heartbeat]
   [ai.miniforge.control-plane.orchestrator :as orch]))

;------------------------------------------------------------------------------ Layer 0

;; Messages
(def ^{:stratum 0} t
  "Look up a control-plane message by key, with optional param substitution."
  messages/t)

;; State machine
(def ^{:stratum 0} load-profile
  "Load the control-plane state profile from classpath.
   Returns: State profile map."
  sm/load-profile)

(def ^{:stratum 0} get-profile
  "Get the cached control-plane state profile."
  sm/get-profile)

(def ^{:stratum 0} valid-transition?
  "Check if a state transition is valid.
   (valid-transition? profile :running :blocked) ;=> true"
  sm/valid-transition?)

(def ^{:stratum 0} validate-transition-result
  "Validate a state transition. Returns nil on success or an anomaly on invalid."
  sm/validate-transition-result)

(def ^{:stratum 0} terminal?
  "Check if a status is terminal.
   (terminal? profile :completed) ;=> true"
  sm/terminal?)

(def ^{:stratum 0} event->transition
  "Map an event type to its configured transition."
  sm/event->transition)

;; Agent registry
(def ^{:stratum 0} create-registry
  "Create a new agent registry.
   Returns: Atom containing agent store."
  registry/create-registry)

(def ^{:stratum 0} register-agent!
  "Register a new agent with the control plane.
   Returns: Complete agent record with :agent/id assigned."
  registry/register-agent!)

(def ^{:stratum 0} deregister-agent!
  "Remove an agent from the registry.
   Returns: The removed agent record."
  registry/deregister-agent!)

(def ^{:stratum 0} update-agent!
  "Update fields on an existing agent record.
   Returns: Updated agent record."
  registry/update-agent!)

(def ^{:stratum 0} get-agent
  "Get an agent record by UUID."
  registry/get-agent)

(def ^{:stratum 0} get-agent-by-external-id
  "Get an agent by vendor-specific external ID."
  registry/get-agent-by-external-id)

(def ^{:stratum 0} list-agents
  "List agents, optionally filtered by :vendor, :status, or :tag."
  registry/list-agents)

(def ^{:stratum 0} count-agents
  "Count agents, optionally filtered by status."
  registry/count-agents)

(def ^{:stratum 0} agents-by-status
  "Group agents by their current status."
  registry/agents-by-status)

(def ^{:stratum 0} record-heartbeat!
  "Record a heartbeat, updating timestamp and optional fields."
  registry/record-heartbeat!)

(def ^{:stratum 0} transition-agent!
  "Transition an agent to a new status with validation.
   Returns an updated agent record, or an anomaly map with
   `:anomaly/type :not-found` when the agent ID is absent."
  registry/transition-agent!)

;; Decision queue
(def ^{:stratum 0} create-decision
  "Create a new decision request.
   (create-decision agent-id \"Merge PR?\" {:priority :high})"
  dq/create-decision)

(def ^{:stratum 0} create-decision-manager
  "Create a new decision manager (atom-backed store)."
  dq/create-decision-manager)

(def ^{:stratum 0} submit-decision!
  "Submit a new decision to the queue."
  dq/submit-decision!)

(def ^{:stratum 0} resolve-decision!
  "Resolve a pending decision with the human's choice."
  dq/resolve-decision!)

(def ^{:stratum 0} cancel-decision!
  "Cancel a pending decision."
  dq/cancel-decision!)

(def ^{:stratum 0} get-decision
  "Get a decision by ID."
  dq/get-decision)

(def ^{:stratum 0} pending-decisions
  "Get all pending decisions, sorted by priority."
  dq/pending-decisions)

(def ^{:stratum 0} decisions-for-agent
  "Get all decisions for a specific agent."
  dq/decisions-for-agent)

(def ^{:stratum 0} count-pending
  "Count pending decisions."
  dq/count-pending)

(def ^{:stratum 0} expire-stale-decisions!
  "Expire all decisions past their deadline."
  dq/expire-stale-decisions!)

;; Heartbeat watchdog
(def ^{:stratum 0} start-watchdog
  "Start the heartbeat watchdog background thread.
   Returns: Map with :future and :stop-fn."
  heartbeat/start-watchdog)

(def ^{:stratum 0} stop-watchdog
  "Stop a running heartbeat watchdog."
  heartbeat/stop-watchdog)

(def ^{:stratum 0} check-stale-agents
  "Check all agents for missed heartbeats (single pass)."
  heartbeat/check-stale-agents)

;; Orchestrator
(def ^{:stratum 0} create-orchestrator
  "Create a control plane orchestrator.
   Coordinates adapters, registry, decisions, and heartbeat monitoring.
   (create-orchestrator {:adapters [claude-adapter]})"
  orch/create-orchestrator)

(def ^{:stratum 0} start!
  "Start the orchestrator discovery and polling loops."
  orch/start!)

(def ^{:stratum 0} stop!
  "Stop the orchestrator and all background loops."
  orch/stop!)

(def ^{:stratum 0} submit-decision-from-agent!
  "Submit a decision from an agent and transition it to :blocked."
  orch/submit-decision-from-agent!)

(def ^{:stratum 0} resolve-and-deliver!
  "Resolve a decision and deliver the result back to the agent."
  orch/resolve-and-deliver!)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Full usage example
  (def reg (create-registry))
  (def mgr (create-decision-manager))

  ;; Register an agent
  (def agent (register-agent! reg {:agent/vendor :claude-code
                                    :agent/external-id "session-123"
                                    :agent/name "PR Review Agent"}))

  ;; Agent sends heartbeat with status
  (record-heartbeat! reg (:agent/id agent) {:status :running :task "Reviewing PR #42"})

  ;; Agent needs a decision
  (def d (create-decision (:agent/id agent)
                          "Should I merge PR #42?"
                          {:type :approval :priority :high
                           :options ["yes" "no" "defer"]}))
  (submit-decision! mgr d)
  (transition-agent! reg (:agent/id agent) :blocked)

  ;; Human sees and resolves
  (pending-decisions mgr #{(:agent/id agent)})
  (resolve-decision! mgr (:decision/id d) "yes" "Ship it")
  (transition-agent! reg (:agent/id agent) :running)

  ;; Start watchdog
  (def wd (start-watchdog reg {:check-interval-ms 5000}))
  (stop-watchdog wd)

  :end)
