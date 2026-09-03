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
  {id {:challenge/id id :challenge/role role :challenge/target target
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
                                 {:op :refine-claim :targets #{"constraint-1"}})))))
  (testing "a soft constraint is fair game"
    (let [soft (assoc-in (hard-constraint "constraint-2") [:object/attrs :kind] :soft)]
      (is (nil? (validate (workspace [soft]) :proposer
                          {:op :refine-claim :targets #{"constraint-2"}}))))))

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
  (testing "backed? sets :evidence and a sibling's :discriminates; both would throw"
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
