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
(ns ai.miniforge.deliberation-workspace.commit-test
  (:require
   [ai.miniforge.deliberation-workspace.commit :as commit]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} obj [id type & {:keys [links status]}]
  (cond-> (object/new-object {:id id :type type :statement (str "statement " id)
                              :role :proposer :activation "act-1" :version 1
                              :links links})
    status (assoc :object/status status)))

(defn- ^{:stratum 0} workspace [& objects]
  {:workspace/version 5
   :workspace/objects (into {} (map (juxt :object/id identity)) objects)
   :workspace/log []})

(defn- ^{:stratum 0} transact [ws role & operations]
  (commit/commit ws (tx/new-transaction {:role role :activation "act-9"
                                         :basis 5 :operations operations})))

(defn- ^{:stratum 0} object-at [ws id]
  (get-in ws [:workspace/objects id]))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} committing-advances-the-version-once-per-transaction
  (let [ws (transact (workspace) :proposer {:op :add-question})]
    (is (= 6 (:workspace/version ws)))
    (is (= 1 (count (:workspace/log ws))))
    (is (= 7 (:workspace/version (transact ws :proposer {:op :add-question}))))))

(deftest ^{:stratum 1} created-objects-are-stamped-by-the-engine
  (let [ws (transact (workspace) :skeptic
                     {:op :assert-claim
                      :creates [{:id "claim-1" :type :claim
                                 :statement "the invariant holds"
                                 :role :synthesizer :version 999}]})
        created (object-at ws "claim-1")]
    (testing "provenance comes from the transaction, not the activation's claim"
      (is (= :skeptic (:object/role created)))
      (is (= 6 (:object/version created)))
      (is (= "act-9" (:object/activation created))))))

(deftest ^{:stratum 1} status-effects-are-derived-from-the-operation
  (testing "accepting a decision moves it to :accepted"
    (let [ws (transact (workspace (obj "decision-1" :decision)) :synthesizer
                       {:op :accept-decision :targets #{"decision-1"}})]
      (is (= :accepted (:object/status (object-at ws "decision-1"))))))
  (testing "a challenge contests its target"
    (let [ws (transact (workspace (obj "claim-1" :claim)) :skeptic
                       {:op :challenge :targets #{"claim-1"}})]
      (is (= :contested (:object/status (object-at ws "claim-1"))))))
  (testing "agent-supplied status is ignored — the operation decides"
    (let [ws (transact (workspace (obj "claim-1" :claim)) :skeptic
                       {:op :challenge :targets #{"claim-1"}
                        :status :accepted})]
      (is (= :contested (:object/status (object-at ws "claim-1")))))))

(deftest ^{:stratum 1} an-illegal-status-for-the-type-is-not-applied
  (testing "a question cannot be driven into a deliberative status"
    (let [ws (transact (workspace (obj "question-1" :question)) :synthesizer
                       {:op :accept-decision :targets #{"question-1"}})]
      (is (= :open (:object/status (object-at ws "question-1")))))))

(deftest ^{:stratum 1} closing-a-goal-requires-a-legal-outcome
  (testing "a declared outcome closes the goal"
    (let [ws (transact (workspace (obj "goal-1" :goal)) :synthesizer
                       {:op :close-goal :targets #{"goal-1"} :outcome :accepted})]
      (is (= :accepted (:object/status (object-at ws "goal-1"))))))
  (testing "an outcome outside the closed set leaves the goal open"
    (let [ws (transact (workspace (obj "goal-1" :goal)) :synthesizer
                       {:op :close-goal :targets #{"goal-1"} :outcome :maybe})]
      (is (= :open (:object/status (object-at ws "goal-1")))))))

(deftest ^{:stratum 1} every-touched-object-advances-the-staleness-clock
  (let [ws (transact (workspace (obj "claim-1" :claim)) :proposer
                     {:op :attach-evidence :targets #{"claim-1"}})]
    (is (= 6 (:object/touched-at (object-at ws "claim-1"))))))

(deftest ^{:stratum 1} challenges-are-recorded-for-the-anti-livelock-cap
  (let [ws (transact (workspace (obj "claim-1" :claim)) :skeptic
                     {:op :challenge :targets #{"claim-1"}})
        recorded (first (vals (:workspace/challenges ws)))]
    (is (= :skeptic (:challenge/role recorded)))
    (is (= "claim-1" (:challenge/target recorded)))
    (is (= :open (:challenge/status recorded)))))

(deftest ^{:stratum 1} links-are-written-onto-declared-targets-only
  (testing "an undeclared destination is not mutated"
    (let [ws (transact (workspace (obj "claim-1" :claim) (obj "evidence-1" :evidence))
                       :proposer
                       {:op :attach-evidence :targets #{"claim-1"}
                        :links {:supports #{"evidence-1"}}})]
      (is (= #{"evidence-1"}
             (object/linked (object-at ws "claim-1") :supports))
          "the declared target carries the edge")
      (is (= #{} (object/linked (object-at ws "evidence-1") :supports))
          "the destination was never declared, so validation never saw it"))))

(deftest ^{:stratum 1} links-to-an-unknown-target-do-not-create-objects
  (let [ws (transact (workspace (obj "claim-1" :claim)) :proposer
                     {:op :attach-evidence :targets #{"claim-1"}
                      :links {:supports #{"evidence-404"}}})]
    (is (nil? (object-at ws "evidence-404")))
    (is (= #{"evidence-404"} (object/linked (object-at ws "claim-1") :supports)))))

(deftest ^{:stratum 1} two-challenges-on-one-target-in-one-commit-both-record
  (testing "collapsing them would undercount the anti-livelock cap"
    (let [ws (transact (workspace (obj "claim-1" :claim)) :skeptic
                       {:op :challenge :targets #{"claim-1"} :evidence #{"e-1"}}
                       {:op :challenge :targets #{"claim-1"} :evidence #{"e-2"}})]
      (is (= 2 (count (:workspace/challenges ws)))))))

(deftest ^{:stratum 1} challenge-ids-follow-sorted-targets-not-set-order
  (testing "an id the log cannot rebuild is not reconstructible from the log"
    (let [ws (transact (workspace (obj "claim-1" :claim)
                                  (obj "claim-2" :claim)
                                  (obj "claim-3" :claim))
                       :skeptic
                       {:op :challenge :targets #{"claim-1" "claim-2" "claim-3"}})]
      (is (= {"claim-1" "challenge-6-0-claim-1"
              "claim-2" "challenge-6-1-claim-2"
              "claim-3" "challenge-6-2-claim-3"}
             (into {} (map (juxt :challenge/target :challenge/id))
                   (vals (:workspace/challenges ws))))
          "targets iterate out of order as a set, so ordinals must come from sort")))
  (testing "a second challenge operation counts on from the first"
    (let [ws (transact (workspace (obj "claim-1" :claim) (obj "claim-2" :claim))
                       :skeptic
                       {:op :challenge :targets #{"claim-1"}}
                       {:op :challenge :targets #{"claim-2"}})]
      (is (= #{"challenge-6-0-claim-1" "challenge-6-1-claim-2"}
             (set (keys (:workspace/challenges ws))))))))
