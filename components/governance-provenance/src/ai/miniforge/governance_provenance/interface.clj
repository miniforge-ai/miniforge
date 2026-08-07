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
(ns ai.miniforge.governance-provenance.interface
  "Experimental read-only incident provenance dossier API."
  (:require
   [ai.miniforge.governance-provenance.dossier :as dossier]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} build-dossier
  "Build a pinned Incident→Symbol/Range→Commit→PR evidence projection.

   Request keys: :repository {:repository/root, :repository/revision?},
   :incident {:incident/id, :incident/source?}, :locations (N1 line ranges,
   optionally carrying :symbol), :specification-mappings, :policy-rules, and
   :policy-context. The optional second argument accepts :git-exec and :clock
   test seams. The function is read-only and never emits `introduced-by`."
  ([request] (dossier/build-dossier request {}))
  ([request opts] (dossier/build-dossier request opts)))
