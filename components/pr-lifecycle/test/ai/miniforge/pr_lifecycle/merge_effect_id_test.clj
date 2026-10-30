;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.pr-lifecycle.merge-effect-id-test
  "Merge attempts take fixed, repeatable effect ids, and GitHub's state,
   not an earlier attempt's record, says whether the merge happened."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.pr-lifecycle.merge-readiness :as readiness]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} head-sha "0123456789abcdef0123456789abcdef01234567")

(defn- ^{:stratum 0} merge-context []
  (let [tmp #(.getCanonicalPath
              (.toFile (Files/createTempDirectory % (into-array FileAttribute []))))]
    {:run-id (random-uuid)
     :pr-id 9
     :effect-store-dir (tmp "attempt-effects")
     :grant-breach-dir (tmp "attempt-grants")}))

(defn- ^{:stratum 0} github
  "gh answers for a PR whose state is `@state`: OPEN, MERGED or CLOSED."
  [state]
  (fn [_args _worktree]
    (dag/ok {:output (json/generate-string
                      {:headRefOid "0123456789abcdef0123456789abcdef01234567"
                       :state @state
                       :mergeCommit (when (= "MERGED" @state) {:oid "squash"})})})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} attempt!
  "Attempt PR 9 in `context` while GitHub reports `state`; count merges."
  [context merges state & {:keys [readiness]
                           :or {readiness {:ready? true :blocking []}}}]
  (with-redefs [readiness/evaluate (constantly readiness)
                merge/run-gh-command (github state)
                merge/read-repository (constantly (dag/ok {:pr/repo "miniforge-ai/miniforge"}))
                merge/merge-pr! (fn [& _] (swap! merges inc) (dag/ok {}))]
    (merge/attempt-merge "/tmp" 9 merge/default-merge-policy context)))

(defn ^{:stratum 1} basis [context]
  [:effect/merge (:run-id context) "miniforge-ai/miniforge" 9 head-sha])

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-merged-pr-is-reported-not-merged-again-test
  (let [context (merge-context)
        merges (atom 0)
        state (atom "OPEN")]
    (attempt! context merges state)
    (reset! state "MERGED")
    (let [result (attempt! context merges state)]
      (is (= "squash" (get-in result [:data :merge/sha])))
      (is (= 1 @merges) "GitHub reports the merge; gh pr merge does not run again"))))

(deftest ^{:stratum 2} a-closed-pr-is-reported-not-merged-test
  (let [merges (atom 0)
        result (attempt! (merge-context) merges (atom "CLOSED"))]
    (is (= :merge-closed-unmerged (get-in result [:error :code])))
    (is (zero? @merges))))

(deftest ^{:stratum 2} a-merged-pr-is-reported-when-no-longer-ready-test
  (let [merges (atom 0)
        result (attempt! (merge-context) merges (atom "MERGED")
                         :readiness {:ready? false :blocking [:branch-not-up-to-date]})]
    (is (= "squash" (get-in result [:data :merge/sha])))
    (is (zero? @merges))))

(deftest ^{:stratum 2} attempts-take-repeatable-ids-test
  (let [context (merge-context)
        merges (atom 0)
        state (atom "OPEN")]
    (attempt! context merges state)
    (attempt! context merges state)
    (is (= 2 @merges) "an open PR is merged again; GitHub refuses a second merge")
    (is (= #{(fx/attempt-id (basis context) 0) (fx/attempt-id (basis context) 1)}
           (set (map :effect/id (fx/list-records (:effect-store-dir context))))))))
