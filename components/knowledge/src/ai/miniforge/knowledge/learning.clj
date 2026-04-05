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

(ns ai.miniforge.knowledge.learning
  "Learning capture from agent execution.
   Layer 0: Learning capture
   Layer 1: Learning promotion (learning -> rule)
   Layer 2: Pattern detection"
  (:require
   [ai.miniforge.knowledge.schema :as schema]
   [ai.miniforge.knowledge.zettel :as zettel]
   [ai.miniforge.knowledge.store :as store]
   [ai.miniforge.knowledge.messages :as messages]
   [clojure.string :as str]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Learning capture

(defn capture-learning
  "Capture a new learning from agent execution.

   Arguments:
   - store   - Knowledge store
   - learning - Map matching LearningCapture schema:
     - :type      - Source type (:inner-loop, :meta-loop, etc.)
     - :title     - Short title for the learning
     - :content   - Detailed markdown content
     - :agent     - (optional) Agent role that generated this
     - :task-id   - (optional) Task that generated this
     - :tags      - (optional) Vector of keyword tags
     - :dewey     - (optional) Dewey classification
     - :links     - (optional) Vector of link specifications
     - :confidence - (optional) Confidence score 0.0-1.0

   Returns the created zettel.

   Example:
     (capture-learning store
       {:type :inner-loop
        :agent :implementer
        :title 'Protocol method collision'
        :content 'When using clear as a method...'
        :tags [:clojure :protocol :gotcha]
        :confidence 0.8})"
  [knowledge-store learning]
  {:pre [(m/validate schema/LearningCapture learning)]}
  (let [;; Generate a UID for the learning
        timestamp (.format (java.text.SimpleDateFormat. "yyyy-MM-dd-HHmmss")
                           (java.util.Date.))
        agent-prefix (when (:agent learning)
                       (str (name (:agent learning)) "-"))
        uid (str "L-" agent-prefix timestamp "-"
                 (-> (:title learning)
                     (str/lower-case)
                     (str/replace #"[^a-z0-9]+" "-")
                     (subs 0 (min 30 (count (:title learning))))))

        ;; Build source provenance
        source {:source/type (:type learning)
                :source/agent (:agent learning)
                :source/task-id (:task-id learning)
                :source/confidence (get learning :confidence 0.7)}

        ;; Resolve link targets (UIDs to UUIDs)
        links (when (seq (:links learning))
                (mapv (fn [{:keys [target type rationale]}]
                        (let [target-id (if (uuid? target)
                                          target
                                          (when-let [z (store/get-zettel-by-uid knowledge-store target)]
                                            (:zettel/id z)))]
                          (when target-id
                            (zettel/create-link target-id type rationale))))
                      (:links learning)))

        ;; Create the zettel
        z (zettel/create-zettel
           uid
           (:title learning)
           (:content learning)
           :learning
           :dewey (:dewey learning)
           :tags (:tags learning)
           :links (vec (remove nil? links))
           :source source
           :author (if (:agent learning)
                     (str "agent:" (name (:agent learning)))
                     "agent:unknown"))]

    (store/put-zettel knowledge-store z)))

(defn capture-inner-loop-learning
  "Convenience function to capture learning from inner loop execution.

   This is typically called when a repair cycle discovers something useful."
  [knowledge-store {:keys [agent task-id title content tags related-to]}]
  (capture-learning knowledge-store
                    {:type :inner-loop
                     :agent agent
                     :task-id task-id
                     :title title
                     :content content
                     :tags tags
                     :links (when related-to
                              [{:target related-to
                                :type :extends
                                :rationale "Learned during implementation related to this rule"}])
                     :confidence 0.7}))

(defn capture-meta-loop-learning
  "Convenience function to capture learning from meta loop (patterns across executions).

   This is typically called when the system observes recurring patterns."
  [knowledge-store {:keys [title content tags confidence related-tasks]}]
  (capture-learning knowledge-store
                    {:type :meta-loop
                     :title title
                     :content content
                     :tags tags
                     :confidence (or confidence 0.85)
                     :context (when (seq related-tasks)
                                (messages/t :learning/observed-across
                                            {:tasks (str/join ", " (map str related-tasks))}))}))

;------------------------------------------------------------------------------ Layer 1
;; Learning promotion

(defn promote-learning
  "Promote a learning to a rule after validation.

   This upgrades the zettel type from :learning to :rule,
   indicating it has been validated and should be treated as authoritative.

   Arguments:
   - store       - Knowledge store
   - learning-id - UUID of the learning to promote
   - opts        - Optional map:
     - :new-uid   - New UID for the rule (generates one if not provided)
     - :dewey     - Assign Dewey classification
     - :reviewed-by - Who reviewed/approved this

   Returns the updated zettel as a rule."
  [knowledge-store learning-id & [{:keys [new-uid dewey reviewed-by]}]]
  (when-let [learning (store/get-zettel-by-id knowledge-store learning-id)]
    (when (= :learning (:zettel/type learning))
      (let [;; Generate new rule UID if not provided
            rule-uid (or new-uid
                         (str (or dewey "800")
                              "-"
                              (-> (:zettel/title learning)
                                  (str/lower-case)
                                  (str/replace #"[^a-z0-9]+" "-")
                                  (subs 0 (min 40 (count (:zettel/title learning)))))))

            ;; Update source to mark promotion
            updated-source (-> (get learning :zettel/source {})
                               (assoc :source/promoted-at (java.util.Date.)
                                      :source/promoted-from (:zettel/uid learning))
                               (cond-> reviewed-by (assoc :source/reviewed-by reviewed-by)))

            ;; Create updated zettel
            rule (-> learning
                     (assoc :zettel/type :rule
                            :zettel/uid rule-uid
                            :zettel/source updated-source
                            :zettel/modified (java.util.Date.))
                     (cond-> dewey (assoc :zettel/dewey dewey)))]

        ;; Delete old learning and store as rule
        (store/delete-zettel knowledge-store learning-id)
        (store/put-zettel knowledge-store rule)))))

;------------------------------------------------------------------------------ Layer 2
;; Pattern detection

(defn detect-recurring-patterns
  "Detect recurring patterns among learnings by grouping on tags.

   Scans all learnings in the store and groups them by their tags.
   Returns patterns where 3+ learnings share a tag, sorted by frequency.

   Arguments:
   - knowledge-store - KnowledgeStore instance

   Options:
   - :min-occurrences - Minimum occurrences to flag (default 3)
   - :exclude-tags    - Tags to ignore when grouping (default #{:inner-loop :repair})

   Returns vector of maps:
     {:tag       keyword
      :count     int
      :learnings [zettel-summary...]}"
  [knowledge-store & [{:keys [min-occurrences exclude-tags]
                        :or {min-occurrences 3
                             exclude-tags #{:inner-loop :repair}}}]]
  (let [learnings (store/query knowledge-store {:include-types [:learning]})
        ;; Build tag -> learnings index
        tag-groups (reduce
                    (fn [acc z]
                      (let [tags (remove exclude-tags (:zettel/tags z []))]
                        (reduce (fn [a tag]
                                  (update a tag (fnil conj [])
                                          {:id (:zettel/id z)
                                           :uid (:zettel/uid z)
                                           :title (:zettel/title z)
                                           :confidence (get-in z [:zettel/source :source/confidence] 0)}))
                                acc tags)))
                    {} learnings)
        ;; Filter to patterns with enough occurrences
        patterns (->> tag-groups
                      (filter (fn [[_tag items]] (>= (count items) min-occurrences)))
                      (map (fn [[tag items]]
                             {:tag tag
                              :count (count items)
                              :learnings items}))
                      (sort-by :count >)
                      vec)]
    patterns))

(defn synthesize-recurring-patterns!
  "Detect recurring patterns and capture them as meta-loop learnings.

   Scans learnings for recurring tags, creates a meta-loop learning for each
   new pattern found. Skips patterns that already have a corresponding learning.

   Arguments:
   - knowledge-store - KnowledgeStore instance

   Returns count of new patterns synthesized."
  [knowledge-store]
  (let [patterns (detect-recurring-patterns knowledge-store)
        new-count (atom 0)]
    (doseq [{:keys [tag count learnings]} patterns]
      ;; Check if we already captured a pattern learning for this tag.
      ;; Search for learnings tagged :pattern that mention this tag name.
      (let [existing (store/query knowledge-store
                                  {:include-types [:learning]
                                   :tags [:pattern]
                                   :text-search (name tag)})]
        (when (empty? existing)
          (let [learning-list (str/join "\n" (map #(str "- " (:title %)) learnings))]
            (capture-meta-loop-learning
             knowledge-store
             {:title (messages/t :pattern/title {:tag (name tag) :count count})
              :content (messages/t :pattern/content {:tag (name tag)
                                                     :count count
                                                     :learnings learning-list})
              :tags [tag :meta-loop :pattern]
              :confidence 0.85
              :related-tasks (mapv :id learnings)})
            (swap! new-count inc)))))
    @new-count))

(defn list-learnings
  "List all learnings, optionally filtered.

   Options:
   - :min-confidence - Minimum confidence threshold
   - :agent          - Filter by agent role
   - :promotable?    - If true, only return high-confidence learnings"
  [knowledge-store & [{:keys [min-confidence agent promotable?]}]]
  (let [all-learnings (store/query knowledge-store {:include-types [:learning]})
        filtered (cond->> all-learnings
                   min-confidence
                   (filter #(>= (get-in % [:zettel/source :source/confidence] 0)
                                min-confidence))

                   agent
                   (filter #(= agent (get-in % [:zettel/source :source/agent])))

                   promotable?
                   (filter #(>= (get-in % [:zettel/source :source/confidence] 0) 0.8)))]
    (vec filtered)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create store
  (def store (store/create-store))

  ;; Capture a learning from inner loop
  (capture-inner-loop-learning store
                               {:agent :implementer
                                :task-id (random-uuid)
                                :title "Protocol method collision with JVM"
                                :content "When using `clear` as a protocol method,
                                         it collides with java.lang.Object. Use
                                         descriptive names like `clear-messages`."
                                :tags [:clojure :protocol :gotcha]})

  ;; List learnings
  (list-learnings store)

  ;; List promotable learnings
  (list-learnings store {:promotable? true})

  ;; Promote a learning to a rule
  (let [learning (first (list-learnings store))]
    (promote-learning store (:zettel/id learning)
                      {:dewey "210"
                       :reviewed-by "user"}))

  :leave-this-here)
