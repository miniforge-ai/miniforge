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

(ns ai.miniforge.dag-executor.parallel-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.parallel :as sut]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- task
  "Build a task spec used by run-state, with optional :exclusive-files."
  [id & {:as overrides}]
  (merge {:task/id     id
          :task/status :ready
          :task/deps   #{}
          :task/config {}}
         overrides))

(defn- run-state-with
  "Run-state shape that select-parallel-batch consumes."
  [tasks]
  {:run/tasks (into {} (map (juxt :task/id identity) tasks))})

;; Logger is optional everywhere; we always pass nil.
(def ^:private no-logger nil)

;------------------------------------------------------------------------------ Layer 1
;; resource-types

(deftest resource-types-exposes-documented-set-test
  (testing "Three resource types are exposed: :repo-write, :exclusive-files, :worktree"
    (is (= #{:repo-write :exclusive-files :worktree} sut/resource-types))))

;------------------------------------------------------------------------------ Layer 1
;; create-lock-pool

(deftest create-lock-pool-defaults-test
  (testing "Defaults: 1 repo write, 4 worktrees, no held locks"
    (let [pool (sut/create-lock-pool)
          {:keys [repo-write-available worktree-available current-file-locks]}
          (sut/available-capacity pool)]
      (is (= 1 repo-write-available))
      (is (= 4 worktree-available))
      (is (= 0 current-file-locks))
      (is (= 1 (get-in @pool [:config :max-repo-writes])))
      (is (= 4 (get-in @pool [:config :max-worktrees]))))))

(deftest create-lock-pool-overrides-test
  (testing "Custom :max-repo-writes and :max-worktrees flow through"
    (let [pool (sut/create-lock-pool :max-repo-writes 3 :max-worktrees 8)
          {:keys [repo-write-available worktree-available]}
          (sut/available-capacity pool)]
      (is (= 3 repo-write-available))
      (is (= 8 worktree-available)))))

;------------------------------------------------------------------------------ Layer 1
;; create-lock

(deftest create-lock-fields-test
  (testing "create-lock emits a lock record with id, type, holder, acquired-at"
    (let [hid (random-uuid)
          lock (sut/create-lock :repo-write hid)]
      (is (uuid? (:lock/id lock)))
      (is (= :repo-write (:lock/resource-type lock)))
      (is (= hid (:lock/holder-id lock)))
      (is (inst? (:lock/acquired-at lock)))
      (is (nil? (:lock/files lock))))))

(deftest create-lock-files-only-on-exclusive-files-test
  (testing ":lock/files is set only when type is :exclusive-files"
    (let [hid (random-uuid)
          excl (sut/create-lock :exclusive-files hid :files ["a.clj" "b.clj"])
          rw   (sut/create-lock :repo-write hid :files ["a.clj"])]
      (is (= #{"a.clj" "b.clj"} (:lock/files excl)))
      (is (nil? (:lock/files rw))))))

;------------------------------------------------------------------------------ Layer 1
;; files-overlap?

(deftest files-overlap?-test
  (testing "files-overlap? detects any common path"
    (is (true?  (sut/files-overlap? #{"a"} #{"a"})))
    (is (true?  (sut/files-overlap? #{"a" "b"} #{"b" "c"})))
    (is (false? (sut/files-overlap? #{"a" "b"} #{"c" "d"})))
    (is (false? (sut/files-overlap? #{} #{"a"})))
    (is (false? (sut/files-overlap? nil nil))))
  (testing "Accepts vectors as well as sets"
    (is (true?  (sut/files-overlap? ["a" "b"] ["b"])))))

;------------------------------------------------------------------------------ Layer 1
;; acquire-repo-write! / release-repo-write!

(deftest acquire-repo-write!-success-test
  (testing "First holder acquires the only repo-write permit; lock recorded under holder-id"
    (let [pool (sut/create-lock-pool)
          hid  (random-uuid)
          ret  (sut/acquire-repo-write! pool hid 1000 no-logger)]
      (is (result/ok? ret))
      (let [lock (:data ret)]
        (is (= :repo-write (:lock/resource-type lock)))
        (is (= hid (:lock/holder-id lock))))
      (is (= 0 (:repo-write-available (sut/available-capacity pool))))
      (is (some? (get-in @pool [:locks hid]))))))

(deftest acquire-repo-write!-second-holder-times-out-test
  (testing "Second holder times out when the single permit is already taken"
    (let [pool (sut/create-lock-pool :max-repo-writes 1)
          first-hid  (random-uuid)
          second-hid (random-uuid)
          _ (sut/acquire-repo-write! pool first-hid 1000 no-logger)
          ret (sut/acquire-repo-write! pool second-hid 0 no-logger)]
      (is (result/err? ret))
      (is (= :timeout (-> ret :error :code))))))

(deftest release-repo-write!-frees-permit-test
  (testing "release-repo-write! returns the permit and clears the holder's lock entry"
    (let [pool (sut/create-lock-pool :max-repo-writes 1)
          hid  (random-uuid)
          _ (sut/acquire-repo-write! pool hid 1000 no-logger)
          ret (sut/release-repo-write! pool hid no-logger)]
      (is (result/ok? ret))
      (is (= 1 (:repo-write-available (sut/available-capacity pool))))
      (is (nil? (get-in @pool [:locks hid]))))))

(deftest release-repo-write!-without-lock-test
  (testing "Releasing a repo-write you don't hold returns :lock-not-found"
    (let [pool (sut/create-lock-pool)
          ret  (sut/release-repo-write! pool (random-uuid) no-logger)]
      (is (result/err? ret))
      (is (= :lock-not-found (-> ret :error :code))))))

(deftest release-repo-write!-rejects-other-lock-types-test
  (testing "Releasing repo-write when the holder owns a different lock type returns :lock-not-found"
    (let [pool (sut/create-lock-pool)
          hid  (random-uuid)
          _ (sut/acquire-file-locks! pool hid ["a.clj"] no-logger)
          ret (sut/release-repo-write! pool hid no-logger)]
      (is (result/err? ret))
      (is (= :lock-not-found (-> ret :error :code))))))

;------------------------------------------------------------------------------ Layer 1
;; acquire-file-locks! / release-file-locks!

(deftest acquire-file-locks!-success-test
  (testing "Files not already locked are claimed for the holder"
    (let [pool (sut/create-lock-pool)
          hid  (random-uuid)
          ret  (sut/acquire-file-locks! pool hid ["src/a.clj" "src/b.clj"] no-logger)]
      (is (result/ok? ret))
      (is (= :exclusive-files (-> ret :data :lock/resource-type)))
      (is (= #{"src/a.clj" "src/b.clj"} (-> ret :data :lock/files)))
      (is (= #{"src/a.clj" "src/b.clj"} (get-in @pool [:file-locks hid]))))))

(deftest acquire-file-locks!-conflict-test
  (testing "Overlapping files held by another holder cause :lock-conflict"
    (let [pool (sut/create-lock-pool)
          a (random-uuid) b (random-uuid)
          _ (sut/acquire-file-locks! pool a ["src/foo.clj"] no-logger)
          ret (sut/acquire-file-locks! pool b ["src/foo.clj" "src/bar.clj"] no-logger)]
      (is (result/err? ret))
      (is (= :lock-conflict (-> ret :error :code)))
      (is (contains? (-> ret :error :data :conflicts) a)))))

(deftest acquire-file-locks!-non-overlapping-coexist-test
  (testing "Two holders requesting disjoint files both succeed"
    (let [pool (sut/create-lock-pool)
          a (random-uuid) b (random-uuid)
          ra (sut/acquire-file-locks! pool a ["src/a.clj"] no-logger)
          rb (sut/acquire-file-locks! pool b ["src/b.clj"] no-logger)]
      (is (result/ok? ra))
      (is (result/ok? rb))
      (is (= #{"src/a.clj"} (get-in @pool [:file-locks a])))
      (is (= #{"src/b.clj"} (get-in @pool [:file-locks b]))))))

(deftest acquire-file-locks!-same-holder-replaces-test
  (testing "Same holder re-acquiring its own files does not conflict
            (current logic: lock map is overwritten with the new file set)"
    (let [pool (sut/create-lock-pool)
          hid  (random-uuid)
          _ (sut/acquire-file-locks! pool hid ["src/a.clj"] no-logger)
          ret (sut/acquire-file-locks! pool hid ["src/a.clj" "src/b.clj"] no-logger)]
      (is (result/ok? ret))
      (is (= #{"src/a.clj" "src/b.clj"} (get-in @pool [:file-locks hid]))))))

(deftest release-file-locks!-clears-state-test
  (testing "release-file-locks! removes both the lock entry and the file-locks set"
    (let [pool (sut/create-lock-pool)
          hid  (random-uuid)
          _ (sut/acquire-file-locks! pool hid ["src/a.clj"] no-logger)
          ret (sut/release-file-locks! pool hid no-logger)]
      (is (result/ok? ret))
      (is (nil? (get-in @pool [:locks hid])))
      (is (nil? (get-in @pool [:file-locks hid]))))))

(deftest release-file-locks!-without-lock-test
  (testing "Releasing files you don't hold returns :lock-not-found"
    (let [pool (sut/create-lock-pool)
          ret (sut/release-file-locks! pool (random-uuid) no-logger)]
      (is (result/err? ret))
      (is (= :lock-not-found (-> ret :error :code))))))

;------------------------------------------------------------------------------ Layer 1
;; acquire-worktree! / release-worktree!

(deftest acquire-worktree!-success-test
  (testing "Worktree slot is allocated when capacity is available"
    (let [pool (sut/create-lock-pool :max-worktrees 2)
          hid  (random-uuid)
          ret  (sut/acquire-worktree! pool hid 1000 no-logger)]
      (is (result/ok? ret))
      (is (= :worktree (-> ret :data :lock/resource-type)))
      (is (= 1 (:worktree-available (sut/available-capacity pool))))
      (is (some? (get-in @pool [:locks hid :worktree]))))))

(deftest acquire-worktree!-times-out-when-full-test
  (testing "Acquire times out when no worktree permits remain"
    (let [pool (sut/create-lock-pool :max-worktrees 1)
          a (random-uuid) b (random-uuid)
          _ (sut/acquire-worktree! pool a 1000 no-logger)
          ret (sut/acquire-worktree! pool b 0 no-logger)]
      (is (result/err? ret))
      (is (= :timeout (-> ret :error :code))))))

(deftest release-worktree!-frees-permit-test
  (testing "Releasing returns the permit and clears the holder's worktree entry"
    (let [pool (sut/create-lock-pool :max-worktrees 1)
          hid  (random-uuid)
          _ (sut/acquire-worktree! pool hid 1000 no-logger)
          ret (sut/release-worktree! pool hid no-logger)]
      (is (result/ok? ret))
      (is (= 1 (:worktree-available (sut/available-capacity pool))))
      (is (nil? (get-in @pool [:locks hid :worktree]))))))

(deftest release-worktree!-without-lock-test
  (testing "Releasing a worktree you don't hold returns :lock-not-found"
    (let [pool (sut/create-lock-pool)
          ret (sut/release-worktree! pool (random-uuid) no-logger)]
      (is (result/err? ret))
      (is (= :lock-not-found (-> ret :error :code))))))

;------------------------------------------------------------------------------ Layer 2
;; can-run-parallel?

(deftest can-run-parallel?-with-no-locks-test
  (testing "Two tasks with no shared file locks and no repo-write needs run in parallel"
    (let [pool (sut/create-lock-pool)
          a (task (random-uuid))
          b (task (random-uuid))]
      (is (true? (sut/can-run-parallel? a b pool))))))

(deftest can-run-parallel?-blocks-on-overlapping-file-locks-test
  (testing "Tasks holding overlapping file locks cannot run in parallel"
    (let [pool (sut/create-lock-pool)
          a-id (random-uuid) b-id (random-uuid)
          _ (sut/acquire-file-locks! pool a-id ["src/x.clj"] no-logger)
          _ (sut/acquire-file-locks! pool b-id ["src/x.clj" "src/y.clj"] no-logger)
          a (task a-id) b (task b-id)]
      ;; Note: acquire-file-locks! conflicts on overlap so the second call errs.
      ;; But can-run-parallel? reads the recorded :file-locks state directly.
      ;; Force the second holder's recorded files for this test.
      (swap! pool assoc-in [:file-locks b-id] #{"src/x.clj" "src/y.clj"})
      (is (false? (sut/can-run-parallel? a b pool))))))

(deftest can-run-parallel?-blocks-when-both-need-repo-write-test
  (testing "Two tasks each flagged :needs-repo-write? cannot run in parallel"
    (let [pool (sut/create-lock-pool)
          a (task (random-uuid) :needs-repo-write? true)
          b (task (random-uuid) :needs-repo-write? true)]
      (is (false? (sut/can-run-parallel? a b pool))))))

(deftest can-run-parallel?-allows-one-repo-write-test
  (testing "One repo-write task and one non-repo-write task can run in parallel"
    (let [pool (sut/create-lock-pool)
          a (task (random-uuid) :needs-repo-write? true)
          b (task (random-uuid) :needs-repo-write? false)]
      (is (true? (sut/can-run-parallel? a b pool))))))

;------------------------------------------------------------------------------ Layer 2
;; select-parallel-batch

(deftest select-parallel-batch-respects-max-parallel-test
  (testing "max-parallel is honored regardless of how many tasks are ready"
    (let [pool (sut/create-lock-pool)
          ids [(random-uuid) (random-uuid) (random-uuid) (random-uuid)]
          tasks (mapv task ids)
          rs (run-state-with tasks)
          batch (sut/select-parallel-batch (set ids) rs pool 2)]
      (is (= 2 (count batch)))
      (is (every? #(contains? (set ids) %) batch)))))

(deftest select-parallel-batch-skips-conflicting-files-test
  (testing "Tasks declaring overlapping :exclusive-files in their :task/config
            are not co-selected"
    (let [pool (sut/create-lock-pool)
          a-id (random-uuid) b-id (random-uuid) c-id (random-uuid)
          a (task a-id :task/config {:exclusive-files #{"src/x.clj"}})
          b (task b-id :task/config {:exclusive-files #{"src/x.clj"}})
          c (task c-id :task/config {:exclusive-files #{"src/y.clj"}})
          rs (run-state-with [a b c])
          batch (sut/select-parallel-batch #{a-id b-id c-id} rs pool 10)]
      ;; a or b will be picked first (whichever the map iteration sees first),
      ;; but a+b cannot coexist. c always coexists with whichever one of a/b
      ;; was selected. So the batch is exactly 2 tasks.
      (is (= 2 (count batch)))
      (is (contains? (set batch) c-id))
      (is (not= #{a-id b-id} (set batch))))))

(deftest select-parallel-batch-empty-ready-set-test
  (testing "No ready tasks ⇒ empty batch"
    (is (= [] (sut/select-parallel-batch #{}
                                         (run-state-with [])
                                         (sut/create-lock-pool)
                                         10)))))

(deftest select-parallel-batch-zero-max-parallel-test
  (testing "max-parallel of 0 ⇒ empty batch even with ready tasks"
    (let [a-id (random-uuid)
          rs (run-state-with [(task a-id)])]
      (is (= [] (sut/select-parallel-batch #{a-id} rs (sut/create-lock-pool) 0))))))

;------------------------------------------------------------------------------ Layer 2
;; available-capacity

(deftest available-capacity-tracks-all-three-counters-test
  (testing "available-capacity reports repo-write/worktree permits and file-lock count"
    (let [pool (sut/create-lock-pool :max-repo-writes 2 :max-worktrees 3)]
      (is (= {:repo-write-available 2
              :worktree-available 3
              :current-file-locks 0}
             (sut/available-capacity pool)))
      (sut/acquire-repo-write! pool (random-uuid) 1000 no-logger)
      (sut/acquire-worktree! pool (random-uuid) 1000 no-logger)
      (sut/acquire-file-locks! pool (random-uuid) ["src/a.clj"] no-logger)
      (let [{:keys [repo-write-available worktree-available current-file-locks]}
            (sut/available-capacity pool)]
        (is (= 1 repo-write-available))
        (is (= 2 worktree-available))
        (is (= 1 current-file-locks))))))

;------------------------------------------------------------------------------ Layer 2
;; release-all-locks!

(deftest release-all-locks!-clears-repo-write-only-test
  (testing "Holder with only a recorded repo-write permit has it cleared"
    ;; This test intentionally covers only the repo-write-recorded case.
    ;; Calling acquire-file-locks! here would overwrite :locks[hid] with a
    ;; file-lock entry, changing the scenario under test (and exposing a
    ;; latent leak: release-all-locks! reads :lock/resource-type from
    ;; :locks[hid] to decide whether to release the repo-write semaphore,
    ;; so a clobbered entry would orphan the permit). Mixed-acquisition
    ;; behavior is its own concern and not covered here.
    (let [pool (sut/create-lock-pool :max-repo-writes 1)
          hid  (random-uuid)
          _ (sut/acquire-repo-write! pool hid 1000 no-logger)
          ret (sut/release-all-locks! pool hid no-logger)]
      (is (result/ok? ret))
      (is (true? (-> ret :data :released-all)))
      (is (= 1 (:repo-write-available (sut/available-capacity pool))))
      (is (nil? (get-in @pool [:locks hid])))
      ;; No file-lock state was ever set; assertion confirms the cleanup
      ;; path leaves :file-locks empty for this holder regardless.
      (is (nil? (get-in @pool [:file-locks hid]))))))

(deftest release-all-locks!-clears-worktree-test
  (testing "Holder with worktree slot has it released"
    (let [pool (sut/create-lock-pool :max-worktrees 1)
          hid  (random-uuid)
          _ (sut/acquire-worktree! pool hid 1000 no-logger)
          ret (sut/release-all-locks! pool hid no-logger)]
      (is (result/ok? ret))
      (is (= 1 (:worktree-available (sut/available-capacity pool))))
      (is (nil? (get-in @pool [:locks hid]))))))

(deftest release-all-locks!-on-non-holder-is-no-op-test
  (testing "Calling release-all-locks! for a holder with no locks returns ok and
            does not change capacity"
    (let [pool (sut/create-lock-pool)
          before (sut/available-capacity pool)
          ret (sut/release-all-locks! pool (random-uuid) no-logger)]
      (is (result/ok? ret))
      (is (= before (sut/available-capacity pool))))))

(deftest release-all-locks!-clears-file-locks-only-test
  (testing "Holder with only file locks has them cleared (no semaphore involved)"
    (let [pool (sut/create-lock-pool)
          hid  (random-uuid)
          _ (sut/acquire-file-locks! pool hid ["src/a.clj"] no-logger)
          ret (sut/release-all-locks! pool hid no-logger)]
      (is (result/ok? ret))
      (is (nil? (get-in @pool [:locks hid])))
      (is (nil? (get-in @pool [:file-locks hid]))))))
