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

(ns ai.miniforge.artifact.interface.protocols.artifact-store
  "Public protocol for artifact persistence and retrieval.

   This is an extensibility point - users can implement custom artifact stores
   to persist artifacts to different backends (databases, file systems, cloud storage, etc.).")

(defprotocol ArtifactStore
  "Protocol for artifact persistence and retrieval."

  (save [this artifact]
    "Persist an artifact. Returns the artifact ID.")

  (load-artifact [this id]
    "Retrieve an artifact by ID. Returns nil if not found.")

  (query [this criteria]
    "Find artifacts matching criteria. Returns vector of artifacts.")

  (link [this parent-id child-id]
    "Establish provenance link between parent and child artifacts.
     Returns true on success.")

  (close [this]
    "Close the store and release resources."))
