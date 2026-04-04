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

(ns ai.miniforge.phase.knowledge-helpers
  "Shared utilities for knowledge injection with manifest capture across phases.

   Extracts the common pattern of calling inject-knowledge-with-manifest and
   splitting the result into formatted prompt text + manifest for evidence."
  (:require [ai.miniforge.knowledge.interface :as knowledge]))

(defn inject-with-manifest
  "Inject knowledge for an agent role and return both formatted text and manifest.

   Arguments:
   - knowledge-store - KnowledgeStore instance (or nil)
   - role - Agent role keyword (:planner, :implementer, :reviewer, etc.)
   - tags - Vector of keyword tags for context filtering

   Returns:
   {:formatted string-or-nil
    :manifest  [manifest-entry...] or nil}

   Returns {:formatted nil :manifest nil} when knowledge-store is nil or on error."
  [knowledge-store role tags]
  (if knowledge-store
    (try
      (let [{:keys [zettels manifest]} (knowledge/inject-knowledge-with-manifest
                                         knowledge-store role {:tags tags})
            formatted (when (seq zettels)
                        (knowledge/format-for-prompt zettels role))]
        {:formatted formatted
         :manifest (when (seq manifest) manifest)})
      (catch Exception _e
        {:formatted nil :manifest nil}))
    {:formatted nil :manifest nil}))
