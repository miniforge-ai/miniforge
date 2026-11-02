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
(ns ai.miniforge.deliberation-workspace.interface
  "Public API of the deliberation workspace (N14). Every function is a
   trusted-runtime API: a model sees projections and supplies proposals,
   never the workspace. Adoption never authorizes an external effect."
  (:require
   [ai.miniforge.deliberation-workspace.admission :as admission]
   [ai.miniforge.deliberation-workspace.commit :as commit]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.projection :as projection]
   [ai.miniforge.deliberation-workspace.run :as run]))

;------------------------------------------------------------------------------ Layer 0

(defn new-object
  "Construct a workspace object; see `object/new-object`."
  [spec]
  (object/new-object spec))

(defn admit
  "Admit a proposal under a runtime-supplied activation context; see
   `admission/admit`."
  [workspace proposal context]
  (admission/admit workspace proposal context))

(defn project
  "Render the projection a participant sees; see `projection/project`."
  [workspace participant options]
  (projection/project workspace participant options))

(defn replay-transaction
  "Re-apply a logged transaction, reconstructing the graph (N14 §9.1)."
  [workspace transaction]
  (commit/commit workspace transaction))

(defn activate-participant
  "Run one work turn for a participant; see `run/activate-participant`."
  [workspace activate activation]
  (run/activate-participant workspace activate activation))

(defn run
  "Drive a deliberation run to a §7 closing rule; see `run/run`."
  [workspace activate options]
  (run/run workspace activate options))
