;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.pr-lifecycle.merge-effect-id-test
  "A merge attempt's effect id is fixed by run and target, so a second
   attempt in the same run settles the first instead of merging again."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.pr-lifecycle.merge-readiness :as readiness]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} merge-context []
  (let [tmp #(.getCanonicalPath
              (.toFile (Files/createTempDirectory % (into-array FileAttribute []))))]
    {:run-id (random-uuid)
     :pr-id 9
     :effect-store-dir (tmp "attempt-effects")
     :grant-breach-dir (tmp "attempt-grants")}))

(defn- ^{:stratum 0} github
  "Read-only gh answers: the head, and the PR state `state-json` gives."
  [state-json]
  (fn [args _worktree]
    (if (some #{"headRefOid"} args)
      (dag/ok {:output "0123456789abcdef0123456789abcdef01234567"})
      (dag/ok {:output state-json}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} attempt!
  "Attempt PR 9 in `context`; `merge-result` is what `gh pr merge` returns,
   and `readiness` what the readiness checks report."
  [context merges state-json merge-result & {:keys [readiness]
                                             :or {readiness {:ready? true :blocking []}}}]
  (with-redefs [readiness/evaluate (constantly readiness)
                merge/run-gh-command (github state-json)
                merge/read-repository (constantly (dag/ok {:pr/repo "miniforge-ai/miniforge"}))
                merge/merge-pr! (fn [& _] (swap! merges inc) merge-result)]
    (merge/attempt-merge "/tmp" 9 merge/default-merge-policy context)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} an-in-flight-merge-is-settled-not-repeated-test
  (let [context (merge-context)
        merges (atom 0)
        open "{\"state\":\"OPEN\",\"mergeCommit\":null}"]
    (attempt! context merges open (dag/ok {}))
    (testing "a second attempt in the same run asks GitHub, it does not merge again"
      (let [result (attempt! context merges
                             "{\"state\":\"MERGED\",\"mergeCommit\":{\"oid\":\"squash\"}}"
                             (dag/ok {}))]
        (is (= 1 @merges))
        (is (= "squash" (get-in result [:data :merge/sha])))
        (is (= 1 (count (fx/list-records (:effect-store-dir context)))))))))

(deftest ^{:stratum 2} a-failed-merge-is-retried-under-the-next-id-test
  (let [context (merge-context)
        merges (atom 0)
        open "{\"state\":\"OPEN\",\"mergeCommit\":null}"]
    (attempt! context merges open (dag/err :gh-command-failed "not mergeable"))
    (attempt! context merges open (dag/ok {}))
    (is (= 2 @merges))
    (is (= #{:failed :unknown-outcome}
           (set (map :effect/state (fx/list-records (:effect-store-dir context))))))))

(deftest ^{:stratum 2} an-open-pr-stays-reconcilable-until-github-settles-it-test
  (let [context (merge-context)
        merges (atom 0)
        open "{\"state\":\"OPEN\",\"mergeCommit\":null}"
        merged "{\"state\":\"MERGED\",\"mergeCommit\":{\"oid\":\"squash\"}}"]
    (attempt! context merges open (dag/ok {}))
    (attempt! context merges open (dag/ok {}))
    (let [result (attempt! context merges merged (dag/ok {}))]
      (is (= 1 @merges) "OPEN, OPEN, MERGED: gh pr merge ran once")
      (is (= "squash" (get-in result [:data :merge/sha]))))))

(deftest ^{:stratum 2} a-completed-merge-is-reported-even-when-no-longer-ready-test
  (let [context (merge-context)
        merges (atom 0)]
    (attempt! context merges "{\"state\":\"OPEN\",\"mergeCommit\":null}" (dag/ok {}))
    (let [result (attempt! context merges
                           "{\"state\":\"MERGED\",\"mergeCommit\":{\"oid\":\"squash\"}}"
                           (dag/ok {})
                           :readiness {:ready? false :blocking [:branch-not-up-to-date]})]
      (is (= "squash" (get-in result [:data :merge/sha])))
      (is (= 1 @merges)))))
