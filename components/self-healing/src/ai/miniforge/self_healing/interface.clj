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
   Exports functions from workaround-registry, workaround-detector, backend-health, and integration."
  (:require
   [ai.miniforge.self-healing.workaround-registry :as registry]
   [ai.miniforge.self-healing.workaround-detector :as detector]
   [ai.miniforge.self-healing.backend-health :as health]
   [ai.miniforge.self-healing.integration :as integration]))

;;------------------------------------------------------------------------------ Workaround Registry

(def load-workarounds
  "Load workarounds from persistent storage."
  registry/load-workarounds)

(def save-workarounds!
  "Save workarounds to persistent storage."
  registry/save-workarounds!)

(def add-workaround!
  "Add a new workaround to the registry."
  registry/add-workaround!)

(def update-workaround-stats!
  "Update success/failure statistics for a workaround."
  registry/update-workaround-stats!)

(def get-workaround-by-pattern
  "Get workaround matching an error pattern ID."
  registry/get-workaround-by-pattern)

(def get-high-confidence-workarounds
  "Get all workarounds with confidence >= 0.8."
  registry/get-high-confidence-workarounds)

(def get-all-workarounds
  "Get all workarounds from registry."
  registry/get-all-workarounds)

(def delete-workaround!
  "Delete a workaround from the registry."
  registry/delete-workaround!)

;;------------------------------------------------------------------------------ Workaround Detector

(def match-error-to-workaround
  "Match error to a workaround pattern."
  detector/match-error-to-workaround)

(def apply-workaround
  "Apply workaround based on type."
  detector/apply-workaround)

(def detect-and-apply-workaround
  "Detect workaround for error and apply it."
  detector/detect-and-apply-workaround)

;;------------------------------------------------------------------------------ Backend Health

(def load-health
  "Load backend health data from persistent storage."
  health/load-health)

(def save-health!
  "Save backend health data to persistent storage."
  health/save-health!)

(def record-backend-call!
  "Record a backend API call and its result."
  health/record-backend-call!)

(def get-backend-success-rate
  "Get current success rate for a backend."
  health/get-backend-success-rate)

(def should-switch-backend?
  "Check if backend should be switched due to low success rate."
  health/should-switch-backend?)

(def in-cooldown?
  "Check if backend is in cooldown period after a switch."
  health/in-cooldown?)

(def select-best-backend
  "Select the best available backend that is not unhealthy or in cooldown."
  health/select-best-backend)

(def trigger-backend-switch!
  "Trigger a backend switch and record cooldown."
  health/trigger-backend-switch!)

(def check-and-switch-if-needed
  "Check current backend health and switch if necessary."
  health/check-and-switch-if-needed)

(def reset-backend-health!
  "Reset all backend health data to defaults, clearing stale metrics."
  health/reset-backend-health!)

;;------------------------------------------------------------------------------ Integration

(def execute-with-health-tracking
  "Execute operation with backend health tracking."
  integration/execute-with-health-tracking)

(def check-backend-health-and-switch
  "Check backend health and switch if necessary."
  integration/check-backend-health-and-switch)

(def emit-workaround-event
  "Emit workaround-applied event to event stream."
  integration/emit-workaround-event)

(def emit-backend-switch-event
  "Emit backend-switched event to event stream."
  integration/emit-backend-switch-event)

(def wrap-phase-execution
  "Wrap phase execution with self-healing capabilities."
  integration/wrap-phase-execution)
