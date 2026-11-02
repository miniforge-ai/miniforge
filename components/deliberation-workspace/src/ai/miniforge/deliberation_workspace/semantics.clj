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
(ns ai.miniforge.deliberation-workspace.semantics
  "Semantic admission stages (N14 §3.4): operation fields, creation and
   target types, reference integrity, and adoption rules. They check
   structure, never the truth of prose. Attestation lives in `provenance`."
  (:require
   [ai.miniforge.deliberation-workspace.messages :as messages]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [ai.miniforge.deliberation-workspace.validation :as validation]
   [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} creation-types
  "Object types each creating operation may create. An operation absent
   from this table creates nothing."
  {:assert-claim #{:claim} :add-question #{:question}
   :propose-hypothesis #{:hypothesis} :split-hypothesis #{:hypothesis}
   :propose-experiment #{:experiment} :propose-plan #{:plan}
   :propose-decision #{:decision} :register-artifact #{:artifact-ref}
   :attach-evidence #{:evidence} :add-goal #{:goal}
   :add-constraint #{:constraint} :declare-blocked #{:blocker}})

(def ^{:stratum 0} optional-creation
  "Creating operations that may instead reference what already exists."
  #{:attach-evidence})

(def ^{:stratum 0} target-types
  "Object types each targeting operation may target. An operation absent
   from this table carries no type requirement on its targets."
  {:refine-claim #{:claim :hypothesis} :revise-plan #{:plan}
   :challenge #{:claim :hypothesis :plan :decision}
   :attach-evidence #{:claim :hypothesis :plan :decision :experiment}
   :answer-question #{:question} :retire-question #{:question}
   :accept-decision #{:decision} :reject-decision #{:decision}
   :record-experiment-result #{:experiment} :invalidate-artifact #{:artifact-ref}
   :close-goal #{:goal} :split-hypothesis #{:hypothesis}
   :merge-hypotheses #{:hypothesis} :supersede tx/supersedable-types})

(def ^{:stratum 0} operation-keys
  "The closed set of fields an operation payload may carry. Anything else —
   an actor, a permission, a policy label — is model-supplied authority."
  #{:op :targets :creates :links :evidence :discriminates :outcome
    :statement :reason :resolution :replacement})

(def ^{:stratum 0} creation-keys
  "The closed set of fields an object specification may carry."
  #{:id :type :statement :links :attrs})

(def ^{:stratum 0} attributes-by-type
  "The attributes each object type may carry."
  {:goal #{} :constraint #{:kind :source-ref} :question #{}
   :claim #{:kind :assumptions} :hypothesis #{:assumptions}
   :experiment #{:experiment-id} :evidence #{:source-class :receipt :source-ref}
   :plan #{:solution :assumptions} :decision #{:choice :solution :assumptions}
   :artifact-ref #{:capsule-id :digest :paths} :blocker #{}})

(def ^{:stratum 0} constraint-kinds #{:hard :soft})

(def ^{:stratum 0} default-statement-cap
  "Characters one statement may hold (N14 §2.2). Manifest-configurable via
   `:workspace/statement-cap`; sized for a paragraph, not a document."
  4000)

(defn- ^{:stratum 0} spec-fields-defect? [spec]
  (let [attrs (get spec :attrs {})]
    (or (seq (remove creation-keys (keys spec)))
        (not (map? attrs))
        (seq (remove (get attributes-by-type (:type spec) #{}) (keys attrs))))))

(defn- ^{:stratum 0} referenced-ids
  "Every id an operation or its created objects point at."
  [operation]
  (concat (mapcat val (get operation :links {}))
          (mapcat #(mapcat val (get % :links {})) (validation/created-specs operation))
          (get operation :evidence [])
          (get operation :discriminates [])))

(defn- ^{:stratum 0} attribute-defect?
  "True when `spec` is evidence without a known source class or a
   constraint without a known kind."
  [spec]
  (case (:type spec)
    :evidence (not (contains? object/evidence-source-classes
                              (get-in spec [:attrs :source-class])))
    :constraint (not (contains? constraint-kinds (get-in spec [:attrs :kind])))
    false))

(defn- ^{:stratum 0} covers-goals?
  "True when `decision` is adopted and depends on every goal in `goal-ids`."
  [decision goal-ids]
  (and (= :decision (:object/type decision))
       (= :accepted (:object/status decision))
       (set/subset? goal-ids (set (get-in decision [:object/links :depends-on])))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} choice-taken?
  "True when another decision already holds `target`'s choice point."
  [workspace target]
  (let [choice (get-in target [:object/attrs :choice])]
    (some #(and (not= (:object/id target) (:object/id %))
                (= :decision (:object/type %))
                (= :accepted (:object/status %))
                (= choice (get-in % [:object/attrs :choice])))
          (vals (validation/objects-of workspace)))))

(defn- ^{:stratum 1} unadoptable?
  "True when the decision at `id` names no choice point, or one another
   adopted decision already holds."
  [workspace id]
  (let [target (get (validation/objects-of workspace) id)]
    (or (not (validation/text? (get-in target [:object/attrs :choice])))
        (choice-taken? workspace target))))

(defn- ^{:stratum 1} check-fields [_workspace operation _context]
  (when (or (seq (remove operation-keys (keys operation)))
            (some spec-fields-defect? (validation/created-specs operation)))
    (validation/reject-operation :anomalies.deliberation/unknown-field
                                 (messages/system-t :anomaly/unknown-field) operation)))

(defn- ^{:stratum 1} check-statement-cap [workspace operation _context]
  (let [cap (get workspace :workspace/statement-cap default-statement-cap)]
    (when (some #(< cap (count (:statement %))) (validation/created-specs operation))
      (validation/reject-operation :anomalies.deliberation/statement-too-long
                                   (messages/system-t :anomaly/statement-too-long)
                                   operation))))

(defn- ^{:stratum 1} check-creation-types [_workspace operation _context]
  (let [op (:op operation)
        allowed (get creation-types op #{})]
    (when (or (some #(not (contains? allowed (:type %))) (validation/created-specs operation))
              (and (contains? creation-types op)
                   (not (contains? optional-creation op))
                   (empty? (validation/created-specs operation))))
      (validation/reject-operation :anomalies.deliberation/creation-type
                                   (messages/system-t :anomaly/creation-type) operation))))

(defn- ^{:stratum 1} check-target-types [workspace operation _context]
  (when-let [allowed (get target-types (:op operation))]
    (let [targets (map (validation/objects-of workspace) (get operation :targets []))]
      (when (or (empty? targets)
                (some #(not (contains? allowed (:object/type %))) targets))
        (validation/reject-operation :anomalies.deliberation/target-type
                                     (messages/system-t :anomaly/target-type) operation)))))

(defn- ^{:stratum 1} check-references [workspace operation _context]
  (let [existing (set (keys (validation/objects-of workspace)))
        known (into existing (map :id) (validation/created-specs operation))]
    (when (some #(not (contains? known %)) (referenced-ids operation))
      (validation/reject-operation :anomalies.deliberation/dangling-reference
                                   (messages/system-t :anomaly/dangling-reference)
                                   operation))))

(defn- ^{:stratum 1} check-attributes [_workspace operation _context]
  (when (some attribute-defect? (validation/created-specs operation))
    (validation/reject-operation :anomalies.deliberation/invalid-attribute
                                 (messages/system-t :anomaly/invalid-attribute) operation)))

(defn- ^{:stratum 1} check-goal-closure [workspace operation _context]
  (when (and (= :close-goal (:op operation))
             (not (some #(covers-goals? (get (validation/objects-of workspace) %)
                                        (set (get operation :targets [])))
                        (get-in operation [:links :resolves] []))))
    (validation/reject-operation :anomalies.deliberation/uncovered-closure
                                 (messages/system-t :anomaly/uncovered-closure) operation)))

(defn- ^{:stratum 1} check-choice-point
  "N14 §3.4: adoption names a choice point that no other adopted decision
   holds. Unadopted alternatives may coexist."
  [workspace operation _context]
  (when (and (= :accept-decision (:op operation))
             (some #(unadoptable? workspace %) (get operation :targets [])))
    (validation/reject-operation :anomalies.deliberation/choice-point
                                 (messages/system-t :anomaly/choice-point) operation)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} semantic-stages
  "Ordered semantic stages. Field and type checks lead because the later
   stages read fields and object types those establish."
  [check-fields check-statement-cap check-creation-types check-target-types
   check-references check-attributes check-goal-closure check-choice-point])
