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
(ns ai.miniforge.governance-provenance.git-collect
  "Composition of read-only git facts into pinned location records."
  (:require
   [ai.miniforge.governance-provenance.git :as git]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} location-facts
  [exec-fn repo-root revision location]
  (let [{:keys [shas gap]} (git/blamed-shas exec-fn repo-root revision location)
        commits (into [] (keep #(git/commit-fact exec-fn repo-root %)) shas)
        commit-shas (set (map :commit/sha commits))
        missing-commits (remove commit-shas shas)
        prs (into [] (keep #(git/pull-request-fact exec-fn repo-root revision
                                                    (:commit/sha %))) commits)]
    {:location location
     :blob-sha (git/blob-sha exec-fn repo-root revision (:path location))
     :commits commits
     :pull-requests prs
     :gaps (cond-> []
             gap (conj gap)
             (seq missing-commits)
             (into (map (fn [sha] {:gap/type :commit-metadata-unavailable
                                   :gap/commit sha})
                        missing-commits)))}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} collect-facts
  "Collect pinned range, blame, commit, and local merge-history facts."
  [repo-root revision locations exec-fn]
  (let [resolved (git/resolve-revision exec-fn repo-root revision)]
    (if-not (:success? resolved)
      {:success? false
       :error {:error/type :repository-revision-unavailable
               :error/detail (:error resolved)}}
      (let [sha (:output resolved)]
        {:success? true
         :repository/revision sha
         :location-facts (mapv #(location-facts exec-fn repo-root sha %) locations)}))))
