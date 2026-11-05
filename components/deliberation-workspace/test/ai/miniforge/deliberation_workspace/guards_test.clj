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
(ns ai.miniforge.deliberation-workspace.guards-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.guards :as guards]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [ai.miniforge.deliberation-workspace.validation :as validation]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} claim-object [id]
  (object/new-object {:id id :type :claim :statement "a claim" :role :proposer
                      :activation "act-1" :version 1}))

(defn- ^{:stratum 0} hard-constraint [id]
  (object/new-object {:id id :type :constraint :statement "no writes outside src/"
                      :role :interpreter :activation "act-1" :version 1
                      :attrs {:kind :hard}}))

(defn- ^{:stratum 0} workspace [objects & {:keys [challenges log challenge-limit]}]
  (cond-> {:workspace/version 10
           :workspace/objects (into {} (map (juxt :object/id identity)) objects)
           :workspace/challenges (or challenges {})
           :workspace/log (or log [])}
    challenge-limit (assoc :workspace/challenge-limit challenge-limit)))

(def ^{:stratum 0} all-stages
  (into validation/concurrency-stages guards/guard-stages))

(defn- ^{:stratum 0} subtype-of [result]
  (is (anomaly/anomaly? result))
  (anomaly/subtype result))

(defn- ^{:stratum 0} open-challenge [id role target]
  {id {:challenge/id id :challenge/participant role :challenge/target target
       :challenge/status :open}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} validate [ws role & operations]
  (validation/validate ws
                       (tx/new-transaction {:role role :activation "act-9"
                                            :basis 10 :operations operations})
                       all-stages))

(deftest ^{:stratum 1} the-pipeline-composes-both-halves
  (is (= (count all-stages)
         (+ (count validation/concurrency-stages) (count guards/guard-stages)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} hard-constraints-are-immutable-to-agents
  (testing "an agent may not touch a hard constraint"
    (is (= :anomalies.deliberation/hard-constraint-immutable
           (subtype-of (validate (workspace [(hard-constraint "constraint-1")])
                                 :proposer
                                 {:op :refine-claim :targets #{"constraint-1"} :statement "revised"})))))
  (testing "a soft constraint is fair game"
    (let [soft (assoc-in (hard-constraint "constraint-2") [:object/attrs :kind] :soft)]
      (is (nil? (validate (workspace [soft]) :proposer
                          {:op :refine-claim :targets #{"constraint-2"} :statement "revised"}))))))

(deftest ^{:stratum 2} bare-challenges-are-refused
  (testing "a challenge with neither evidence nor an experiment is refused"
    (is (= :anomalies.deliberation/bare-challenge
           (subtype-of (validate (workspace [(claim-object "claim-1")]) :skeptic
                                 {:op :challenge :targets #{"claim-1"}})))))
  (testing "evidence backs a challenge"
    (is (nil? (validate (workspace [(claim-object "claim-1")]) :skeptic
                        {:op :challenge :targets #{"claim-1"}
                         :evidence #{"evidence-4"}}))))
  (testing "a discriminating experiment in the same transaction backs it"
    (is (nil? (validate (workspace [(claim-object "claim-1")]) :skeptic
                        {:op :challenge :targets #{"claim-1"}}
                        {:op :propose-experiment :discriminates #{"claim-1"}}))))
  (testing "an experiment that discriminates something else does not"
    (is (= :anomalies.deliberation/bare-challenge
           (subtype-of (validate (workspace [(claim-object "claim-1")]) :skeptic
                                 {:op :challenge :targets #{"claim-1"}}
                                 {:op :propose-experiment
                                  :discriminates #{"claim-99"}}))))))

(deftest ^{:stratum 2} open-challenges-are-capped-per-role-and-object
  (let [saturated (merge (open-challenge "ch-1" :skeptic "claim-1")
                         (open-challenge "ch-2" :skeptic "claim-1"))
        ws (workspace [(claim-object "claim-1")] :challenges saturated)]
    (testing "the default limit refuses a third open challenge"
      (is (= :anomalies.deliberation/challenge-limit
             (subtype-of (validate ws :skeptic
                                   {:op :challenge :targets #{"claim-1"}
                                    :evidence #{"evidence-4"}})))))
    (testing "the cap is per role — another role is unaffected"
      (is (nil? (validate ws :verifier {:op :challenge :targets #{"claim-1"}
                                        :evidence #{"evidence-4"}}))))
    (testing "resolved challenges do not count against the cap"
      (let [resolved (assoc-in saturated ["ch-1" :challenge/status] :resolved)]
        (is (nil? (validate (workspace [(claim-object "claim-1")]
                                       :challenges resolved)
                            :skeptic
                            {:op :challenge :targets #{"claim-1"}
                             :evidence #{"evidence-4"}})))))
    (testing "the limit is manifest-configurable"
      (is (nil? (validate (workspace [(claim-object "claim-1")]
                                     :challenges saturated :challenge-limit 5)
                          :skeptic
                          {:op :challenge :targets #{"claim-1"}
                           :evidence #{"evidence-4"}}))))))

(deftest ^{:stratum 2} repeated-operations-are-refused
  (let [prior [(tx/new-transaction
                {:role :proposer :activation "act-2" :basis 4
                 :operations [{:op :attach-evidence :targets #{"claim-1"}}]})]
        ws (workspace [(claim-object "claim-1")] :log prior)]
    (testing "the same role cannot commit the same operation twice"
      (is (= :anomalies.deliberation/duplicate-operation
             (subtype-of (validate ws :proposer
                                   {:op :attach-evidence :targets #{"claim-1"}})))))
    (testing "a different role may still make that assertion"
      (is (nil? (validate ws :skeptic
                          {:op :attach-evidence :targets #{"claim-1"}}))))))

(deftest ^{:stratum 2} the-chain-runs-concurrency-stages-before-guards
  (testing "an unknown operation is caught by schema conformance, not a guard"
    (is (= :anomalies.deliberation/unknown-operation
           (subtype-of (validate (workspace [(hard-constraint "constraint-1")])
                                 :proposer
                                 {:op :not-an-op :targets #{"constraint-1"}}))))))

(deftest ^{:stratum 2} the-fields-the-backing-check-reads-are-routed-not-thrown
  (testing "backed? seqs :evidence and sets a sibling's :discriminates; both would throw"
    (doseq [[label operations]
            [["scalar :evidence"
              [{:op :challenge :targets #{"claim-1"} :evidence :evidence-4}]]
             ["scalar :discriminates on a sibling"
              [{:op :challenge :targets #{"claim-1"}}
               {:op :propose-experiment :discriminates :claim-1}]]]]
      (is (= :anomalies.deliberation/invalid-object-ids
             (subtype-of (apply validate (workspace [(claim-object "claim-1")])
                                :skeptic operations)))
          label)))
  (testing "the shape fault outranks the bare challenge read out of it"
    (is (not= :anomalies.deliberation/bare-challenge
              (subtype-of (validate (workspace [(claim-object "claim-1")]) :skeptic
                                    {:op :challenge :targets #{"claim-1"}}
                                    {:op :propose-experiment
                                     :discriminates :claim-1})))
        "an experiment whose :discriminates is unreadable never backed anything")))

(deftest ^{:stratum 2} a-concern-with-a-resolution-criterion-backs-a-challenge
  (let [ws (workspace [(claim-object "claim-1")])]
    (testing "a reason and a resolution together back the challenge"
      (is (nil? (validate ws :skeptic {:op :challenge :targets #{"claim-1"}
                                        :reason "ordering assumed"
                                        :resolution "check the transport contract"}))))
    (testing "a reason alone is still a bare objection"
      (is (= :anomalies.deliberation/bare-challenge
             (subtype-of (validate ws :skeptic {:op :challenge :targets #{"claim-1"}
                                                 :reason "ordering assumed"
                                                 :resolution " "})))))))

(deftest ^{:stratum 2} idempotency-compares-the-whole-payload-per-participant
  (let [committed {:op :refine-claim :targets #{"claim-1"} :statement "first"}
        ws (workspace [(claim-object "claim-1")]
                      :log [(tx/new-transaction {:role :proposer :activation "act-1"
                                                 :basis 9 :operations [committed]})])]
    (testing "a new payload against the same target is a distinct contribution"
      (is (nil? (validate ws :proposer (assoc committed :statement "second")))))
    (testing "the identical payload is a retry"
      (is (= :anomalies.deliberation/duplicate-operation
             (subtype-of (validate ws :proposer committed)))))))

(deftest ^{:stratum 2} the-challenge-cap-counts-per-participant
  (let [ws (workspace [(claim-object "claim-1")]
                      :challenges (merge (open-challenge "ch-1" :skeptic-1 "claim-1")
                                         (open-challenge "ch-2" :skeptic-1 "claim-1")))
        challenge {:op :challenge :targets #{"claim-1"} :evidence #{"evidence-4"}}
        as (fn [participant]
             (validation/validate ws (assoc (tx/new-transaction
                                             {:role :skeptic :activation "act-9" :basis 10
                                              :operations [challenge]})
                                            :tx/participant participant)
                                  guards/guard-stages))]
    (is (= :anomalies.deliberation/challenge-limit (subtype-of (as :skeptic-1))))
    (is (nil? (as :skeptic-2)))))

(deftest ^{:stratum 2} idempotency-is-keyed-on-the-participant
  (let [committed {:op :refine-claim :targets #{"claim-1"} :statement "first"}
        logged (fn [participant role]
                 (assoc (tx/new-transaction {:role role :activation "act-1" :basis 9
                                             :operations [committed]})
                        :tx/participant participant))
        as (fn [ws participant role]
             (validation/validate ws (assoc (tx/new-transaction
                                             {:role role :activation "act-9" :basis 10
                                              :operations [committed]})
                                            :tx/participant participant)
                                  guards/guard-stages))
        ws (workspace [(claim-object "claim-1")] :log [(logged :proposer-1 :proposer)])]
    (testing "another participant sharing the role may submit the same payload"
      (is (nil? (as ws :proposer-2 :proposer))))
    (testing "the same participant under another role is still a retry"
      (is (= :anomalies.deliberation/duplicate-operation
             (subtype-of (as ws :proposer-1 :skeptic)))))))
