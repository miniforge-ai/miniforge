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
(ns ai.miniforge.artifact.protocols.records.transit-store
  "TransitArtifactStore record that implements the ArtifactStore protocol.

   Transit-based artifact store for Babashka compatibility.

   Architecture:
   - In-memory cache (atom) for fast access during execution
   - Transit JSON files for persistence (~/.miniforge/artifacts/)
   - Lazy loading: load from disk only when not in memory
   - Async writes: save and link queue changes; one writer agent per
     store applies them in order under a cross-process lock (see
     impl/drain-action); close waits for it (bounded by
     :close-timeout-ms, default impl/default-close-timeout-ms)
   - The artifact files are the source of truth; the index is a
     projection of them, rebuilt from them if it is unreadable. Files
     are written via temp file + rename

   File structure:
     ~/.miniforge/artifacts/
       ├── {uuid}.transit.json     - Individual artifact files
       └── index.transit.json      - Metadata index for queries

   Benefits:
   - Babashka compatible (no JVM-only deps)
   - Fast in-memory access during workflow execution
   - Survives process restarts
   - Can be streamed to central location later
  - Simple file-based persistence"
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
   [ai.miniforge.artifact.protocols.impl.transit-store :as impl]))

;------------------------------------------------------------------------------ Layer 0

(defrecord ^{:stratum 0} TransitArtifactStore [artifacts-dir cache index logger
                                               writer close-timeout-ms pending]
  p/ArtifactStore

  (save [this artifact]
    (impl/save-artifact this artifact))

  (load-artifact [this id]
    (impl/load-artifact-impl this id))

  (query [this criteria]
    (impl/query-artifacts this criteria))

  (link [this parent-id child-id]
    (impl/link-artifacts this parent-id child-id))

  (close [this]
    (impl/close-store this)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-transit-store
  ([] (create-transit-store {}))
  ([{:keys [dir logger close-timeout-ms]}]
   (let [artifacts-dir (impl/artifacts-dir dir)]
     ;; Ensure directory exists
     (impl/ensure-artifacts-dir! artifacts-dir)

     ;; Load existing index (rebuilt from artifact files if unreadable)
     (let [index (impl/load-index artifacts-dir logger)]
       (when logger
         (log/info logger :system :artifact/store-created
                   {:data {:type :transit
                           :dir artifacts-dir
                           :existing-artifacts (count index)}}))

       (->TransitArtifactStore
        artifacts-dir
        (atom {})           ; Empty cache initially
        (atom index)        ; Load existing index
        logger
        ;; State: ids written but not yet indexed (impl/drain-action).
        ;; Every action catches its own failures; :continue keeps one
        ;; that slips through from blocking every later write.
        (agent #{} :error-mode :continue)
        (or close-timeout-ms impl/default-close-timeout-ms)
        (atom []))))))   ; changes queued for impl/drain-action
