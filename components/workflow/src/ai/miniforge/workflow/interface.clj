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

(ns ai.miniforge.workflow.interface
  "Canonical public boundary for the workflow component.
   Public API groups live under ai.miniforge.workflow.interface.* namespaces,
   while this namespace remains the Polylith entrypoint."
  (:require
   [ai.miniforge.workflow.interface.configurable :as configurable]
   [ai.miniforge.workflow.interface.pipeline :as pipeline]
   [ai.miniforge.workflow.interface.profiles :as profiles]
   [ai.miniforge.workflow.interface.protocols :as protocols]
   [ai.miniforge.workflow.interface.registry :as registry]
   [ai.miniforge.workflow.interface.runtime :as runtime]
   [ai.miniforge.workflow.interface.supervision :as supervision]))

;------------------------------------------------------------------------------ Layer 0
;; Protocols and workflow topology

(def Workflow protocols/Workflow)
(def PhaseExecutor protocols/PhaseExecutor)
(def WorkflowObserver protocols/WorkflowObserver)
(def phases protocols/phases)
(def phase-transitions protocols/phase-transitions)

;------------------------------------------------------------------------------ Layer 1
;; Workflow lifecycle and execution

(def create-workflow runtime/create-workflow)
(def add-observer runtime/add-observer)
(def remove-observer runtime/remove-observer)
(def start runtime/start)
(def get-state runtime/get-state)
(def advance runtime/advance)
(def rollback runtime/rollback)
(def complete runtime/complete)
(def fail runtime/fail)
(def create-phase-executor runtime/create-phase-executor)
(def execute-phase runtime/execute-phase)
(def can-execute? runtime/can-execute?)
(def get-phase-requirements runtime/get-phase-requirements)
(def run-workflow runtime/run-workflow)

;------------------------------------------------------------------------------ Layer 2
;; Supervision machine

(def supervision-states supervision/supervision-states)
(def supervision-terminal-states supervision/terminal-states)
(def supervision-transitions supervision/supervision-transitions)
(def valid-supervision-state? supervision/valid-state?)
(def supervision-terminal-state? supervision/terminal-state?)
(def valid-supervision-transition? supervision/valid-transition?)
(def next-supervision-state supervision/next-state)
(def transition-supervision supervision/transition)
(def get-supervision-events supervision/get-available-events)
(def supervision-machine-graph supervision/machine-graph)
(def initialize-supervision supervision/initialize)
(def transition-supervision-fsm supervision/transition-fsm)
(def current-supervision-state supervision/current-state)
(def final-supervision-state? supervision/is-final?)

;------------------------------------------------------------------------------ Layer 3
;; Pipeline, configurable workflows, persistence, and registry helpers

(def run-pipeline pipeline/run-pipeline)
(def build-pipeline pipeline/build-pipeline)
(def validate-pipeline pipeline/validate-pipeline)
(def run-chain pipeline/run-chain)
(def load-chain pipeline/load-chain)
(def list-chains pipeline/list-chains)

(def load-workflow configurable/load-workflow)
(def execute-workflow configurable/execute-workflow)
(def get-active-workflow configurable/get-active-workflow)
(def set-active-workflow configurable/set-active-workflow)
(def append-event configurable/append-event)
(def load-event-log configurable/load-event-log)
(def replay-workflow configurable/replay-workflow)
(def verify-determinism configurable/verify-determinism)
(def save-workflow configurable/save-workflow)
(def list-workflows configurable/list-workflows)
(def compare-workflows configurable/compare-workflows)
(def create-directory-publisher configurable/create-directory-publisher)
(def publish-output! configurable/publish-output!)
(def create-event-trigger configurable/create-event-trigger)
(def create-merge-trigger configurable/create-merge-trigger)
(def stop-trigger! configurable/stop-trigger!)

(def load-state-profile-provider profiles/load-state-profile-provider)
(def available-state-profile-ids profiles/available-state-profile-ids)
(def default-state-profile-id profiles/default-state-profile-id)
(def resolve-state-profile profiles/resolve-state-profile)

(def register-workflow! registry/register-workflow!)
(def get-workflow registry/get-workflow)
(def list-workflow-ids registry/list-workflow-ids)
(def workflow-exists? registry/workflow-exists?)
(def workflow-characteristics registry/workflow-characteristics)
(def ensure-initialized! registry/ensure-initialized!)
(def valid-recommendation? registry/valid-recommendation?)
(def explain-recommendation registry/explain-recommendation)
