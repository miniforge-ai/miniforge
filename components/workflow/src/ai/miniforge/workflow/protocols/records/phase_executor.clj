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

(ns ai.miniforge.workflow.protocols.records.phase-executor
  "Record implementations for PhaseExecutor protocol."
  (:require
   [ai.miniforge.workflow.interface.protocols.phase-executor :as p]
   [ai.miniforge.workflow.protocols.impl.phase-executor :as impl]))

(defrecord PlanPhaseExecutor [llm-backend]
  p/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (impl/execute-plan-phase llm-backend workflow-state context))

  (can-execute? [_this phase]
    (= phase :plan))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:spec]
     :optional-artifacts []}))

(defrecord ImplementPhaseExecutor [llm-backend]
  p/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (impl/execute-implement-phase llm-backend workflow-state context))

  (can-execute? [_this phase]
    (= phase :implement))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:plan]
     :optional-artifacts [:design]}))

(defrecord VerifyPhaseExecutor [llm-backend]
  p/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (impl/execute-verify-phase llm-backend workflow-state context))

  (can-execute? [_this phase]
    (= phase :verify))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:code]
     :optional-artifacts []}))

(defn create-plan-executor
  "Create a PlanPhaseExecutor instance."
  [llm-backend]
  (->PlanPhaseExecutor llm-backend))

(defn create-implement-executor
  "Create an ImplementPhaseExecutor instance."
  [llm-backend]
  (->ImplementPhaseExecutor llm-backend))

(defn create-verify-executor
  "Create a VerifyPhaseExecutor instance."
  [llm-backend]
  (->VerifyPhaseExecutor llm-backend))
