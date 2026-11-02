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
(ns ai.miniforge.deliberation-workspace.admission-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.interface :as board]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private goal-id "goal")

(defn- ^{:stratum 0} seed-workspace []
  {:workspace/version 0
   :workspace/log []
   :workspace/objects {goal-id (board/new-object {:id goal-id :type :goal :statement "solve"
                                                  :role :interpreter :activation "seed"
                                                  :version 0})}})

(defn- ^{:stratum 0} context
  "The runtime-supplied activation context for `role` acting as itself."
  [workspace role]
  {:role role :participant role :activation "trusted"
   :basis (get workspace :workspace/version 0)})

(defn- ^{:stratum 0} claim [id]
  {:op :assert-claim :creates [{:id id :type :claim :statement id}]})

(defn- ^{:stratum 0} decision [id choice & {:as links}]
  {:id id :type :decision :statement id :attrs {:choice choice} :links links})

(defn- ^{:stratum 0} submit [workspace role & operations]
  (board/admit workspace {:tx/operations (vec operations)} (context workspace role)))

(defn- ^{:stratum 0} admitted [workspace role & operations]
  (:workspace (apply submit workspace role operations)))

(defn- ^{:stratum 0} object-at [workspace id]
  (get-in workspace [:workspace/objects id]))

(defn- ^{:stratum 0} subtype-of [result]
  (is (anomaly/anomaly? result))
  (anomaly/subtype result))

(defn- ^{:stratum 0} adopted [id choice]
  (admitted (seed-workspace) :synthesizer
            {:op :propose-decision :creates [(decision id choice)]}
            {:op :accept-decision :targets [id]}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} identity-comes-from-the-runtime
  (testing "a proposal naming another responsibility is rejected"
    (is (= :anomalies.deliberation/actor-mismatch
           (subtype-of (board/admit (seed-workspace)
                                    {:tx/role :synthesizer :tx/operations [(claim "c")]}
                                    (context (seed-workspace) :proposer))))))
  (testing "a proposal supplying its own activation or basis is rejected"
    (doseq [field [:tx/activation :tx/basis :tx/participant]]
      (is (= :anomalies.deliberation/actor-mismatch
             (subtype-of (board/admit (seed-workspace)
                                      {field "forged" :tx/operations [(claim "c")]}
                                      (context (seed-workspace) :proposer)))))))
  (testing "an invalid or future context is rejected before any operation"
    (is (= :anomalies.deliberation/invalid-context
           (subtype-of (board/admit (seed-workspace) {:tx/operations [(claim "c")]}
                                    (assoc (context (seed-workspace) :proposer) :basis 7))))))
  (testing "provenance is stamped from the context"
    (let [created (object-at (admitted (seed-workspace) :proposer (claim "c")) "c")]
      (is (= "trusted" (:object/activation created)))
      (is (= :proposer (:object/participant created))))))

(deftest ^{:stratum 1} creation-types-and-fields-are-closed
  (doseq [operation [{:op :assert-claim :creates [{:id "q" :type :question :statement "q"}]}
                     {:op :assert-claim :creates [{:id "c" :type :claim :statement "c"
                                                   :attrs {:policy-clauses []}}]}
                     {:op :assert-claim :creates [{:id "c" :type :claim :statement "c"}]
                      :actor :synthesizer}
                     {:op :assert-claim :creates []}]]
    (is (anomaly/anomaly? (submit (seed-workspace) :proposer operation)))))

(deftest ^{:stratum 1} a-transaction-is-validated-in-order-and-published-once
  (let [operations [{:op :propose-decision :creates [(decision "d" "design" :depends-on [goal-id])]}
                    {:op :accept-decision :targets ["d"]}
                    {:op :close-goal :targets [goal-id] :outcome :accepted
                     :links {:resolves ["d"]}}]
        result (apply submit (seed-workspace) :synthesizer operations)]
    (testing "later operations see what earlier ones created"
      (is (= :accepted (:object/status (object-at (:workspace result) goal-id)))))
    (testing "the whole transaction is one version and one log entry"
      (is (= 1 (get-in result [:workspace :workspace/version])))
      (is (= 1 (count (get-in result [:workspace :workspace/log])))))
    (testing "a failing operation rejects the transaction whole"
      (is (anomaly/anomaly? (apply submit (seed-workspace) :synthesizer
                                   (conj operations {:op :accept-decision
                                                     :targets [goal-id]})))))))

(deftest ^{:stratum 1} closure-needs-an-adopted-covering-decision
  (let [ws (adopted "d" "design")]
    (is (= :anomalies.deliberation/uncovered-closure
           (subtype-of (submit ws :synthesizer {:op :close-goal :targets [goal-id]
                                                :outcome :accepted
                                                :links {:resolves ["d"]}}))))))

(deftest ^{:stratum 1} one-adopted-decision-per-choice-point
  (let [ws (admitted (adopted "d1" "same") :synthesizer
                     {:op :propose-decision :creates [(decision "d2" "same")]})]
    (testing "unadopted alternatives coexist, but a second adoption is refused"
      (is (= :anomalies.deliberation/choice-point
             (subtype-of (submit ws :synthesizer {:op :accept-decision :targets ["d2"]})))))
    (testing "superseding the adopted decision frees the choice point"
      (let [replaced (admitted ws :synthesizer
                               {:op :supersede :targets ["d1"] :replacement ["d2"]}
                               {:op :accept-decision :targets ["d2"]})]
        (is (= :superseded (:object/status (object-at replaced "d1"))))
        (is (= :accepted (:object/status (object-at replaced "d2"))))))
    (testing "only the synthesizer supersedes a decision"
      (is (= :anomalies.deliberation/role-forbidden
             (subtype-of (submit ws :skeptic {:op :supersede :targets ["d1"]
                                              :replacement ["d2"]})))))))

(deftest ^{:stratum 1} adopted-records-stay-open-to-challenge-and-evidence
  (let [ws (adopted "d" "design")
        challenged (admitted ws :skeptic {:op :challenge :targets ["d"]
                                          :reason "ordering assumed"
                                          :resolution "check the transport contract"})
        evidenced (admitted ws :proposer
                            {:op :attach-evidence :targets ["d"] :links {:supports ["e"]}
                             :creates [{:id "e" :type :evidence :statement "contrary"
                                        :attrs {:source-class :agent-analysis}}]})]
    (is (= :accepted (:object/status (object-at challenged "d"))))
    (is (true? (:object/contested? (object-at challenged "d"))))
    (is (= #{"e"} (get-in (object-at evidenced "d") [:object/links :supports])))))

(deftest ^{:stratum 1} references-must-resolve
  (is (= :anomalies.deliberation/dangling-reference
         (subtype-of (submit (seed-workspace) :proposer
                             {:op :assert-claim
                              :creates [{:id "c" :type :claim :statement "c"
                                         :links {:supports ["missing"]}}]})))))

(deftest ^{:stratum 1} distinct-contributions-are-not-retries
  (let [ws (admitted (seed-workspace) :proposer (claim "c1"))]
    (is (some? (admitted ws :proposer (claim "c2"))))
    (is (anomaly/anomaly? (submit ws :proposer (claim "c1"))))))

(deftest ^{:stratum 1} the-declared-basis-still-guards-staleness
  (let [ws (admitted (seed-workspace) :proposer (claim "c"))
        stale (board/admit ws {:tx/operations [{:op :refine-claim :targets ["c"]
                                                :statement "stale"}]}
                           (assoc (context ws :proposer) :basis 0))]
    (is (= :anomalies.deliberation/stale-basis (subtype-of stale)))
    (is (= "c" (:object/statement (object-at ws "c"))))))

(deftest ^{:stratum 1} one-adoption-cannot-name-a-choice-point-twice
  (let [ws (admitted (seed-workspace) :synthesizer
                     {:op :propose-decision :creates [(decision "d1" "same") (decision "d2" "same")]})]
    (is (= :anomalies.deliberation/choice-point
           (subtype-of (submit ws :synthesizer {:op :accept-decision :targets ["d1" "d2"]}))))))

(deftest ^{:stratum 1} the-statement-cap-bounds-revisions
  (let [ws (assoc (admitted (seed-workspace) :proposer (claim "c")) :workspace/statement-cap 5)]
    (is (= :anomalies.deliberation/statement-too-long
           (subtype-of (submit ws :proposer {:op :refine-claim :targets ["c"]
                                             :statement "far too long"}))))))

(deftest ^{:stratum 1} conflicts-are-derived-once-at-the-transaction-boundary
  (testing "a conflict created and resolved inside one transaction is never referenced"
    (let [ws (admitted (seed-workspace) :proposer (claim "b"))]
      (is (= :anomalies.deliberation/dangling-reference
             (subtype-of (submit ws :proposer
                                 {:op :assert-claim
                                  :creates [{:id "a" :type :claim :statement "a"
                                             :links {:contradicts ["b"]}}]}
                                 {:op :assert-claim
                                  :creates [{:id "c2" :type :claim :statement "cites"
                                             :links {:depends-on ["conflict-a-b"]}}]})))))))
