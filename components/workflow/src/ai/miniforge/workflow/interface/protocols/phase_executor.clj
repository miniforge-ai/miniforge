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

(ns ai.miniforge.workflow.interface.protocols.phase-executor
  "Public protocol for executing individual SDLC phases.
   This is the extensibility point for custom phase executors.")

(defprotocol PhaseExecutor
  "Protocol for executing individual SDLC phases."

  (execute-phase [this workflow-state context]
    "Execute the current phase.
     Returns {:success? :artifacts :errors :metrics}")

  (can-execute? [this phase]
    "Check if this executor can handle a phase.")

  (get-phase-requirements [this phase]
    "Get required inputs for a phase.
     Returns {:required-artifacts [...] :optional-artifacts [...]}"))
