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

   Layer 0: State machine (profile loading, transition validation)
   Layer 1: Agent registry (CRUD, heartbeat, state transitions)
   Layer 2: Decision queue (submit, resolve, priority sorting)
   Layer 3: Heartbeat watchdog (background liveness monitoring)
   Layer 4: Orchestrator (adapter coordination, full lifecycle)"
  (:require
   [ai.miniforge.control-plane.messages :as messages]
   [ai.miniforge.control-plane.state-machine :as sm]
   [ai.miniforge.control-plane.registry :as registry]
   [ai.miniforge.control-plane.decision-queue :as dq]
   [ai.miniforge.control-plane.heartbeat :as heartbeat]
   [ai.miniforge.control-plane.orchestrator :as orch]))

;------------------------------------------------------------------------------ Layer 0
;; Messages

(def t
  "Look up a control-plane message by key, with optional param substitution."
  messages/t)

;------------------------------------------------------------------------------ Layer 0
;; State machine

(def load-profile
  "Load the control-plane state profile from classpath.
   Returns: State profile map."
  sm/load-profile)

(def get-profile
  "Get the cached control-plane state profile."
  sm/get-profile)

(def valid-transition?
  "Check if a state transition is valid.
   (valid-transition? profile :running :blocked) ;=> true"
  sm/valid-transition?)

(def terminal?
  "Check if a status is terminal.
   (terminal? profile :completed) ;=> true"
  sm/terminal?)

(def event->transition
  "Map an event type to its configured transition."
  sm/event->transition)

;------------------------------------------------------------------------------ Layer 1
;; Agent registry

(def create-registry
  "Create a new agent registry.
   Returns: Atom containing agent store."
  registry/create-registry)

(def register-agent!
  "Register a new agent with the control plane.
   Returns: Complete agent record with :agent/id assigned."
  registry/register-agent!)

(def deregister-agent!
  "Remove an agent from the registry.
   Returns: The removed agent record."
  registry/deregister-agent!)

(def update-agent!
  "Update fields on an existing agent record.
   Returns: Updated agent record."
  registry/update-agent!)

(def get-agent
  "Get an agent record by UUID."
  registry/get-agent)

(def get-agent-by-external-id
  "Get an agent by vendor-specific external ID."
  registry/get-agent-by-external-id)

(def list-agents
  "List agents, optionally filtered by :vendor, :status, or :tag."
  registry/list-agents)

(def count-agents
  "Count agents, optionally filtered by status."
  registry/count-agents)

(def agents-by-status
  "Group agents by their current status."
  registry/agents-by-status)

(def record-heartbeat!
  "Record a heartbeat, updating timestamp and optional fields."
  registry/record-heartbeat!)

(def transition-agent!
  "Transition an agent to a new status with validation."
  registry/transition-agent!)

;------------------------------------------------------------------------------ Layer 2
;; Decision queue

(def create-decision
  "Create a new decision request.
   (create-decision agent-id \"Merge PR?\" {:priority :high})"
  dq/create-decision)

(def create-decision-manager
  "Create a new decision manager (atom-backed store)."
  dq/create-decision-manager)

(def submit-decision!
  "Submit a new decision to the queue."
  dq/submit-decision!)

(def resolve-decision!
  "Resolve a pending decision with the human's choice."
  dq/resolve-decision!)

(def cancel-decision!
  "Cancel a pending decision."
  dq/cancel-decision!)

(def get-decision
  "Get a decision by ID."
  dq/get-decision)

(def pending-decisions
  "Get all pending decisions, sorted by priority."
  dq/pending-decisions)

(def decisions-for-agent
  "Get all decisions for a specific agent."
  dq/decisions-for-agent)

(def count-pending
  "Count pending decisions."
  dq/count-pending)

(def expire-stale-decisions!
  "Expire all decisions past their deadline."
  dq/expire-stale-decisions!)

;------------------------------------------------------------------------------ Layer 3
;; Heartbeat watchdog

(def start-watchdog
  "Start the heartbeat watchdog background thread.
   Returns: Map with :future and :stop-fn."
  heartbeat/start-watchdog)

(def stop-watchdog
  "Stop a running heartbeat watchdog."
  heartbeat/stop-watchdog)

(def check-stale-agents
  "Check all agents for missed heartbeats (single pass)."
  heartbeat/check-stale-agents)

;------------------------------------------------------------------------------ Layer 4
;; Orchestrator

(def create-orchestrator
  "Create a control plane orchestrator.
   Coordinates adapters, registry, decisions, and heartbeat monitoring.
   (create-orchestrator {:adapters [claude-adapter]})"
  orch/create-orchestrator)

(def start!
  "Start the orchestrator discovery and polling loops."
  orch/start!)

(def stop!
  "Stop the orchestrator and all background loops."
  orch/stop!)

(def submit-decision-from-agent!
  "Submit a decision from an agent and transition it to :blocked."
  orch/submit-decision-from-agent!)

(def resolve-and-deliver!
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
