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
(ns ai.miniforge.governance-provenance.dossier
  "Top-level assembly of the experimental incident dossier."
  (:require
   [ai.miniforge.governance-provenance.core :as core]
   [ai.miniforge.governance-provenance.git :as git]
   [ai.miniforge.governance-provenance.git-collect :as git-collect]
   [ai.miniforge.governance-provenance.model :as model]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} build-dossier
  [{:keys [repository incident locations specification-mappings policy-rules policy-context]
    :as request}
   {:keys [git-exec clock] :or {git-exec git/shell-exec clock #(java.time.Instant/now)}}]
  (if-not (core/valid-request? request)
    {:success? false
     :error {:error/type :invalid-incident-dossier-request
             :error/detail "repository/root, incident/id, and a safe positive line range are required"}}
    (let [facts (git-collect/collect-facts (:repository/root repository)
                                          (:repository/revision repository)
                                          locations git-exec)]
      (if-not (:success? facts)
        facts
        (let [revision (:repository/revision facts)
              incident-id (str (:incident/id incident))
              incident-n (model/node (str "incident:" incident-id) :incident revision
                                     {:source/type (:incident/source incident)
                                      :source/ref incident-id} incident)
              mappings (vec (or specification-mappings []))
              rules (vec (or policy-rules []))
              projections (mapv #(core/project-location incident-id revision % mappings rules
                                                        policy-context)
                                (:location-facts facts))
              gaps (vec (distinct (mapcat :gaps projections)))]
          {:success? true
           :dossier/schema-version "0.1"
           :dossier/status (if (seq gaps) :partial :complete)
           :dossier/observed-at (clock)
           :dossier/repository {:repository/root (:repository/root repository)
                                :repository/revision revision}
           :dossier/incident incident
           :dossier/nodes (model/unique-by :node/id
                                           (into [incident-n] (mapcat :nodes projections)))
           :dossier/edges (model/unique-edges (mapcat :edges projections))
           :dossier/claims (model/unique-by :node/id (mapcat :claims projections))
           :dossier/paths (vec (mapcat :paths projections))
           :dossier/coverage (core/coverage (:location-facts facts) mappings rules)
           :dossier/gaps gaps})))))
