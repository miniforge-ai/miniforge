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

(ns ai.miniforge.control-plane-adapter.protocol
  "Protocol definition for vendor-specific control plane adapters.

   Adapters bridge between vendor-native agent APIs and the
   control plane's normalized state model. Each adapter handles
   discovery, status polling, decision delivery, and control
   commands for a specific vendor.

   Adapters can operate in push mode (agents call the control
   plane API) or pull mode (adapter discovers and polls agents).")

;------------------------------------------------------------------------------ Layer 0
;; Core adapter protocol

(defprotocol ControlPlaneAdapter
  "Protocol for vendor-specific agent integration.
   Adapters bridge between vendor-native agent APIs and
   the control plane's normalized model."

  (adapter-id [this]
    "Return keyword identifying this adapter (e.g., :claude-code, :openai).
     Must be unique across all registered adapters.")

  (discover-agents [this config]
    "Discover running agents for this vendor.

     Called periodically by the control plane orchestrator.
     Returns seq of maps conforming to agent registration schema:
       {:agent/vendor      keyword
        :agent/external-id string
        :agent/name        string
        :agent/capabilities #{keywords}
        :agent/metadata    {}}

     Returns empty seq if no agents are found or discovery is
     not supported (push-only adapters).")

  (poll-agent-status [this agent-record]
    "Poll current status of a single agent.

     Arguments:
     - agent-record - The agent's current record from the registry

     Returns updated map with keys:
       {:status  keyword   ;; normalized status
        :task    string    ;; current work (optional)
        :metrics map}      ;; cost/token metrics (optional)

     Returns nil if agent is no longer reachable.")

  (deliver-decision [this agent-record decision-resolution]
    "Deliver a resolved decision to the agent.

     Arguments:
     - agent-record        - The agent's registry record
     - decision-resolution - Map with:
       {:decision/id         uuid
        :decision/resolution string-or-keyword
        :decision/comment    string-or-nil}

     Returns {:delivered? bool :error string-or-nil}")

  (send-command [this agent-record command]
    "Send a control command to the agent.

     Commands: :pause :resume :terminate :restart

     Arguments:
     - agent-record - The agent's registry record
     - command      - Keyword command

     Returns {:success? bool :error string-or-nil}"))

;------------------------------------------------------------------------------ Layer 0
;; Optional extended protocol

(defprotocol ControlPlaneAdapterLogs
  "Optional protocol for adapters that support log retrieval."

  (agent-logs [this agent-record opts]
    "Retrieve recent logs/output from the agent.

     opts:
     - :limit - Max number of log entries
     - :since - java.util.Date, only logs after this time

     Returns seq of log-entry maps:
       [{:timestamp inst :level keyword :message string}]

     Returns nil if log retrieval is not supported."))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example adapter implementation skeleton:
  ;;
  ;; (defrecord ClaudeCodeAdapter [config]
  ;;   ControlPlaneAdapter
  ;;   (adapter-id [_] :claude-code)
  ;;   (discover-agents [_ config] ...)
  ;;   (poll-agent-status [_ agent-record] ...)
  ;;   (deliver-decision [_ agent-record resolution] ...)
  ;;   (send-command [_ agent-record command] ...))
  :end)
