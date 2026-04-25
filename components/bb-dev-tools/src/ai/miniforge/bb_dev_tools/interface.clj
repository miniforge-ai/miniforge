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

(ns ai.miniforge.bb-dev-tools.interface
  "Public API for repo-local developer tool catalogs."
  (:require [ai.miniforge.bb-dev-tools.core :as core]))

(def default-filename
  core/default-filename)

(def default-toolset-id
  core/default-toolset-id)

(defn load-catalog
  ([] (core/load-catalog))
  ([path] (core/load-catalog path)))

(defn tool
  [catalog tool-id]
  (core/tool catalog tool-id))

(defn toolset
  [catalog toolset-id]
  (core/toolset catalog toolset-id))

(defn resolve-toolset-tools
  [catalog toolset-id]
  (core/resolve-toolset-tools catalog toolset-id))

(defn stage-plans
  [repo-root tool stage-key]
  (core/stage-plans repo-root tool stage-key))

(defn install-toolset
  [opts]
  (core/install-toolset opts))

(defn run-toolset
  [opts]
  (core/run-toolset opts))
