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
(ns ai.miniforge.self-healing.interface
  "Public interface for self-healing system.
   Exports functions from workaround-registry, workaround-detector, backend-health,
   integration, and stream-recovery."
  (:require
   [ai.miniforge.self-healing.workaround-registry :as registry]
   [ai.miniforge.self-healing.workaround-detector :as detector]
   [ai.miniforge.self-healing.backend-health :as health]
   [ai.miniforge.self-healing.integration :as integration]
   [ai.miniforge.self-healing.stream-recovery :as stream-recovery]))

;------------------------------------------------------------------------------ Layer 0

;;------------------------------------------------------------------------------ Workaround Registry
(def ^{:stratum 0} load-workarounds
  "Load workarounds from persistent storage."
  registry/load-workarounds)

(def ^{:stratum 0} save-workarounds!
  "Save workarounds to persistent storage."
  registry/save-workarounds!)

(def ^{:stratum 0} add-workaround!
  "Add a new workaround to the registry."
  registry/add-workaround!)

(def ^{:stratum 0} update-workaround-stats!
  "Update success/failure statistics for a workaround."
  registry/update-workaround-stats!)

(def ^{:stratum 0} get-workaround-by-pattern
  "Get workaround matching an error pattern ID."
  registry/get-workaround-by-pattern)

(def ^{:stratum 0} get-high-confidence-workarounds
  "Get all workarounds with confidence >= 0.8."
  registry/get-high-confidence-workarounds)

(def ^{:stratum 0} get-all-workarounds
  "Get all workarounds from registry."
  registry/get-all-workarounds)

(def ^{:stratum 0} delete-workaround!
  "Delete a workaround from the registry."
  registry/delete-workaround!)

;;------------------------------------------------------------------------------ Workaround Detector
(def ^{:stratum 0} match-error-to-workaround
  "Match error to a workaround pattern."
  detector/match-error-to-workaround)

(def ^{:stratum 0} apply-workaround
  "Apply workaround based on type."
  detector/apply-workaround)

(def ^{:stratum 0} detect-and-apply-workaround
  "Detect workaround for error and apply it."
  detector/detect-and-apply-workaround)

;;------------------------------------------------------------------------------ Backend Health
(def ^{:stratum 0} load-health
  "Load backend health data from persistent storage."
  health/load-health)

(def ^{:stratum 0} save-health!
  "Save backend health data to persistent storage."
  health/save-health!)

(def ^{:stratum 0} record-backend-call!
  "Record a backend API call and its result."
  health/record-backend-call!)

(def ^{:stratum 0} get-backend-success-rate
  "Get current success rate for a backend."
  health/get-backend-success-rate)

(def ^{:stratum 0} should-switch-backend?
  "Check if backend should be switched due to low success rate."
  health/should-switch-backend?)

(def ^{:stratum 0} in-cooldown?
  "Check if backend is in cooldown period after a switch."
  health/in-cooldown?)

(def ^{:stratum 0} select-best-backend
  "Select the best available backend that is not unhealthy or in cooldown."
  health/select-best-backend)

(def ^{:stratum 0} trigger-backend-switch!
  "Trigger a backend switch and record cooldown."
  health/trigger-backend-switch!)

(def ^{:stratum 0} check-and-switch-if-needed
  "Check current backend health and switch if necessary."
  health/check-and-switch-if-needed)

(def ^{:stratum 0} reset-backend-health!
  "Reset all backend health data to defaults, clearing stale metrics."
  health/reset-backend-health!)

;;------------------------------------------------------------------------------ Stream Recovery
(def ^{:stratum 0} evaluate-stall-recovery
  "Decide whether to resume, failover, or abort after a watchdog kill.

   Takes a context map with :phase-id, :backend, :session-id, :hang-count (atom),
   :config (self-healing config section), and :allowed-failover-backends.

   Decision rules:
     hang-count = 1, backend healthy   → {:action :resume,   :session-id sid, :backend kw}
     hang-count = 1, backend unhealthy → {:action :failover, :new-backend kw}
     hang-count >= 2                   → {:action :failover, :new-backend kw}
     no candidate                      → {:action :abort,    :reason \"no healthy backends\"}

   Side effects on :failover path:
     - record-backend-call! marks current backend unhealthy
     - trigger-backend-switch! records cooldown and updates default-backend

   See ai.miniforge.self-healing.stream-recovery/evaluate-stall-recovery."
  stream-recovery/evaluate-stall-recovery)

(def ^{:stratum 0} execute-resume!
  "Restart an agent subprocess using the backend-specific resume flag.

   Arguments: backend, session-id, optional extra-args.

   Constructs: <backend-binary> <resume-flag> <session-id> [extra-args...]
   and launches it with inherited stdio.

   Returns a process map {:process, :backend, :session-id, :command} on success,
   or an anomaly map {:anomaly/category, :anomaly/message, :cmd} on IOException.

   See ai.miniforge.self-healing.stream-recovery/execute-resume!"
  stream-recovery/execute-resume!)

;;------------------------------------------------------------------------------ Integration
(def ^{:stratum 0} execute-with-health-tracking
  "Execute operation with backend health tracking."
  integration/execute-with-health-tracking)

(def ^{:stratum 0} check-backend-health-and-switch
  "Check backend health and switch if necessary."
  integration/check-backend-health-and-switch)

(def ^{:stratum 0} emit-workaround-event
  "Emit workaround-applied event to event stream."
  integration/emit-workaround-event)

(def ^{:stratum 0} emit-backend-switch-event
  "Emit backend-switched event to event stream."
  integration/emit-backend-switch-event)

(def ^{:stratum 0} wrap-phase-execution
  "Wrap phase execution with self-healing capabilities."
  integration/wrap-phase-execution)
