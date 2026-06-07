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

(ns ai.miniforge.control-plane-adapter.interface
  "Public API for the control plane adapter component.

   Provides the ControlPlaneAdapter protocol that vendor-specific
   adapters must implement, plus shared utilities for building adapters.

   Stratification (intra-namespace):
   Layer 0: Protocol re-exports + pure data + helpers with no in-ns deps.
   Layer 1: `heartbeat-interval-for-vendor` (consumes Layer 0 data)."
  (:require
   [ai.miniforge.control-plane-adapter.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports + pure data + helpers with no in-namespace deps.

(def ControlPlaneAdapter
  "Protocol vendor-specific adapters must implement to bridge a
   vendor-native agent API to the control plane's normalized model.

   Members: `adapter-id`, `discover-agents`, `poll-agent-status`,
   `deliver-decision`, `send-command`. Implement via defrecord/reify."
  proto/ControlPlaneAdapter)

(def ControlPlaneAdapterLogs
  "Optional protocol for adapters that can retrieve agent logs.

   Single member `agent-logs`; adapters without log support omit it."
  proto/ControlPlaneAdapterLogs)

(def adapter-id
  "Protocol fn. Return the keyword identifying an adapter (e.g.
   :claude-code, :openai). Unique across all registered adapters.

   Args: [this]. Returns: keyword."
  proto/adapter-id)

(def discover-agents
  "Protocol fn. Discover running agents for the adapter's vendor.

   Args: [this config]. Returns: seq of agent-registration maps
   ({:agent/vendor kw :agent/external-id str :agent/name str
   :agent/capabilities set-of-kw :agent/metadata map}); empty seq
   when none found or discovery unsupported (push-only adapters)."
  proto/discover-agents)

(def poll-agent-status
  "Protocol fn. Poll the current status of a single agent.

   Args: [this agent-record]. Returns: map
   {:status kw :task str-optional :metrics map-optional}, or nil
   when the agent is no longer reachable."
  proto/poll-agent-status)

(def deliver-decision
  "Protocol fn. Deliver a resolved decision to an agent.

   Args: [this agent-record decision-resolution], where
   decision-resolution is {:decision/id uuid
   :decision/resolution str-or-kw :decision/comment str-or-nil}.
   Returns: map {:delivered? boolean :error str-or-nil}."
  proto/deliver-decision)

(def send-command
  "Protocol fn. Send a control command to an agent.

   Args: [this agent-record command] where command is one of
   :pause :resume :terminate :restart.
   Returns: map {:success? boolean :error str-or-nil}."
  proto/send-command)

(def agent-logs
  "Protocol fn (ControlPlaneAdapterLogs). Retrieve recent agent logs.

   Args: [this agent-record opts] where opts is
   {:limit max-entries :since java.util.Date}. Returns: seq of
   log-entry maps ([{:timestamp inst :level kw :message str}]),
   or nil when log retrieval is unsupported."
  proto/agent-logs)

(def ^:private vendor-heartbeat-ms
  "Recommended heartbeat intervals per vendor (ms)."
  {:claude-code 15000    ;; local process, fast check
   :miniforge   10000    ;; native, fastest
   :openai      60000    ;; API rate limits
   :cursor      30000})

(defn normalize-status
  "Normalize a vendor-specific status string to a control plane status keyword.

   Arguments:
   - vendor-status - String or keyword from the vendor
   - mapping       - Map of vendor-status → control-plane-status

   Returns: Normalized status keyword, or :unknown if no mapping found.

   Example:
     (normalize-status \"requires_action\" openai-status-map)
     ;=> :blocked"
  [vendor-status mapping]
  (get mapping (keyword vendor-status) :unknown))

(defn ms-since
  "Calculate milliseconds elapsed since a timestamp.

   Arguments:
   - timestamp - java.util.Date

   Returns: Long milliseconds, or nil if timestamp is nil."
  [timestamp]
  (when timestamp
    (- (System/currentTimeMillis) (.getTime timestamp))))

;------------------------------------------------------------------------------ Layer 1
;; Composes Layer 0 (`vendor-heartbeat-ms`).

(defn heartbeat-interval-for-vendor
  "Return the recommended heartbeat interval for a vendor.

   Arguments:
   - vendor - Vendor keyword

   Returns: Interval in milliseconds."
  [vendor]
  (get vendor-heartbeat-ms vendor 30000))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (normalize-status "requires_action" {"requires_action" :blocked
                                        "in_progress" :running
                                        "completed" :completed})
  (ms-since (java.util.Date.))
  (heartbeat-interval-for-vendor :claude-code)
  :end)
