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
(ns ai.miniforge.deliberation-workspace.object-test
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} claim [& {:as overrides}]
  (object/new-object
   (merge {:id "claim-1" :type :claim :statement "the cache invalidates on write"
           :role :proposer :activation "act-1" :version 3}
          overrides)))

(defn- ^{:stratum 0} evidence [source-class]
  (object/new-object
   {:id "evidence-1" :type :evidence :statement "suite run 18"
    :role :verifier :activation "act-2" :version 4
    :attrs {:source-class source-class}}))

(deftest ^{:stratum 0} status-model-is-closed-per-type
  (testing "goals carry only the three lifecycle statuses of N14 §2.3"
    (is (object/legal-status? :goal :accepted))
    (is (not (object/legal-status? :goal :contested))))
  (testing "questions cannot borrow deliberative statuses"
    (is (object/legal-status? :question :retired))
    (is (not (object/legal-status? :question :accepted))))
  (testing "every declared type has a status set"
    (is (= object/object-types (set (keys object/status-model))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} new-object-seeds-status-and-staleness-clock
  (testing "a new object takes its type's initial status"
    (is (= :open (:object/status (claim))))
    (is (= :proposed (:object/status (object/new-object
                                     {:id "experiment-1" :type :experiment
                                      :statement "stress the invariant"
                                      :role :verifier :activation "act-1"
                                      :version 1})))))
  (testing "creation version seeds the staleness clock"
    (let [c (claim)]
      (is (= 3 (:object/version c)))
      (is (= 3 (:object/touched-at c)))))
  (testing "every link type is present so callers never nil-pun"
    (is (= object/link-types (set (keys (:object/links (claim))))))))

(deftest ^{:stratum 1} new-object-rejects-programmer-errors
  (testing "an unknown type is a programmer error, not an anomaly"
    (is (thrown? IllegalArgumentException
                 (object/new-object {:id "x-1" :type :not-a-type :statement "s"
                                     :role :proposer :activation "a" :version 1}))))
  (testing "a blank statement is rejected"
    (is (thrown? IllegalArgumentException (claim :statement "   ")))))

(deftest ^{:stratum 1} terminal-detection-covers-every-type
  (is (object/terminal? (assoc (claim) :object/status :accepted)))
  (is (not (object/terminal? (claim)))))

(deftest ^{:stratum 1} touch-advances-only-the-staleness-clock
  (let [touched (object/touch (claim) 9)]
    (is (= 9 (:object/touched-at touched)))
    (is (= 3 (:object/version touched)) "creation version is immutable")))

(deftest ^{:stratum 1} links-are-typed
  (let [linked (object/add-link (claim) :supports "evidence-1")]
    (is (= #{"evidence-1"} (object/linked linked :supports)))
    (is (= #{} (object/linked linked :contradicts))))
  (is (thrown? IllegalArgumentException (object/add-link (claim) :vibes "evidence-1"))))

(deftest ^{:stratum 1} hard-constraints-are-identifiable
  (let [hard (object/new-object {:id "constraint-1" :type :constraint
                                 :statement "no writes outside src/"
                                 :role :interpreter :activation "act-1"
                                 :version 1 :attrs {:kind :hard}})]
    (is (object/hard-constraint? hard))
    (is (not (object/hard-constraint? (assoc-in hard [:object/attrs :kind] :soft))))
    (is (not (object/hard-constraint? (claim))))))

(deftest ^{:stratum 1} claim-acceptance-requires-execution-or-user-evidence
  (testing "agent analysis alone never accepts a claim"
    (is (not (object/acceptable-claim? [(evidence :agent-analysis)] 0)))
    (is (not (object/acceptable-claim? [(evidence :retrieval)] 0))))
  (testing "execution and user evidence both qualify"
    (is (object/acceptable-claim? [(evidence :execution)] 0))
    (is (object/acceptable-claim? [(evidence :user)] 0)))
  (testing "an open challenge blocks acceptance regardless of evidence"
    (is (not (object/acceptable-claim? [(evidence :execution)] 1))))
  (testing "no evidence at all never accepts"
    (is (not (object/acceptable-claim? [] 0)))))
