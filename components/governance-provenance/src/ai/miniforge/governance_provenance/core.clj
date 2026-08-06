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

(defn ^{:stratum 0} valid-mapping?
  [mapping]
  (and (some? (:specification/id mapping))
       (some? (:mapping/id mapping))
       (seq (:mapping/file-globs mapping))
       (every? string? (:mapping/file-globs mapping))))

(defn ^{:stratum 0} valid-rule-reference?
  [rule]
  (some? (:rule/id rule)))

(defn ^{:stratum 0} prepend-incident-node
  [incident-id path]
  (update path :path/nodes #(into [(str "incident:" incident-id)] %)))

(defn ^{:stratum 0} pr-for-commit
  [prs-by-commit commit]
  (first (get prs-by-commit (:commit/sha commit))))

;; mapping/file-globs and policy-pack rule globs are always `/`-delimited
;; (per governance-provenance.git/valid-location?, which treats `\` as a
;; separator too); without normalizing here a Windows-style path would
;; fail to match either, silently skipping expected specification
;; mappings and rules.
(defn ^{:stratum 0} mapping-matches?
  [path mapping]
  (let [path (str/replace path "\\" "/")]
    (some #(policy/glob-matches? % path) (:mapping/file-globs mapping))))

(defn ^{:stratum 0} applicable-rules
  [path rules policy-context]
  (policy/filter-applicable-rules
   rules (assoc (or policy-context {})
                :artifact {:artifact/path (str/replace path "\\" "/")})))

(defn ^{:stratum 0} commit-projection
  [incident-id revision subject facts commit pr]
  (let [commit-n (model/commit-node commit)
        claim (model/attribution-claim incident-id subject commit)
        evidence (model/blame-evidence revision subject facts commit)
        pr-n (some-> pr model/pull-request-node)
        nodes (cond-> [commit-n claim evidence] pr-n (conj pr-n))
        changed-by-edge (model/edge1 (:node/id subject) :changed-by (:node/id commit-n) :derived
                               {:evidence/ref (:node/id evidence)})
        supports-edge (model/edge1 (:node/id evidence) :supports (:node/id claim) :derived
                             {:evidence/type :git-blame})
        contained-in-edge (when pr-n
                            (model/edge1 (:node/id commit-n) :contained-in (:node/id pr-n) :derived
                                   {:evidence/type :local-merge-ancestry
                                    :evidence/ref (:pull-request/merge-commit pr)}))
        edges (cond-> [changed-by-edge supports-edge] contained-in-edge (conj contained-in-edge))
        path (cond-> [(:node/id subject) (:node/id commit-n)] pr-n (conj (:node/id pr-n)))]
    {:nodes nodes :edges edges :claims [claim]
     :paths [{:path/type :incident-change-candidate :path/nodes path}]}))

(defn ^{:stratum 0} spec-governance-edge
  [subject mapping spec]
  (model/edge1 (:node/id subject) :governed-by (:node/id spec) :derived
         {:evidence/type :specification-mapping :evidence/ref (:mapping/id mapping)}))

(defn ^{:stratum 0} rule-governance-edge
  [subject rule rule-n]
  (model/edge1 (:node/id subject) :governed-by (:node/id rule-n) :derived
         {:evidence/type :policy-applicability :evidence/ref (str (:rule/id rule))}))

(defn ^{:stratum 0} location-implicates-edge
  [incident-id subject]
  (model/edge1 (str "incident:" incident-id) :implicates (:node/id subject) :asserted
         {:evidence/type :operator-selected-location}))

(defn ^{:stratum 0} location-cites-edge
  "Edge from `subject` to `range-n`, or nil when there's no range or the
   range and subject are already the same node (a symbol subject has no
   independent range to cite)."
  [subject range-n]
  (when (and range-n (not= (:node/id range-n) (:node/id subject)))
    (model/edge1 (:node/id subject) :cites (:node/id range-n) :asserted
           {:evidence/type :operator-selected-location})))

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

(defn- ^{:stratum 1} location-mapped?
  [{:keys [location]} mappings]
  (some #(mapping-matches? (:path location) %) mappings))

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

(defn ^{:stratum 1} governance-projection
  [subject path mappings rules policy-context]
  (let [matched-mappings (filterv #(mapping-matches? path %) mappings)
        matched-rules (applicable-rules path rules policy-context)
        specs (mapv model/specification-node matched-mappings)
        rule-nodes (mapv model/rule-node matched-rules)
        spec-edges (mapv #(spec-governance-edge subject %1 %2) matched-mappings specs)
        rule-edges (mapv #(rule-governance-edge subject %1 %2) matched-rules rule-nodes)]
    {:nodes (into specs rule-nodes) :edges (into spec-edges rule-edges)}))

(defn ^{:stratum 1} location-commit-projections
  [incident-id revision subject facts]
  (let [prs-by-commit (group-by :pull-request/contains-commit (:pull-requests facts))]
    (mapv #(commit-projection incident-id revision subject facts %
                              (pr-for-commit prs-by-commit %))
          (:commits facts))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} coverage
  [location-facts mappings rules]
  (let [location-count (count location-facts)
        symbol-count (count (filter #(get-in % [:location :symbol :symbol/id]) location-facts))
        range-count (count (filter :blob-sha location-facts))
        commit-count (count (set (map :commit/sha (mapcat :commits location-facts))))
        pr-count (count (set (map (juxt :pull-request/number :pull-request/contains-commit)
                                  (mapcat :pull-requests location-facts))))
        mapped? (some #(location-mapped? % mappings) location-facts)
        symbol-status (cond (= symbol-count location-count) :provided
                            (zero? symbol-count) :none
                            :else :partial)
        spec-status (cond (empty? mappings) :not-provided
                          mapped? :mapped
                          :else :unresolved)
        policy-status (if (seq rules) :evaluated :not-provided)]
    {:coverage/locations {:requested location-count :immutable-ranges range-count}
     :coverage/symbols {:status symbol-status :resolved symbol-count :requested location-count}
     :coverage/pull-requests {:status :local-merge-history
                              :resolved pr-count :candidate-commits commit-count}
     :coverage/specifications spec-status
     :coverage/policy-rules policy-status}))

(defn ^{:stratum 2} project-location
  [incident-id revision facts mappings rules policy-context]
  (let [gaps (location-gaps facts mappings rules policy-context)]
    (if-let [subject (model/subject-node revision facts)]
      (let [range-n (model/range-node revision facts)
            implicates-edge (location-implicates-edge incident-id subject)
            cites-edge (location-cites-edge subject range-n)
            governance (governance-projection subject (get-in facts [:location :path])
                                              mappings rules policy-context)
            commits (location-commit-projections incident-id revision subject facts)
            paths (mapv #(prepend-incident-node incident-id %) (mapcat :paths commits))
            nodes (into (cond-> [subject] range-n (conj range-n))
                        (concat (:nodes governance) (mapcat :nodes commits)))
            edges (into (cond-> [implicates-edge] cites-edge (conj cites-edge))
                        (concat (:edges governance) (mapcat :edges commits)))
            claims (vec (mapcat :claims commits))]
        {:nodes nodes :edges edges :claims claims :paths paths :gaps gaps})
      (assoc (empty-projection) :gaps gaps))))
