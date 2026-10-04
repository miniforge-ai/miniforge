;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-workspace.semantics
  "Operation semantics. This validates structure and attestation, never prose truth."
  (:require [clojure.set :as set]
            [ai.miniforge.deliberation-workspace.validation :as v]
            [clojure.string :as str]))

(def creation-types
  {:assert-claim #{:claim} :add-question #{:question}
   :propose-hypothesis #{:hypothesis} :split-hypothesis #{:hypothesis}
   :propose-experiment #{:experiment} :propose-plan #{:plan}
   :propose-decision #{:decision} :register-artifact #{:artifact-ref}
   :attach-evidence #{:evidence} :add-goal #{:goal}
   :add-constraint #{:constraint} :declare-blocked #{:blocker}})

(def target-types
  {:refine-claim #{:claim :hypothesis} :revise-plan #{:plan}
   :challenge #{:claim :hypothesis :plan :decision}
   :attach-evidence #{:claim :hypothesis :plan :decision :experiment}
   :answer-question #{:question} :retire-question #{:question}
   :accept-decision #{:decision} :reject-decision #{:decision}
   :record-experiment-result #{:experiment} :invalidate-artifact #{:artifact-ref}
   :close-goal #{:goal} :split-hypothesis #{:hypothesis}
   :merge-hypotheses #{:hypothesis} :supersede #{:claim :hypothesis :plan :decision}})

(def operation-keys
  #{:op :targets :creates :links :evidence :discriminates :outcome
    :statement :reason :resolution})
(def creation-keys #{:id :type :statement :links :attrs})
(def attributes-by-type
  {:goal #{} :constraint #{:kind :source-ref} :question #{}
   :claim #{:kind :assumptions} :hypothesis #{:assumptions}
   :experiment #{:experiment-id} :evidence #{:source-class :receipt :source-ref}
   :plan #{:solution :assumptions} :decision #{:choice :solution :assumptions}
   :artifact-ref #{:capsule-id :digest :paths} :blocker #{}})
(defn- text? [x] (and (string? x) (not (str/blank? x))))
(defn- refuse [detail] (v/reject :invalid-input :anomalies.deliberation/semantics detail {}))

(defn check
  "Run after shape/concurrency validation. Trusted receipts live outside model payloads."
  [workspace operation {:keys [role]}]
  (let [op (:op operation)
        objects (:workspace/objects workspace)
        creates (:creates operation)
        created-types (into {} (map (juxt :id :type)) creates)
        targets (map objects (:targets operation))
        allowed (get creation-types op #{})
        edges (concat (mapcat val (:links operation))
                      (mapcat #(mapcat val (:links %)) creates)
                      (:evidence operation) (:discriminates operation))
        known (into (set (keys objects)) (map :id creates))]
    (cond
      (or (seq (remove operation-keys (keys operation)))
          (some #(> (count (:statement %)) (get workspace :workspace/statement-cap 4000)) creates))
      (refuse "Unknown operation fields")

      (some #(or (seq (remove creation-keys (keys %)))
                 (not (contains? allowed (:type %)))
                 (and (:attrs %) (not (map? (:attrs %))))
                 (seq (remove (get attributes-by-type (:type %) #{}) (keys (:attrs %))))) creates)
      (refuse "Creation type or fields do not belong to this operation")

      (and (contains? creation-types op) (not= :attach-evidence op) (empty? creates))
      (refuse "Creation operation requires at least one object")

      (and (contains? target-types op)
           (or (empty? targets)
               (some #(not (contains? (target-types op) (:object/type %))) targets)))
      (refuse "Operation requires targets of its documented type")

      (some #(not (contains? known %)) edges)
      (refuse "References must identify existing objects or objects created by this operation")

      (some (fn [spec]
              (let [{:keys [source-class receipt kind source-ref]} (:attrs spec)
                    attestation (get-in workspace [:workspace/receipts receipt])]
                (or (and (= :hard kind)
                         (not (and (= :interpreter role) (text? source-ref)
                                   (contains? (:workspace/spec-sources workspace) source-ref))))
                    (and (contains? #{:execution :user} source-class)
                         (not (and (= source-class (:source-class attestation))
                                   (= (:statement spec) (:statement attestation)))))))) creates)
      (refuse "Hard constraints require a spec source; execution/user evidence requires a runtime receipt")

      (some #(and (= :evidence (:type %))
                  (not (contains? #{:execution :user :retrieval :agent-analysis}
                                  (get-in % [:attrs :source-class])))) creates)
      (refuse "Evidence requires a known provenance class")

      (some #(and (= :constraint (:type %))
                  (not (contains? #{:hard :soft} (get-in % [:attrs :kind])))) creates)
      (refuse "Constraints require a hard or soft kind")

      (and (= :attach-evidence op) (empty? creates)
           (empty? (get-in operation [:links :supports])))
      (refuse "Attaching existing evidence requires a supports reference")

      (and (= :attach-evidence op)
           (some #(not= :evidence (or (:object/type (objects %)) (created-types %)))
                 (get-in operation [:links :supports])))
      (refuse "Evidence support links must reference evidence objects")

      (and (= :record-experiment-result op)
           (not (some (fn [id]
                        (let [e (objects id)
                              receipt (get-in workspace [:workspace/receipts (get-in e [:object/attrs :receipt])])]
                          (and (= :evidence (:object/type e))
                               (= :execution (:source-class receipt))
                               (= (:object/statement e) (:statement receipt)))))
                      (:evidence operation))))
      (refuse "Experiment completion requires execution evidence backed by a runtime receipt")

      (and (= :supersede op)
           (some (fn [target]
                   (or (and (= :decision (:object/type target)) (not= :synthesizer role))
                       (not (some #(and (not= % (:object/id target))
                                        (= (:object/type target) (:object/type (objects %))))
                                  (get-in operation [:links :resolves]))))) targets))
      (refuse "Supersession needs a distinct same-type replacement; decisions require synthesizer responsibility")

      (and (= :close-goal op)
           (not (some #(and (= :decision (:object/type (objects %)))
                            (= :accepted (:object/status (objects %)))
                            (set/subset? (set (:targets operation))
                                         (set (get-in (objects %) [:object/links :depends-on]))))
                      (get-in operation [:links :resolves]))))
      (refuse "Goal closure requires an accepted decision reference")

      (and (= :accept-decision op)
           (some (fn [target]
                   (let [choice (get-in target [:object/attrs :choice])]
                     (or (not (text? choice))
                         (some #(and (not= (:object/id target) (:object/id %))
                                     (= :decision (:object/type %))
                                     (= :accepted (:object/status %))
                                     (= choice (get-in % [:object/attrs :choice])))
                               (vals objects))))) targets))
      (refuse "Adoption requires a choice point with no other adopted decision; supersede first")

      (and (contains? #{:refine-claim :revise-plan :answer-question} op)
           (not (text? (:statement operation))))
      (refuse "Refinement requires a replacement statement")

      (and (= :supersede op) (empty? (get-in operation [:links :resolves])))
      (refuse "Supersession must reference its replacement")

      (some #(and (= :artifact-ref (:type %))
                  (not= (select-keys (:attrs %) [:capsule-id :digest :paths])
                        (get-in workspace [:workspace/artifact-exports (get-in % [:attrs :digest])]))) creates)
      (refuse "Artifact references require capsule, digest, and paths"))))
