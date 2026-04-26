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

(ns ai.miniforge.workflow-resume.interface
  "Public API for workflow resume — reconstructing execution context
   from recorded events and preparing a trimmed workflow for re-run.

   Adapters (CLI, HTTP API, dashboard) compose runtime wiring on top;
   this interface is pure.

   Typical use:

       (require '[ai.miniforge.workflow-resume.interface :as wr])

       (def ctx (wr/reconstruct-context events-dir workflow-id))
       (def identity (wr/resolve-workflow-identity ctx select-default-fn))
       (def workflow (load-workflow (:workflow-type identity)
                                    (:workflow-version identity)))
       (def resumed (wr/trim-pipeline workflow (:completed-phases ctx)))
       ;; adapters call run-pipeline on `resumed` with the ctx's
       ;; :completed-dag-tasks / :completed-dag-artifacts threaded into
       ;; :pre-completed-dag-tasks / :pre-completed-artifacts"
  (:require
   [ai.miniforge.workflow-resume.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Pure extractors

(def completed?                  core/completed?)
(def failed?                     core/failed?)
(def paused?                     core/paused?)
(def extract-completed-dag-tasks core/extract-completed-dag-tasks)
(def extract-completed-dag-artifacts core/extract-completed-dag-artifacts)
(def extract-dag-pause-info      core/extract-dag-pause-info)
(def extract-completed-phases    core/extract-completed-phases)
(def extract-phase-results       core/extract-phase-results)
(def find-workflow-spec          core/find-workflow-spec)

;------------------------------------------------------------------------------ Layer 1
;; High-level APIs

(def reconstruct-context       core/reconstruct-context)
(def trim-pipeline             core/trim-pipeline)
(def resolve-workflow-identity core/resolve-workflow-identity)
