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
(ns ai.miniforge.governance-provenance.core
  "Pure projection of pinned source facts into incident dossier paths."
  (:require
   [ai.miniforge.governance-provenance.git :as git]
   [ai.miniforge.governance-provenance.model :as model]
   [ai.miniforge.policy-pack.interface :as policy]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} empty-projection
  []
  {:nodes [] :edges [] :claims [] :paths [] :gaps []})

(defn ^{:stratum 0} mapping-matches?
  [path mapping]
  (some #(policy/glob-matches? % path) (:mapping/file-globs mapping)))

(defn ^{:stratum 0} applicable-rules
  [path rules policy-context]
  (policy/filter-applicable-rules
   rules (assoc (or policy-context {}) :artifact {:artifact/path path})))

(defn ^{:stratum 0} valid-mapping?
  [mapping]
  (and (some? (:specification/id mapping))
       (some? (:mapping/id mapping))
       (seq (:mapping/file-globs mapping))
       (every? string? (:mapping/file-globs mapping))))

(defn ^{:stratum 0} valid-rule-reference?
  [rule]
  (some? (:rule/id rule)))

(defn ^{:stratum 0} commit-projection
  [incident-id revision subject facts commit pr]
  (let [commit-n (model/commit-node commit)
        claim (model/attribution-claim incident-id subject commit)
        evidence (model/blame-evidence revision subject facts commit)
        pr-n (some-> pr model/pull-request-node)
        nodes (cond-> [commit-n claim evidence] pr-n (conj pr-n))
        edges (cond-> [(model/edge (:node/id subject) :changed-by (:node/id commit-n) :derived
                                   [{:evidence/ref (:node/id evidence)}])
                        (model/edge (:node/id evidence) :supports (:node/id claim) :derived
                                   [{:evidence/type :git-blame}])]
                pr-n (conj (model/edge (:node/id commit-n) :contained-in (:node/id pr-n) :derived
                                       [{:evidence/type :local-merge-ancestry
                                         :evidence/ref (:pull-request/merge-commit pr)}])))
        path (cond-> [(:node/id subject) (:node/id commit-n)] pr-n (conj (:node/id pr-n)))]
    {:nodes nodes :edges edges :claims [claim]
     :paths [{:path/type :incident-change-candidate :path/nodes path}]}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid-request?
  [{:keys [repository incident locations specification-mappings policy-rules]}]
  (and (map? incident)
       (string? (:incident/id incident))
       (not (str/blank? (:incident/id incident)))
       (string? (:repository/root repository))
       (not (str/blank? (:repository/root repository)))
       (vector? locations)
       (seq locations)
       (every? git/valid-location? locations)
       (every? valid-mapping? (or specification-mappings []))
       (every? valid-rule-reference? (or policy-rules []))))

(defn ^{:stratum 1} coverage
  [location-facts mappings rules]
  (let [location-count (count location-facts)
        symbol-count (count (filter #(get-in % [:location :symbol :symbol/id]) location-facts))
        range-count (count (filter :blob-sha location-facts))
        commit-count (count (set (map :commit/sha (mapcat :commits location-facts))))
        pr-count (count (set (map (juxt :pull-request/number :pull-request/contains-commit)
                                  (mapcat :pull-requests location-facts))))
        mapped? (some (fn [{:keys [location]}]
                        (some #(mapping-matches? (:path location) %) mappings))
                      location-facts)]
    {:coverage/locations {:requested location-count :immutable-ranges range-count}
     :coverage/symbols {:status (cond (= symbol-count location-count) :provided
                                      (zero? symbol-count) :none
                                      :else :partial)
                        :resolved symbol-count :requested location-count}
     :coverage/pull-requests {:status :local-merge-history
                              :resolved pr-count :candidate-commits commit-count}
     :coverage/specifications (cond (empty? mappings) :not-provided
                                    mapped? :mapped
                                    :else :unresolved)
     :coverage/policy-rules (if (seq rules) :evaluated :not-provided)}))

(defn ^{:stratum 1} governance-projection
  [subject path mappings rules policy-context]
  (let [matched-mappings (filterv #(mapping-matches? path %) mappings)
        matched-rules (applicable-rules path rules policy-context)
        specs (mapv model/specification-node matched-mappings)
        rule-nodes (mapv model/rule-node matched-rules)
        spec-edges (mapv (fn [mapping spec]
                           (model/edge (:node/id subject) :governed-by (:node/id spec) :derived
                                       [{:evidence/type :specification-mapping
                                         :evidence/ref (:mapping/id mapping)}]))
                         matched-mappings specs)
        rule-edges (mapv (fn [rule rule-n]
                           (model/edge (:node/id subject) :governed-by (:node/id rule-n) :derived
                                       [{:evidence/type :policy-applicability
                                         :evidence/ref (str (:rule/id rule))}]))
                         matched-rules rule-nodes)]
    {:nodes (into specs rule-nodes) :edges (into spec-edges rule-edges)}))

(defn ^{:stratum 1} location-gaps
  [facts mappings rules policy-context]
  (let [{:keys [location blob-sha commits pull-requests]} facts
        path (:path location)
        pr-commits (set (map :pull-request/contains-commit pull-requests))
        matched-mappings (filter #(mapping-matches? path %) mappings)
        matched-rules (applicable-rules path rules policy-context)]
    (cond-> (vec (:gaps facts))
      (nil? (get-in location [:symbol :symbol/id]))
      (conj {:gap/type :symbol-resolution-unavailable :gap/path path})
      (nil? blob-sha)
      (conj {:gap/type :immutable-range-unavailable :gap/path path})
      (and (seq mappings) (empty? matched-mappings))
      (conj {:gap/type :specification-mapping-unresolved :gap/path path})
      (empty? mappings)
      (conj {:gap/type :specification-mappings-not-provided :gap/path path})
      (empty? rules)
      (conj {:gap/type :policy-rules-not-provided :gap/path path})
      :always
      (into (for [mapping matched-mappings :when (nil? (:specification/revision mapping))]
              {:gap/type :specification-revision-unavailable
               :gap/specification (:specification/id mapping)}))
      :always
      (into (for [rule matched-rules
                  :when (and (nil? (:rule/revision rule)) (nil? (:rule/version rule)))]
              {:gap/type :policy-rule-revision-unavailable :gap/rule (:rule/id rule)}))
      :always
      (into (for [commit commits
                  :when (not (contains? pr-commits (:commit/sha commit)))]
              {:gap/type :pull-request-unresolved :gap/commit (:commit/sha commit)})))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project-location
  [incident-id revision facts mappings rules policy-context]
  (if-let [subject (model/subject-node revision facts)]
    (let [range-n (model/range-node revision facts)
          incident-edge (model/edge (str "incident:" incident-id) :implicates
                                    (:node/id subject) :asserted
                                    [{:evidence/type :operator-selected-location}])
          range-edge (when (and range-n (not= (:node/id range-n) (:node/id subject)))
                       (model/edge (:node/id subject) :cites (:node/id range-n) :asserted
                                   [{:evidence/type :operator-selected-location}]))
          governance (governance-projection subject (get-in facts [:location :path])
                                            mappings rules policy-context)
          prs-by-commit (group-by :pull-request/contains-commit (:pull-requests facts))
          commits (mapv #(commit-projection incident-id revision subject facts %
                                            (first (get prs-by-commit (:commit/sha %))))
                        (:commits facts))
          paths (mapv #(update % :path/nodes (fn [nodes]
                                              (into [(str "incident:" incident-id)] nodes)))
                      (mapcat :paths commits))]
      {:nodes (into (cond-> [subject] range-n (conj range-n))
                    (concat (:nodes governance) (mapcat :nodes commits)))
       :edges (into (cond-> [incident-edge] range-edge (conj range-edge))
                    (concat (:edges governance) (mapcat :edges commits)))
       :claims (vec (mapcat :claims commits))
       :paths paths
       :gaps (location-gaps facts mappings rules policy-context)})
    (assoc (empty-projection) :gaps (location-gaps facts mappings rules policy-context))))
