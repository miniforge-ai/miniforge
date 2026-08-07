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
(ns ai.miniforge.pr-lifecycle.merge-transaction-test
  "The claim this namespace exists to stop: enabling auto-merge is not
   merging, and a SHA read from the local worktree is not the merge
   commit."
  (:require
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.pr-lifecycle.merge-authority :as authority]
   [ai.miniforge.pr-lifecycle.merge-transaction :as mt]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} later (Instant/parse "2026-08-01T01:00:00Z"))

(defn ^{:stratum 0} ctx []
  {:run-id (random-uuid)
   :effect-store-dir
   (str (.toFile (Files/createTempDirectory "merge-fx" (into-array FileAttribute []))))
   :grant-breach-dir
   (str (.toFile (Files/createTempDirectory "merge-grants" (into-array FileAttribute []))))
   :pr/repo "miniforge-ai/miniforge"})

(defn ^{:stratum 0} gh-returning
  "An injected gh that answers every call with `body`."
  [body]
  (fn [_] {:ok? true :output body}))

(def ^{:stratum 0} merged-json
  "{\"state\":\"MERGED\",\"mergeCommit\":{\"oid\":\"squash-sha-on-base\"}}")

(def ^{:stratum 0} open-json
  "{\"state\":\"OPEN\",\"mergeCommit\":null}")

(defn ^{:stratum 0} ok-enable [] (fn [] {:ok? true}))

(deftest ^{:stratum 0} store-dir-is-never-relative-test
  ;; An audit trail written to whatever directory the process started in
  ;; is an audit trail nobody can find.
  (is (= "/tmp/x" (mt/store-dir {:effect-store-dir "/tmp/x"})))
  (is (.startsWith ^String (mt/store-dir {}) (System/getProperty "user.home"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} merge-case! []
  (let [context (ctx)
        prepared (authority/prepare context (random-uuid)
                                    (:pr/repo context) 42 :squash now)]
    {:context context
     :authority prepared
     :transaction (mt/propose! context prepared now)}))

(deftest ^{:stratum 1} parse-pr-view-test
  (is (= {:pr/state "MERGED" :merge/sha "squash-sha-on-base"}
         (mt/parse-pr-view merged-json)))
  (is (= {:pr/state "OPEN" :merge/sha nil} (mt/parse-pr-view open-json)))
  (testing "unparseable output is nil — 'did not answer', never 'not merged'"
    (is (nil? (mt/parse-pr-view "not json at all")))
    (is (nil? (mt/parse-pr-view "{\"unexpected\":true}")))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} enabling-auto-merge-is-not-merging-test
  ;; The core correction. `merge-pr!` passes --auto unconditionally, so a
  ;; zero exit means auto-merge was ENABLED. GitHub still says OPEN.
  (let [{:keys [context authority transaction]} (merge-case!)
        settled (mt/commit! context transaction (:authority/grant authority)
                            42 now (ok-enable) (gh-returning open-json))]
    (is (= :unknown-outcome (:effect/state settled))
        "a request in flight is not a success")
    (is (not= :succeeded (:effect/state settled)))
    (is (nil? (mt/substantiated-sha settled))
        "no SHA is published for a merge nobody has observed")
    (testing "the record is reconcilable, so a later pass asks again"
      (is (contains? fx/reconcilable-states (:effect/state settled))))))

(deftest ^{:stratum 2} observed-merge-carries-githubs-sha-test
  (let [{:keys [context authority transaction]} (merge-case!)
        settled (mt/commit! context transaction (:authority/grant authority)
                            42 now (ok-enable) (gh-returning merged-json))]
    (is (= :succeeded (:effect/state settled)))
    (is (= "squash-sha-on-base" (mt/substantiated-sha settled))
        "the published SHA is GitHub's mergeCommit, not a local branch tip")))

(deftest ^{:stratum 2} a-failed-gh-call-is-a-definite-failure-test
  (let [{:keys [context authority transaction]} (merge-case!)
        settled (mt/commit! context transaction (:authority/grant authority) 42 now
                            (fn [] {:ok? false :error "PR already closed"})
                            (gh-returning merged-json))]
    (is (= :failed (:effect/state settled))
        "the gh call reported it did not happen — that is knowledge, not doubt")
    (is (nil? (mt/substantiated-sha settled)))))

(deftest ^{:stratum 2} silent-github-leaves-it-unknown-test
  (let [{:keys [context authority transaction]} (merge-case!)
        settled (mt/commit! context transaction (:authority/grant authority)
                            42 now (ok-enable)
                            (fn [_] {:ok? false :output nil}))]
    (is (= :unknown-outcome (:effect/state settled))
        "GitHub not answering is not GitHub saying no")
    (is (nil? (mt/substantiated-sha settled)))))

(deftest ^{:stratum 2} the-intent-is-on-disk-before-any-gh-command-test
  (let [{:keys [context authority transaction]} (merge-case!)]
    (is (= :proposed (:effect/state transaction)))
    (is (= :effect/merge (:effect/class transaction)))
    (is (= 42 (get-in transaction [:effect/proposal :pr/number])))
    (is (= (get-in authority [:authority/grant :grant/id])
           (:effect/grant-id transaction)))
    (is (= (get-in authority [:authority/envelope :envelope/id])
           (:effect/envelope-id transaction)))
    (testing "a reader sharing no memory with the writer finds it"
      (is (= (:effect/id transaction)
             (:effect/id (fx/read-record (:effect-store-dir context)
                                         (:effect/id transaction))))))))

(deftest ^{:stratum 2} reconcile-settles-a-later-merge-test
  (testing "asking again after GitHub finishes settles it with the real SHA"
    (let [{:keys [context authority transaction]} (merge-case!)
          pending (mt/commit! context transaction (:authority/grant authority)
                              42 now (ok-enable) (gh-returning open-json))]
      (is (nil? (mt/substantiated-sha pending)))
      (let [settled (mt/reconcile! context pending 42 later (gh-returning merged-json))]
        (is (= :reconciled (:effect/state settled)))
        (is (= "squash-sha-on-base" (mt/substantiated-sha settled))))))
  (testing "a PR that closed unmerged reconciles as a mismatch, not a merge"
    (let [{:keys [context authority transaction]} (merge-case!)
          pending (mt/commit! context transaction (:authority/grant authority)
                              42 now (ok-enable) (gh-returning open-json))
          settled (mt/reconcile! context pending 42 later
                                 (gh-returning "{\"state\":\"CLOSED\",\"mergeCommit\":null}"))]
      (is (= :reconciled (:effect/state settled)))
      (is (false? (:effect/matched? settled)))
      (is (nil? (mt/substantiated-sha settled))))))
