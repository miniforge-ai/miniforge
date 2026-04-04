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

(ns ai.miniforge.pr-lifecycle.events-test
  "Unit tests for the PR lifecycle event system.

   Tests event constructors, event bus pub/sub, and event queries."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.pr-lifecycle.events :as events]))

;------------------------------------------------------------------------------ Event Types

(deftest event-types-test
  (testing "All expected event types are defined"
    (is (= 11 (count events/event-types))
        "Should have exactly 11 event types")
    (are [type] (contains? events/event-types type)
      :pr/opened
      :pr/ci-passed
      :pr/ci-failed
      :pr/review-approved
      :pr/review-changes-requested
      :pr/comment-actionable
      :pr/merged
      :pr/closed
      :pr/rebase-needed
      :pr/conflict
      :pr/fix-pushed)))

;------------------------------------------------------------------------------ Event Constructors

(def dag-id (random-uuid))
(def run-id (random-uuid))
(def task-id (random-uuid))

(deftest create-event-test
  (testing "create-event produces well-formed event with required fields"
    (let [event (events/create-event :pr/opened {:dag/id dag-id :task/id task-id})]
      (is (uuid? (:event/id event))
          "Event should have a UUID id")
      (is (= :pr/opened (:event/type event))
          "Event type should match")
      (is (inst? (:event/timestamp event))
          "Event should have a timestamp")
      (is (= dag-id (:dag/id event))
          "Additional data should be merged in")))

  (testing "create-event merges arbitrary data"
    (let [event (events/create-event :pr/ci-failed {:custom "data" :count 42})]
      (is (= "data" (:custom event)))
      (is (= 42 (:count event))))))

(deftest pr-opened-constructor-test
  (testing "pr-opened creates event with all PR fields"
    (let [event (events/pr-opened dag-id run-id task-id 123
                                   "https://example.com/pr/123"
                                   "feat/foo" "abc123")]
      (is (= :pr/opened (:event/type event)))
      (is (= dag-id (:dag/id event)))
      (is (= run-id (:run/id event)))
      (is (= task-id (:task/id event)))
      (is (= 123 (:pr/id event)))
      (is (= "https://example.com/pr/123" (:pr/url event)))
      (is (= "feat/foo" (:pr/branch event)))
      (is (= "abc123" (:pr/sha event))))))

(deftest ci-passed-constructor-test
  (testing "ci-passed creates event with SHA"
    (let [event (events/ci-passed dag-id run-id task-id 123 "abc123")]
      (is (= :pr/ci-passed (:event/type event)))
      (is (= 123 (:pr/id event)))
      (is (= "abc123" (:pr/sha event))))))

(deftest ci-failed-constructor-test
  (testing "ci-failed creates event with logs"
    (let [event (events/ci-failed dag-id run-id task-id 123 "abc123" "FAIL in test-foo")]
      (is (= :pr/ci-failed (:event/type event)))
      (is (= "FAIL in test-foo" (:ci/logs event))))))

(deftest review-approved-constructor-test
  (testing "review-approved creates event with approvers"
    (let [event (events/review-approved dag-id run-id task-id 123 ["alice" "bob"])]
      (is (= :pr/review-approved (:event/type event)))
      (is (= ["alice" "bob"] (:review/approvers event))))))

(deftest review-changes-requested-constructor-test
  (testing "review-changes-requested creates event with comments"
    (let [comments [{:body "Fix this" :path "src/foo.clj"}]
          event (events/review-changes-requested dag-id run-id task-id 123 comments)]
      (is (= :pr/review-changes-requested (:event/type event)))
      (is (= comments (:review/comments event))))))

(deftest comment-actionable-constructor-test
  (testing "comment-actionable creates event with comment data"
    (let [comment-data {:body "Please add tests" :author "reviewer"}
          event (events/comment-actionable dag-id run-id task-id 123 comment-data)]
      (is (= :pr/comment-actionable (:event/type event)))
      (is (= comment-data (:comment event))))))

(deftest merged-constructor-test
  (testing "merged creates event with merge SHA"
    (let [event (events/merged dag-id run-id task-id 123 "merge-sha-abc")]
      (is (= :pr/merged (:event/type event)))
      (is (= "merge-sha-abc" (:pr/merge-sha event))))))

(deftest closed-constructor-test
  (testing "closed creates event with reason"
    (let [event (events/closed dag-id run-id task-id 123 "superseded")]
      (is (= :pr/closed (:event/type event)))
      (is (= "superseded" (:close/reason event))))))

(deftest rebase-needed-constructor-test
  (testing "rebase-needed creates event with base SHA"
    (let [event (events/rebase-needed dag-id run-id task-id 123 "base-sha")]
      (is (= :pr/rebase-needed (:event/type event)))
      (is (= "base-sha" (:pr/base-sha event))))))

(deftest conflict-constructor-test
  (testing "conflict creates event with conflicting files"
    (let [files ["src/a.clj" "src/b.clj"]
          event (events/conflict dag-id run-id task-id 123 files)]
      (is (= :pr/conflict (:event/type event)))
      (is (= files (:conflict/files event))))))

(deftest fix-pushed-constructor-test
  (testing "fix-pushed creates event with SHA and fix type"
    (let [event (events/fix-pushed dag-id run-id task-id 123 "fix-sha" :ci-fix)]
      (is (= :pr/fix-pushed (:event/type event)))
      (is (= "fix-sha" (:pr/sha event)))
      (is (= :ci-fix (:fix/type event))))))

;------------------------------------------------------------------------------ Event Bus

(deftest create-event-bus-test
  (testing "create-event-bus initializes with empty state"
    (let [bus (events/create-event-bus)]
      (is (= [] (:events @bus)))
      (is (= {} (:subscribers @bus)))
      (is (= {} (:filters @bus))))))

(deftest publish-test
  (testing "publish! adds event to bus log"
    (let [bus (events/create-event-bus)
          event (events/ci-passed dag-id run-id task-id 123 "sha")]
      (events/publish! bus event nil)
      (is (= 1 (count (:events @bus))))
      (is (= :pr/ci-passed (:event/type (first (:events @bus)))))))

  (testing "publish! notifies matching subscribers"
    (let [bus (events/create-event-bus)
          received (atom [])
          event (events/ci-passed dag-id run-id task-id 123 "sha")]
      (events/subscribe! bus :test-sub
                          (fn [e] (swap! received conj e)))
      (events/publish! bus event nil)
      (is (= 1 (count @received)))
      (is (= :pr/ci-passed (:event/type (first @received))))))

  (testing "publish! respects subscriber filters"
    (let [bus (events/create-event-bus)
          received (atom [])
          ci-event (events/ci-passed dag-id run-id task-id 123 "sha")
          review-event (events/review-approved dag-id run-id task-id 123 ["alice"])]
      (events/subscribe! bus :ci-only
                          (fn [e] (swap! received conj e))
                          (fn [e] (= :pr/ci-passed (:event/type e))))
      (events/publish! bus ci-event nil)
      (events/publish! bus review-event nil)
      (is (= 1 (count @received))
          "Only CI event should be delivered")
      (is (= :pr/ci-passed (:event/type (first @received)))))))

(deftest subscribe-test
  (testing "subscribe! returns subscriber-id"
    (let [bus (events/create-event-bus)
          id (events/subscribe! bus :my-sub identity)]
      (is (= :my-sub id))))

  (testing "subscribe! with default filter accepts all events"
    (let [bus (events/create-event-bus)
          received (atom 0)]
      (events/subscribe! bus :counter (fn [_] (swap! received inc)))
      (events/publish! bus (events/ci-passed dag-id run-id task-id 1 "a") nil)
      (events/publish! bus (events/merged dag-id run-id task-id 1 "b") nil)
      (is (= 2 @received)))))

(deftest unsubscribe-test
  (testing "unsubscribe! removes subscriber"
    (let [bus (events/create-event-bus)
          received (atom 0)]
      (events/subscribe! bus :temp (fn [_] (swap! received inc)))
      (events/publish! bus (events/ci-passed dag-id run-id task-id 1 "a") nil)
      (is (= 1 @received))
      (events/unsubscribe! bus :temp)
      (events/publish! bus (events/ci-passed dag-id run-id task-id 1 "b") nil)
      (is (= 1 @received)
          "Should not receive events after unsubscribe"))))

(deftest publish-callback-error-handling-test
  (testing "publish! continues and event is logged even if subscriber throws"
    (let [bus (events/create-event-bus)]
      (events/subscribe! bus :thrower (fn [_] (throw (Exception. "boom"))))
      (events/publish! bus (events/ci-passed dag-id run-id task-id 1 "a") nil)
      (is (= 1 (count (:events @bus)))))))

;------------------------------------------------------------------------------ Event Queries

(deftest events-for-task-test
  (testing "events-for-task filters by task ID"
    (let [bus (events/create-event-bus)
          t1 (random-uuid)
          t2 (random-uuid)]
      (events/publish! bus (events/ci-passed dag-id run-id t1 1 "a") nil)
      (events/publish! bus (events/ci-failed dag-id run-id t2 2 "b" "logs") nil)
      (events/publish! bus (events/merged dag-id run-id t1 1 "c") nil)
      (let [t1-events (events/events-for-task bus t1)]
        (is (= 2 (count t1-events)))
        (is (every? #(= t1 (:task/id %)) t1-events))))))

(deftest events-for-pr-test
  (testing "events-for-pr filters by PR ID"
    (let [bus (events/create-event-bus)]
      (events/publish! bus (events/ci-passed dag-id run-id task-id 100 "a") nil)
      (events/publish! bus (events/ci-failed dag-id run-id task-id 200 "b" "logs") nil)
      (events/publish! bus (events/merged dag-id run-id task-id 100 "c") nil)
      (let [pr100-events (events/events-for-pr bus 100)]
        (is (= 2 (count pr100-events)))
        (is (every? #(= 100 (:pr/id %)) pr100-events))))))

(deftest latest-event-test
  (testing "latest-event returns most recent matching event"
    (let [bus (events/create-event-bus)]
      (events/publish! bus (events/ci-passed dag-id run-id task-id 1 "first") nil)
      (events/publish! bus (events/ci-passed dag-id run-id task-id 1 "second") nil)
      (let [latest (events/latest-event bus #(= :pr/ci-passed (:event/type %)))]
        (is (= "second" (:pr/sha latest))))))

  (testing "latest-event returns nil when no match"
    (let [bus (events/create-event-bus)]
      (is (nil? (events/latest-event bus (constantly false)))))))