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
(ns ai.miniforge.deliberation-workspace.conflict-test
  (:require
   [ai.miniforge.deliberation-workspace.conflict :as conflict]
   [ai.miniforge.deliberation-workspace.object :as object]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} obj [id type & {:keys [links status]}]
  (cond-> (object/new-object {:id id :type type :statement (str "statement " id)
                              :role :proposer :activation "act-1" :version 1
                              :links links})
    status (assoc :object/status status)))

(defn- ^{:stratum 0} workspace [& objects]
  {:workspace/version 5
   :workspace/objects (into {} (map (juxt :object/id identity)) objects)})

(defn- ^{:stratum 0} conflicts-in [ws]
  (filter #(= :conflict (:object/type %)) (vals (:workspace/objects ws))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} contradicting-claims-derive-a-conflict
  (let [ws (conflict/derive-conflicts
            (workspace (obj "claim-1" :claim :links {:contradicts #{"claim-2"}})
                       (obj "claim-2" :claim))
            6)
        derived (first (conflicts-in ws))]
    (is (= "conflict-claim-1-claim-2" (:object/id derived)))
    (testing "conflicts are engine-derived, never proposed by a role"
      (is (= :engine (:object/role derived))))
    (testing "the conflict names both participants"
      (is (= #{"claim-1" "claim-2"} (object/linked derived :contradicts))))))

(deftest ^{:stratum 1} derivation-is-idempotent
  (let [base (workspace (obj "claim-1" :claim :links {:contradicts #{"claim-2"}})
                        (obj "claim-2" :claim))
        twice (-> base (conflict/derive-conflicts 6) (conflict/derive-conflicts 7))]
    (is (= 1 (count (conflicts-in twice))))))

(deftest ^{:stratum 1} a-derived-conflict-does-not-breed
  (testing "the conflict's own contradicts edges never derive further conflicts"
    (let [ws (-> (workspace (obj "claim-1" :claim :links {:contradicts #{"claim-2"}})
                            (obj "claim-2" :claim))
                 (conflict/derive-conflicts 6)
                 (conflict/derive-conflicts 7)
                 (conflict/derive-conflicts 8))]
      (is (= 1 (count (conflicts-in ws)))))))

(deftest ^{:stratum 1} a-terminal-participant-produces-no-conflict
  (let [ws (conflict/derive-conflicts
            (workspace (obj "claim-1" :claim :links {:contradicts #{"claim-2"}})
                       (obj "claim-2" :claim :status :superseded))
            6)]
    (is (empty? (conflicts-in ws)))))

(deftest ^{:stratum 1} a-missing-participant-produces-no-conflict
  (let [ws (conflict/derive-conflicts
            (workspace (obj "claim-1" :claim :links {:contradicts #{"claim-404"}}))
            6)]
    (is (empty? (conflicts-in ws)))))

(deftest ^{:stratum 1} derivation-is-deterministic
  (let [objects [(obj "claim-2" :claim :links {:contradicts #{"claim-1"}})
                 (obj "claim-1" :claim :links {:contradicts #{"claim-2"}})]
        forward (conflict/derive-conflicts (apply workspace objects) 6)
        reversed (conflict/derive-conflicts (apply workspace (reverse objects)) 6)]
    (is (= (sort (map :object/id (conflicts-in forward)))
           (sort (map :object/id (conflicts-in reversed)))))))

(deftest ^{:stratum 1} a-symmetric-pair-derives-exactly-one-conflict
  (testing "both sides holding the edge is still one contradiction"
    (let [ws (conflict/derive-conflicts
              (workspace (obj "claim-1" :claim :links {:contradicts #{"claim-2"}})
                         (obj "claim-2" :claim :links {:contradicts #{"claim-1"}}))
              6)]
      (is (= 1 (count (conflicts-in ws))))
      (is (= "conflict-claim-1-claim-2"
             (:object/id (first (conflicts-in ws))))))))

(deftest ^{:stratum 1} the-conflict-id-is-canonical-whichever-side-holds-the-edge
  (let [forward (conflict/derive-conflicts
                 (workspace (obj "claim-1" :claim :links {:contradicts #{"claim-2"}})
                            (obj "claim-2" :claim))
                 6)
        backward (conflict/derive-conflicts
                  (workspace (obj "claim-1" :claim)
                             (obj "claim-2" :claim :links {:contradicts #{"claim-1"}}))
                  6)]
    (is (= (map :object/id (conflicts-in forward))
           (map :object/id (conflicts-in backward)))
        "a directional id would make the same contradiction derive twice")
    (is (= "conflict-claim-1-claim-2"
           (:object/id (first (conflicts-in backward)))))))

(deftest ^{:stratum 1} an-object-contradicting-itself-derives-no-conflict
  (testing "a conflict is a relation between two objects"
    (let [ws (conflict/derive-conflicts
              (workspace (obj "claim-1" :claim :links {:contradicts #{"claim-1"}}))
              6)]
      (is (empty? (conflicts-in ws))
          "conflict-claim-1-claim-1 has no second participant to resolve")))
  (testing "a self-link does not suppress the real pair beside it"
    (let [ws (conflict/derive-conflicts
              (workspace (obj "claim-1" :claim
                              :links {:contradicts #{"claim-1" "claim-2"}})
                         (obj "claim-2" :claim))
              6)]
      (is (= ["conflict-claim-1-claim-2"]
             (map :object/id (conflicts-in ws)))))))
