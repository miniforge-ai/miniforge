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

   Layer 0: Protocol re-exports
   Layer 1: Adapter utilities"
  (:require
   [ai.miniforge.control-plane-adapter.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def ControlPlaneAdapter proto/ControlPlaneAdapter)
(def ControlPlaneAdapterLogs proto/ControlPlaneAdapterLogs)

(def adapter-id proto/adapter-id)
(def discover-agents proto/discover-agents)
(def poll-agent-status proto/poll-agent-status)
(def deliver-decision proto/deliver-decision)
(def send-command proto/send-command)
(def agent-logs proto/agent-logs)

;------------------------------------------------------------------------------ Layer 1
;; Adapter utilities

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

(defn heartbeat-interval-for-vendor
  "Return the recommended heartbeat interval for a vendor.

   Arguments:
   - vendor - Vendor keyword

   Returns: Interval in milliseconds."
  [vendor]
  (case vendor
    :claude-code 15000    ;; local process, fast check
    :miniforge   10000    ;; native, fastest
    :openai      60000    ;; API rate limits
    :cursor      30000
    30000))               ;; default

;------------------------------------------------------------------------------ Rich Comment
(comment
  (normalize-status "requires_action" {"requires_action" :blocked
                                        "in_progress" :running
                                        "completed" :completed})
  (ms-since (java.util.Date.))
  (heartbeat-interval-for-vendor :claude-code)
  :end)
