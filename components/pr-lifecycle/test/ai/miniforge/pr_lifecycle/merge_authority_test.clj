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
(ns ai.miniforge.pr-lifecycle.merge-authority-test
  "Provider-effect tests for granted and denied pull-request merges."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.pr-lifecycle.merge-readiness :as readiness]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} merge-context
  []
  {:dag-id (random-uuid)
   :run-id (random-uuid)
   :task-id (random-uuid)
   :pr-id 123
   :effect-store-dir
   (str (.toFile (Files/createTempDirectory
                  "merge-effects" (into-array FileAttribute []))))
   :grant-breach-dir
   (str (.toFile (Files/createTempDirectory
                  "merge-grants" (into-array FileAttribute []))))})

(defn- ^{:stratum 0} ready-result
  [& _]
  {:ready? true :checks {} :blocking []})

(defn- ^{:stratum 0} issued-merge-grant
  [request now scope-overrides expires-at]
  (grant/issue
   {:principal "runtime:test"
    :effect-class :effect/merge
    :scope (merge (select-keys request
                               [:effect/id :workflow-run/id :pr/repo :pr/number])
                  scope-overrides)
    :constraints {:constraint/max-count 1}
    :delegable? false
    :expires-at expires-at}
   now))

(defn- ^{:stratum 0} repository-result
  [_]
  (dag/ok {:pr/repo "miniforge-ai/miniforge"}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} attempt-with-issuer
  [issuer]
  (let [merge-calls (atom 0)
        context (merge-context)]
    (with-redefs [readiness/evaluate ready-result
                  merge/read-repository repository-result
                  grant/issue-for-effect issuer
                  merge/merge-pr!
                  (fn [_ _ & _]
                    (swap! merge-calls inc)
                    (dag/ok {:merged? true :method :squash}))]
      {:result (merge/attempt-merge "/tmp" 123
                                   merge/default-merge-policy context)
       :merge-calls @merge-calls})))

(deftest ^{:stratum 1} governed-merge-records-authority-before-provider-test
  (let [context (merge-context)
        at-command (atom nil)]
    (with-redefs [readiness/evaluate ready-result
                  merge/read-repository repository-result
                  merge/merge-pr!
                  (fn [_ _ & _]
                    (reset! at-command
                            (first (fx/list-records
                                    (:effect-store-dir context))))
                    (dag/ok {:merged? true :method :squash}))
                  merge/run-gh-command
                  (fn [_ _]
                    (dag/ok {:output "{\"state\":\"MERGED\",\"mergeCommit\":{\"oid\":\"real-squash-sha\"}}"}))]
      (let [result (merge/attempt-merge "/tmp" 123
                                       merge/default-merge-policy context)]
        (is (dag/ok? result))
        (is (= "real-squash-sha" (get-in result [:data :merge/sha])))
        (is (= :committing (:effect/state @at-command)))
        (is (= :granted (:effect/authority @at-command)))
        (is (uuid? (:effect/grant-id @at-command)))
        (is (uuid? (:effect/envelope-id @at-command)))
        (is (= (:effect/id @at-command)
               (get-in @at-command [:effect/proposal :effect/id])))
        (is (= (:run-id context)
               (get-in @at-command [:effect/proposal :workflow-run/id])))
        (is (= "miniforge-ai/miniforge"
               (get-in @at-command [:effect/proposal :pr/repo])))
        (is (= 123 (get-in @at-command [:effect/proposal :pr/number])))
        (is (= :granted
               (:effect/authority
                (first (fx/list-records (:effect-store-dir context))))))))))

(deftest ^{:stratum 1} pending-auto-merge-is-not-completed-test
  (with-redefs [readiness/evaluate ready-result
                merge/read-repository repository-result
                merge/merge-pr!
                (fn [_ _ & _] (dag/ok {:merged? true :method :squash}))
                merge/run-gh-command
                (fn [_ _]
                  (dag/ok {:output "{\"state\":\"OPEN\",\"mergeCommit\":null}"}))]
    (let [result (merge/attempt-merge "/tmp" 123
                                     merge/default-merge-policy
                                     (merge-context))]
      (is (dag/ok? result))
      (is (false? (get-in result [:data :merged?])))
      (is (true? (get-in result [:data :auto-merge/enabled?])))
      (is (nil? (get-in result [:data :merge/sha]))))))

(deftest ^{:stratum 1} uncertain-merge-does-not-claim-auto-merge-test
  (with-redefs [readiness/evaluate ready-result
                merge/read-repository repository-result
                merge/merge-pr!
                (fn [_ _ & _] (throw (ex-info "provider disconnected" {})))]
    (let [result (merge/attempt-merge "/tmp" 123
                                     merge/default-merge-policy
                                     (merge-context))]
      (is (dag/err? result))
      (is (= :merge-outcome-unknown (get-in result [:error :code])))
      (is (= :unknown-outcome
             (get-in result [:error :data :effect/state])))
      (is (= "provider disconnected"
             (get-in result [:error :data :effect/failure])))
      (is (nil? (get-in result [:data :auto-merge/enabled?]))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} denied-authority-never-invokes-merge-test
  (testing "an absent grant denies before the provider mutation"
    (let [{:keys [result merge-calls]} (attempt-with-issuer
                                        (fn [_ _ _] nil))]
      (is (dag/err? result))
      (is (= :merge-authority-denied (get-in result [:error :code])))
      (is (zero? merge-calls))))
  (testing "a mismatched grant denies before the provider mutation"
    (let [issuer (fn [_ request now]
                   (issued-merge-grant request now
                                       {:pr/repo "other/repository"}
                                       (.plusSeconds now 60)))
          {:keys [result merge-calls]} (attempt-with-issuer issuer)]
      (is (dag/err? result))
      (is (= :merge-authority-denied (get-in result [:error :code])))
      (is (zero? merge-calls))))
  (testing "an expired grant denies before the provider mutation"
    (let [issuer (fn [_ request now]
                   (issued-merge-grant request now {}
                                       (.minusSeconds now 1)))
          {:keys [result merge-calls]} (attempt-with-issuer issuer)]
      (is (dag/err? result))
      (is (= :merge-authority-denied (get-in result [:error :code])))
      (is (zero? merge-calls)))))
