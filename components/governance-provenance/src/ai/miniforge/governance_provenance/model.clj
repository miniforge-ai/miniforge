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
(ns ai.miniforge.governance-provenance.model
  "Pure node, edge, claim, and evidence constructors.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} node
  [id type revision source content]
  {:node/id id :node/type type :node/revision revision
   :node/source source :node/content content})

(defn ^{:stratum 0} edge
  [from type to status basis]
  {:edge/id (str from "|" (name type) "|" to)
   :edge/type type :edge/from from :edge/to to
   :edge/status status :edge/basis (vec basis)})

(defn ^{:stratum 0} unique-by
  [key-fn values]
  (->> values (reduce #(assoc %1 (key-fn %2) %2) (sorted-map)) vals vec))

(defn ^{:stratum 0} unique-edges
  [edges]
  (->> edges
       (reduce (fn [index edge]
                 (update index (:edge/id edge)
                         #(if % (update % :edge/basis into (:edge/basis edge)) edge)))
               (sorted-map))
       vals
       (mapv #(update % :edge/basis (comp vec distinct)))))

(defn ^{:stratum 0} rule-revision
  [rule]
  (str (or (:rule/revision rule) (:rule/version rule) "unversioned")))

(defn ^{:stratum 0} specification-revision
  [mapping]
  (str (or (:specification/revision mapping) "unversioned")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} range-node
  [revision {:keys [location blob-sha]}]
  (when blob-sha
    (let [{:keys [path start-line end-line]} location
          id (str "range:" blob-sha ":" path ":" start-line "-" end-line)]
      (node id :range revision {:source/type :git-blob :source/ref blob-sha}
            {:range/path path :range/blob-sha blob-sha
             :range/start-line start-line :range/end-line end-line}))))

(defn ^{:stratum 1} specification-node
  [mapping]
  (let [logical-id (str (:specification/id mapping))
        revision (specification-revision mapping)
        clause (:specification/clause mapping)]
    (node (str "specification:" logical-id "@" revision (when clause (str "#" clause)))
          :specification-revision revision
          {:source/type :specification :source/ref (:specification/path mapping)}
          (select-keys mapping [:specification/id :specification/path
                                :specification/clause :specification/revision]))))

(defn ^{:stratum 1} rule-node
  [rule]
  (let [logical-id (str (:rule/id rule))
        revision (rule-revision rule)]
    (node (str "rule:" logical-id "@" revision) :policy-rule-revision revision
          {:source/type :policy-rule :source/ref logical-id}
          (select-keys rule [:rule/id :rule/title :rule/enforcement
                             :rule/severity :rule/applies-to :rule/revision :rule/version]))))

(defn ^{:stratum 1} commit-node
  [commit]
  (node (str "commit:" (:commit/sha commit)) :commit (:commit/sha commit)
        {:source/type :git-commit :source/ref (:commit/sha commit)} commit))

(defn ^{:stratum 1} pull-request-node
  [pr]
  (node (str "pull-request:" (:pull-request/number pr)) :pull-request
        (:pull-request/merge-commit pr)
        {:source/type (:pull-request/source pr)
         :source/ref (str (:pull-request/number pr))} pr))

(defn ^{:stratum 1} attribution-claim
  [incident-id subject commit]
  (let [sha (:commit/sha commit)
        id (str "claim:" incident-id ":changed-by:" (:node/id subject) ":" sha)]
    (node id :generated-claim sha {:source/type :projection :source/ref "git-blame"}
          {:claim/id id
           :claim/status :derived
           :claim/type :candidate-change-attribution
           :claim/text (str "Commit " sha " changed lines implicated by incident " incident-id ".")
           :claim/caveat "changed-by evidence does not establish introduced-by causality"})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} subject-node
  [revision facts]
  (if-let [symbol-id (get-in facts [:location :symbol :symbol/id])]
    (node (str "symbol:" symbol-id) :symbol-revision revision
          {:source/type :repository-index :source/ref symbol-id}
          (get-in facts [:location :symbol]))
    (range-node revision facts)))

(defn ^{:stratum 2} blame-evidence
  "Evidence node linking `subject` to the commit that changed it, per
   git blame. `range-n` is nil when `facts` carries no `:blob-sha` (the
   blame lookup couldn't resolve one) -- baking a nil straight into the
   id string would produce a degenerate `...:nil:...` id indistinguishable
   from any other unresolved-range evidence for the same subject/commit,
   and silently returning `:evidence/range nil` while still claiming
   `:evidence/type :git-blame` would leave consumers unable to tell
   'no range' apart from 'range present but empty' without checking a
   for a missing map key. `:evidence/range-resolved?` makes that explicit
   and queryable instead."
  [revision subject facts commit]
  (let [sha (:commit/sha commit)
        range-n (range-node revision facts)
        range-id (or (:node/id range-n) "unresolved-range")
        id (str "evidence:git-blame:" (:node/id subject) ":" range-id ":" sha)]
    (node id :evidence revision {:source/type :git-blame :source/ref sha}
          (cond-> {:evidence/id id :evidence/type :git-blame
                   :evidence/subject (:node/id subject) :evidence/commit sha
                   :evidence/range-resolved? (some? range-n)}
            range-n (assoc :evidence/range (:node/content range-n))))))
