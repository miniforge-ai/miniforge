;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.environment-ownership-test
  (:require [ai.miniforge.workflow.environment-ownership-fixtures :as fixture]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} missing-worktree-prevents-capsule-acquisition
  (doseq [[options calls] [[{:missing-worktree? true} []]
                           [{:acquire {:worktree :refused}} [[:acquire :worktree]]]]]
    (let [result (fixture/execute options)]
      (is (nil? (:result result)))
      (is (= calls (:calls result))))))

(deftest ^{:stratum 0} refused-capsule-releases-worktree-once
  (let [result (fixture/execute {:acquire {:capsule :refused}})]
    (is (nil? (:result result)))
    (is (= [[:acquire :worktree] [:acquire :capsule] [:release :worktree :worktree]]
           (:calls result)))))

(deftest ^{:stratum 0} thrown-capsule-failure-preserves-cause-and-releases-worktree
  (doseq [failure [(Exception. "fixture acquisition failure")
                   (Error. "fixture fatal acquisition")
                   (InterruptedException. "fixture interrupted acquisition")]]
    (let [result (fixture/execute {:acquire {:capsule failure}})]
      (is (identical? failure (:result result)))
      (is (= [[:acquire :worktree] [:acquire :capsule] [:release :worktree :worktree]]
             (:calls result)))
      (is (= (instance? InterruptedException failure) (:interrupted? result))))))

(deftest ^{:stratum 0} record-construction-failure-releases-every-acquired-resource
  (doseq [[workdir calls]
          [["worktree" [[:acquire :worktree] [:release :worktree :worktree]]]
           ["capsule" [[:acquire :worktree] [:acquire :capsule]
                       [:release :capsule :capsule] [:release :worktree :worktree]]]]]
    (let [failure (InterruptedException. "fixture revision lookup interruption")
          result (fixture/execute {:describe workdir :failure failure})]
      (is (identical? failure (:result result)))
      (is (= calls (:calls result)))
      (is (:interrupted? result)))))

(deftest ^{:stratum 0} fatal-rollback-retains-the-original-acquisition-failure
  (let [primary (Exception. "fixture acquisition failure")
        fatal (Error. "fixture rollback failure")
        result (fixture/execute {:acquire {:capsule primary} :release fatal})]
    (is (identical? fatal (:result result)))
    (is (= [primary] (vec (.getSuppressed fatal))))))

(deftest ^{:stratum 0} successful-pair-transfers-both-resources-without-release
  (let [{:keys [result calls]} (fixture/execute {})]
    (is (= [[:acquire :worktree] [:acquire :capsule]] calls))
    (is (= :capsule (:executor result) (:environment-id result)))
    (is (= :worktree (:worktree-executor result) (:worktree-environment-id result)))
    (is (= "worktree" (:worktree-path result)))))

(comment ::partial-acquisition-rollback-contract)
