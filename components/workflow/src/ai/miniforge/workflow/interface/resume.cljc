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

(ns ai.miniforge.workflow.interface.resume
  "Public workflow resume boundary."
  #?(:bb
     (:require
      [clojure.string :as str])
     :clj
     (:require
      [ai.miniforge.workflow.dag-resilience :as dag-resilience])))

#?(:bb
   (defn resume-context
     "Load resume context for workflow-id from checkpointed DAG state."
     ([workflow-id]
      (resume-context workflow-id {}))
     ([workflow-id opts]
      (throw (ex-info (str/join "" ["Workflow DAG resume is JVM-only: " workflow-id])
                      {:workflow-id workflow-id
                       :opts opts
                       :runtime :bb}))))
   :clj
   (def resume-context
     "Load resume context for workflow-id from checkpointed DAG state."
     dag-resilience/resume-context))
