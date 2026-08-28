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
(ns ai.miniforge.deliberation-workspace.validation-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [ai.miniforge.deliberation-workspace.validation :as validation]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} object-at [id touched-at & {:as overrides}]
  (merge (object/new-object {:id id :type :claim :statement "a claim"
                             :role :proposer :activation "act-1" :version 1})
         {:object/touched-at touched-at}
         overrides))

(defn- ^{:stratum 0} workspace [& objects]
  {:workspace/version 10
   :workspace/objects (into {} (map (juxt :object/id identity)) objects)
   :workspace/log []})

(defn- ^{:stratum 0} transaction [role basis & operations]
  (tx/new-transaction {:role role :activation "act-9" :basis basis
                       :operations operations}))

(defn- ^{:stratum 0} validate-tx [workspace transaction]
  (validation/validate workspace transaction validation/concurrency-stages))

(defn- ^{:stratum 0} subtype-of [result]
  (is (anomaly/anomaly? result))
  (anomaly/subtype result))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unknown-operations-are-refused
  (is (= :anomalies.deliberation/unknown-operation
         (subtype-of (validate-tx (workspace)
                                  (transaction :proposer 10 {:op :rewrite-history}))))))

(deftest ^{:stratum 1} operations-must-target-existing-objects
  (is (= :anomalies.deliberation/unknown-target
         (subtype-of (validate-tx
                      (workspace)
                      (transaction :proposer 10
                                   {:op :refine-claim :targets #{"claim-404"}}))))))

(deftest ^{:stratum 1} role-permissions-are-enforced
  (testing "a skeptic cannot accept decisions"
    (is (= :anomalies.deliberation/role-forbidden
           (subtype-of (validate-tx
                        (workspace (object-at "decision-1" 4))
                        (transaction :skeptic 10
                                     {:op :accept-decision :targets #{"decision-1"}}))))))
  (testing "the synthesizer can"
    (is (nil? (validate-tx
               (workspace (object-at "decision-1" 4))
               (transaction :synthesizer 10
                            {:op :accept-decision :targets #{"decision-1"}}))))))

(deftest ^{:stratum 1} exclusive-operations-refuse-a-stale-basis
  (testing "a target touched after the basis blocks an exclusive write"
    (is (= :anomalies.deliberation/stale-basis
           (subtype-of (validate-tx
                        (workspace (object-at "decision-1" 12))
                        (transaction :synthesizer 10
                                     {:op :accept-decision :targets #{"decision-1"}}))))))
  (testing "an untouched target commits"
    (is (nil? (validate-tx
               (workspace (object-at "decision-1" 9))
               (transaction :synthesizer 10
                            {:op :accept-decision :targets #{"decision-1"}}))))))

(deftest ^{:stratum 1} mergeable-operations-also-refuse-a-stale-basis
  (is (= :anomalies.deliberation/stale-basis
         (subtype-of (validate-tx
                      (workspace (object-at "claim-1" 12))
                      (transaction :proposer 10
                                   {:op :refine-claim :targets #{"claim-1"}}))))))

(deftest ^{:stratum 1} additive-operations-commute-over-a-stale-basis
  (testing "a stale basis does not block an additive operation"
    (is (nil? (validate-tx
               (workspace (object-at "claim-1" 12))
               (transaction :proposer 10
                            {:op :attach-evidence :targets #{"claim-1"}})))))
  (testing "but a terminal target does"
    (is (= :anomalies.deliberation/terminal-target
           (subtype-of (validate-tx
                        (workspace (object-at "claim-1" 12 :object/status :accepted))
                        (transaction :proposer 10
                                     {:op :attach-evidence :targets #{"claim-1"}})))))))

(deftest ^{:stratum 1} a-missing-basis-is-rejected-not-thrown
  (testing "nothing in the pipeline throws, including on a malformed basis"
    (doseq [basis [nil "10"]]
      (is (= :anomalies.deliberation/missing-basis
             (subtype-of (validate-tx (workspace (object-at "claim-1" 4))
                                      (transaction :proposer basis
                                                   {:op :refine-claim
                                                    :targets #{"claim-1"}}))))))))

(deftest ^{:stratum 1} the-first-failing-stage-wins
  (testing "schema conformance is checked before target existence"
    (is (= :anomalies.deliberation/unknown-operation
           (subtype-of (validate-tx
                        (workspace)
                        (transaction :proposer 10
                                     {:op :not-an-op :targets #{"claim-404"}})))))))

(deftest ^{:stratum 1} a-transaction-is-rejected-whole
  (testing "one bad operation rejects the transaction even when others pass"
    (is (some? (validate-tx
                (workspace (object-at "claim-1" 4))
                (transaction :proposer 10
                             {:op :attach-evidence :targets #{"claim-1"}}
                             {:op :refine-claim :targets #{"claim-404"}}))))))

(deftest ^{:stratum 1} creations-the-engine-cannot-construct-are-refused
  (testing "every payload object/new-object throws on is rejected as data first"
    (doseq [[label spec] [["unknown type" {:id "x" :type :wormhole :statement "s"}]
                          ["blank statement" {:id "x" :type :claim :statement "   "}]
                          ["missing statement" {:id "x" :type :claim}]
                          ["non-string statement" {:id "x" :type :claim :statement :s}]
                          ["unknown link type" {:id "x" :type :claim :statement "s"
                                                :links {:bogus #{"claim-1"}}}]
                          ["scalar link value" {:id "x" :type :claim :statement "s"
                                                :links {:supports "claim-1"}}]
                          ["non-map links" {:id "x" :type :claim :statement "s"
                                            :links [:supports]}]]]
      (is (= :anomalies.deliberation/invalid-creation
             (subtype-of (validate-tx (workspace)
                                      (transaction :proposer 10
                                                   {:op :assert-claim :creates [spec]}))))
          label))))

(deftest ^{:stratum 1} a-creation-rejection-carries-the-reason-it-failed
  (testing "routing reads the reason without parsing the message"
    (is (= :unknown-type
           (-> (validate-tx (workspace)
                            (transaction :proposer 10
                                         {:op :assert-claim
                                          :creates [{:id "x" :type :wormhole
                                                     :statement "s"}]}))
               :anomaly/data :reason)))))

(deftest ^{:stratum 1} a-well-formed-creation-passes
  (testing "the stage refuses malformed specs, not creation itself"
    (is (nil? (validate-tx (workspace)
                           (transaction :proposer 10
                                        {:op :assert-claim
                                         :creates [{:id "claim-9" :type :claim
                                                    :statement "the invariant holds"
                                                    :links {:supports #{"claim-1"}}}]})))))
  (testing "an operation that creates nothing is untouched by the stage"
    (is (nil? (validate-tx (workspace) (transaction :proposer 10 {:op :add-question}))))))

(deftest ^{:stratum 1} a-malformed-creates-payload-is-rejected-not-thrown
  (testing "insert-created reduces over :creates, so a scalar would crash the engine"
    (is (= :anomalies.deliberation/invalid-creation
           (subtype-of (validate-tx (workspace)
                                    (transaction :proposer 10
                                                 {:op :assert-claim :creates 5})))))))

(deftest ^{:stratum 1} creations-may-not-overwrite-an-object-the-workspace-holds
  (testing "insert-created writes with assoc-in, so a collision would erase the original"
    (is (= :anomalies.deliberation/duplicate-object-id
           (subtype-of (validate-tx
                        (workspace (object-at "claim-1" 4))
                        (transaction :proposer 10
                                     {:op :assert-claim
                                      :creates [{:id "claim-1" :type :goal
                                                 :statement "clobbered"}]}))))))
  (testing "a fresh id is free to be created"
    (is (nil? (validate-tx (workspace (object-at "claim-1" 4))
                           (transaction :proposer 10
                                        {:op :assert-claim
                                         :creates [{:id "claim-2" :type :claim
                                                    :statement "a second claim"}]}))))))

(deftest ^{:stratum 1} an-unknown-operation-outranks-a-bad-creation
  (testing "payload conformance runs after schema, so the vocabulary is reported first"
    (is (= :anomalies.deliberation/unknown-operation
           (subtype-of (validate-tx (workspace)
                                    (transaction :proposer 10
                                                 {:op :rewrite-history
                                                  :creates [{:id "x" :type :wormhole}]})))))))

(deftest ^{:stratum 1} a-creation-must-carry-a-usable-id
  (testing "without one the object lands in the graph under a nil key"
    (doseq [[label spec] [["missing" {:type :claim :statement "s"}]
                          ["blank" {:id "  " :type :claim :statement "s"}]
                          ["non-string" {:id :claim-1 :type :claim :statement "s"}]]]
      (is (= :anomalies.deliberation/invalid-creation
             (subtype-of (validate-tx (workspace)
                                      (transaction :proposer 10
                                                   {:op :assert-claim :creates [spec]}))))
          label)))
  (testing "the reason names the id, so the collision check is not silently vacuous"
    (is (= :blank-id
           (-> (validate-tx (workspace)
                            (transaction :proposer 10
                                         {:op :assert-claim
                                          :creates [{:type :claim :statement "s"}]}))
               :anomaly/data :reason)))))

(deftest ^{:stratum 1} one-operation-may-not-create-two-objects-at-one-id
  (testing "insert-created reduces, so the later spec would overwrite the earlier"
    (is (= :anomalies.deliberation/duplicate-object-id
           (subtype-of (validate-tx
                        (workspace)
                        (transaction :proposer 10
                                     {:op :assert-claim
                                      :creates [{:id "claim-1" :type :claim
                                                 :statement "first"}
                                                {:id "claim-1" :type :goal
                                                 :statement "second"}]}))))))
  (testing "distinct ids in one operation are fine"
    (is (nil? (validate-tx (workspace)
                           (transaction :proposer 10
                                        {:op :assert-claim
                                         :creates [{:id "claim-1" :type :claim
                                                    :statement "first"}
                                                   {:id "claim-2" :type :claim
                                                    :statement "second"}]}))))))
