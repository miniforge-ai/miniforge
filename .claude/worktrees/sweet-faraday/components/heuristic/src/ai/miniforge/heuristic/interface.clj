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

(ns ai.miniforge.heuristic.interface
  "Public API for the heuristic component.

   The heuristic component provides:
   - Storage for agent prompts, thresholds, and repair strategies
   - Versioning of heuristics for experimentation
   - Local and shared heuristic stores

   All external access to heuristic functionality goes through this interface."
  (:require
   [ai.miniforge.heuristic.core :as core]
   [ai.miniforge.heuristic.store :as store]))

;; ============================================================================
;; Layer 0 - Public API Delegation
;; ============================================================================

(defn get-heuristic
  "Get a heuristic by type and version.

   Parameters:
   - heuristic-type: Type of heuristic (:prompt, :threshold, :repair-strategy)
   - version: Version string (e.g., \"1.0.0\" or :latest)
   - opts: Optional map with :store (defaults to local store)

   Returns:
   Heuristic data map or nil if not found

   Example:
   (get-heuristic :implementer-prompt \"1.0.0\")
   (get-heuristic :inner-loop-max-iterations :latest)"
  ([heuristic-type version]
   (get-heuristic heuristic-type version {}))
  ([heuristic-type version opts]
   (core/get-heuristic heuristic-type version opts)))

(defn save-heuristic
  "Save a heuristic with versioning.

   Parameters:
   - heuristic-type: Type of heuristic
   - version: Version string
   - data: Heuristic data map
   - opts: Optional map with :store

   Returns:
   UUID of saved heuristic

   Example:
   (save-heuristic :implementer-prompt \"1.1.0\"
     {:system \"You are an implementer agent...\"
      :task-template \"Implement: {{task}}\"})'"
  ([heuristic-type version data]
   (save-heuristic heuristic-type version data {}))
  ([heuristic-type version data opts]
   (core/save-heuristic heuristic-type version data opts)))

(defn list-versions
  "List all versions of a heuristic type.

   Parameters:
   - heuristic-type: Type of heuristic
   - opts: Optional map with :store

   Returns:
   Vector of version strings sorted newest first

   Example:
   (list-versions :implementer-prompt)"
  ([heuristic-type]
   (list-versions heuristic-type {}))
  ([heuristic-type opts]
   (core/list-versions heuristic-type opts)))

(defn get-active-heuristic
  "Get the currently active version of a heuristic.

   The active version is marked in the store and is used by default.

   Parameters:
   - heuristic-type: Type of heuristic
   - opts: Optional map with :store

   Returns:
   Heuristic data map

   Example:
   (get-active-heuristic :implementer-prompt)"
  ([heuristic-type]
   (get-active-heuristic heuristic-type {}))
  ([heuristic-type opts]
   (core/get-active-heuristic heuristic-type opts)))

(defn set-active-version
  "Set the active version of a heuristic.

   Parameters:
   - heuristic-type: Type of heuristic
   - version: Version to make active
   - opts: Optional map with :store

   Returns:
   true if successful

   Example:
   (set-active-version :implementer-prompt \"1.1.0\")"
  ([heuristic-type version]
   (set-active-version heuristic-type version {}))
  ([heuristic-type version opts]
   (core/set-active-version heuristic-type version opts)))

(defn create-store
  "Create a heuristic store.

   Parameters:
   - store-type: Type of store (:local, :memory)
   - config: Store configuration

   Returns:
   Store instance

   Example:
   (create-store :local {:path \"~/.miniforge/heuristics\"})"
  [store-type config]
  (store/create-store store-type config))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Create a local store
  (def my-store (create-store :local {:path "~/.miniforge/heuristics"}))

  ;; Save a prompt heuristic
  (save-heuristic :implementer-prompt "1.0.0"
                  {:system "You are an implementer agent..."
                   :task-template "Implement: {{task}}"})

  ;; Get a specific version
  (get-heuristic :implementer-prompt "1.0.0")

  ;; List all versions
  (list-versions :implementer-prompt)

  ;; Get active version
  (get-active-heuristic :implementer-prompt)

  ;; Set active version
  (set-active-version :implementer-prompt "1.1.0")

  :end)
