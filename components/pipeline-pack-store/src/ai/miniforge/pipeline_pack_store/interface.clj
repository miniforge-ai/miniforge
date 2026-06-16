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

(ns ai.miniforge.pipeline-pack-store.interface
  "Public API for pipeline pack persistence.

   JVM-only: this component is Datalevin-backed and has no non-JVM
   implementation. Runtime compatibility is declared explicitly via
   namespace metadata instead of hidden behind dynamic resolution."
  {:miniforge/runtime :jvm-only}
  (:require
   [ai.miniforge.pipeline-pack-store.datalevin-store :as datalevin-store]
   [ai.miniforge.pipeline-pack-store.protocol :as proto]))

;; -- Store creation --
(defn create-store
  "Create a pack store. Options:
     :dir - directory for persistent storage (nil = in-memory for tests)"
  ([] (datalevin-store/create-store))
  ([opts] (datalevin-store/create-store opts)))

;; -- Protocol delegations --
(defn save-pack
  "Persist a pack manifest and its metrics."
  [store pack]
  (proto/save-pack store pack))

(defn load-pack
  "Retrieve a pack by id."
  [store pack-id]
  (proto/load-pack store pack-id))

(defn list-packs
  "List all stored packs."
  [store]
  (proto/list-packs store))

(defn save-snapshot
  "Save a metric value snapshot."
  [store snapshot]
  (proto/save-snapshot store snapshot))

(defn latest-snapshots
  "Get the latest snapshot for each metric in a pack."
  [store pack-id]
  (proto/latest-snapshots store pack-id))

(defn save-run
  "Save a pipeline run record."
  [store run]
  (proto/save-run store run))

(defn runs-for-pack
  "Get all runs for a pack."
  [store pack-id]
  (proto/runs-for-pack store pack-id))

(defn close
  "Close the store and release resources."
  [store]
  (proto/close store))
