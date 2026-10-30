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
(ns ai.miniforge.pr-lifecycle.merge-grant-registry-test
  "Scenario: a merge grant that is unregistered, or revoked after issue,
   refuses the merge before `gh pr merge` runs. Commit re-reads the grant
   from the registry instead of trusting the copy issuance returned."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.pr-lifecycle.merge-readiness :as readiness]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} merge-context []
  (let [tmp #(.getCanonicalPath
              (.toFile (Files/createTempDirectory % (into-array FileAttribute []))))]
    {:run-id (random-uuid)
     :pr-id 7
     :effect-store-dir (tmp "registry-effects")
     :grant-breach-dir (tmp "registry-grants")}))

(defn- ^{:stratum 0} github
  "Answer the read-only gh calls a merge attempt makes."
  [args _worktree]
  (if (some #{"headRefOid"} args)
    (dag/ok {:output "0123456789abcdef0123456789abcdef01234567"})
    (dag/ok {:output "{\"state\":\"OPEN\",\"mergeCommit\":null}"})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} attempt-with-registration
  "Merge PR 7 with `register` standing in for `grant/register!`."
  [register]
  (let [merge-calls (atom 0)
        context (merge-context)]
    (with-redefs [readiness/evaluate (constantly {:ready? true :blocking []})
                  merge/run-gh-command github
                  merge/read-repository
                  (constantly (dag/ok {:pr/repo "miniforge-ai/miniforge"}))
                  grant/register! register
                  merge/merge-pr! (fn [& _] (swap! merge-calls inc) (dag/ok {}))]
      {:result (merge/attempt-merge "/tmp" 7 merge/default-merge-policy context)
       :records (fx/list-records (:effect-store-dir context))
       :merge-calls @merge-calls})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} unregistered-grant-refuses-merge-test
  (testing "a grant issuance returned but the registry never stored"
    (let [{:keys [result merge-calls]} (attempt-with-registration
                                        (fn [_dir issued] issued))]
      (is (= :merge-commit-refused (get-in result [:error :code])))
      (is (zero? merge-calls)))))

(deftest ^{:stratum 2} revoked-grant-refuses-merge-test
  (testing "a grant revoked in the registry between issue and commit"
    (let [register grant/register!
          {:keys [result records merge-calls]}
          (attempt-with-registration
           (fn [dir issued]
             (let [registered (register dir issued)]
               (grant/revoke-stored! dir (:grant/id issued)
                                     :revocation/operator (Instant/now))
               registered)))]
      (is (= :merge-commit-refused (get-in result [:error :code]))
          "a refused recheck is not reported as a failed gh command")
      (is (= [:failed] (mapv :effect/state records))
          "the refused commit is recorded, not silently dropped")
      (is (zero? merge-calls)))))
