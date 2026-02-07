(ns ai.miniforge.task-executor.bridge-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.task-executor.bridge :as bridge]))

(deftest pr-event->scheduler-action-test
  (testing "All expected event mappings exist"
    (is (= :pr-opened (get bridge/pr-event->scheduler-action :pr/opened)))
    (is (= :ci-passed (get bridge/pr-event->scheduler-action :pr/ci-passed)))
    (is (= :ci-failed (get bridge/pr-event->scheduler-action :pr/ci-failed)))
    (is (= :review-approved (get bridge/pr-event->scheduler-action :pr/review-approved)))
    (is (= :review-changes-requested (get bridge/pr-event->scheduler-action :pr/review-changes-requested)))
    (is (= :fix-pushed (get bridge/pr-event->scheduler-action :pr/fix-pushed)))
    (is (= :merged (get bridge/pr-event->scheduler-action :pr/merged)))
    (is (= :merge-failed (get bridge/pr-event->scheduler-action :pr/closed)))
    (is (= :ci-failed (get bridge/pr-event->scheduler-action :pr/conflict)))
    (is (= :ci-failed (get bridge/pr-event->scheduler-action :pr/rebase-needed))))

  (testing "Unmapped events return nil"
    (is (nil? (get bridge/pr-event->scheduler-action :pr/comment-actionable)))
    (is (nil? (get bridge/pr-event->scheduler-action :pr/unknown)))))

(deftest translate-event-test
  (testing "Translates mapped PR events to scheduler events"
    (let [pr-event {:event/type :pr/ci-passed
                    :task-id "task-123"
                    :timestamp #inst "2026-02-06T10:00:00Z"
                    :pr-url "https://github.com/org/repo/pull/1"}
          result (bridge/translate-event pr-event)]
      (is (= :ci-passed (:event/action result)))
      (is (= "task-123" (:event/task-id result)))
      (is (= #inst "2026-02-06T10:00:00Z" (:timestamp result)))
      (is (= "https://github.com/org/repo/pull/1" (get-in result [:metadata :pr-url])))))

  (testing "Returns nil for unmapped event types"
    (is (nil? (bridge/translate-event {:event/type :pr/comment-actionable
                                       :task-id "task-123"}))))

  (testing "Handles missing optional fields"
    (let [result (bridge/translate-event {:event/type :pr/merged
                                          :task-id "task-456"})]
      (is (= :merged (:event/action result)))
      (is (= "task-456" (:event/task-id result)))
      (is (nil? (:timestamp result)))))

  (testing "Conflict events map to ci-failed"
    (let [result (bridge/translate-event {:event/type :pr/conflict
                                          :task-id "task-789"})]
      (is (= :ci-failed (:event/action result)))
      (is (= "task-789" (:event/task-id result)))))

  (testing "Rebase events map to ci-failed"
    (let [result (bridge/translate-event {:event/type :pr/rebase-needed
                                          :task-id "task-999"})]
      (is (= :ci-failed (:event/action result)))
      (is (= "task-999" (:event/task-id result))))))

(deftest create-scheduler-event-test
  (testing "Creates basic scheduler event"
    (let [result (bridge/create-scheduler-event "task-123" :ci-passed)]
      (is (= :ci-passed (:event/action result)))
      (is (= "task-123" (:event/task-id result)))
      (is (instance? java.time.Instant (:timestamp result)))))

  (testing "Accepts custom timestamp"
    (let [ts #inst "2026-02-06T12:00:00Z"
          result (bridge/create-scheduler-event "task-456" :merged {:timestamp ts})]
      (is (= ts (:timestamp result)))))

  (testing "Includes metadata when provided"
    (let [result (bridge/create-scheduler-event "task-789" :review-approved
                   {:metadata {:reviewer "alice" :pr-url "https://..."}})]
      (is (= "alice" (get-in result [:metadata :reviewer])))
      (is (= "https://..." (get-in result [:metadata :pr-url])))))

  (testing "Round-trip translation preserves semantics"
    (let [pr-event {:event/type :pr/ci-passed
                    :task-id "task-111"
                    :timestamp #inst "2026-02-06T14:00:00Z"}
          scheduler-event (bridge/translate-event pr-event)
          recreated (bridge/create-scheduler-event
                      (:event/task-id scheduler-event)
                      (:event/action scheduler-event)
                      {:timestamp (:timestamp scheduler-event)})]
      (is (= :ci-passed (:event/action scheduler-event)))
      (is (= :ci-passed (:event/action recreated)))
      (is (= "task-111" (:event/task-id scheduler-event)))
      (is (= "task-111" (:event/task-id recreated)))
      (is (= (:timestamp scheduler-event) (:timestamp recreated))))))

(deftest unmapped-event?-test
  (testing "Returns true for unmapped events"
    (is (true? (bridge/unmapped-event? :pr/comment-actionable)))
    (is (true? (bridge/unmapped-event? :pr/unknown))))

  (testing "Returns false for mapped events"
    (is (false? (bridge/unmapped-event? :pr/ci-passed)))
    (is (false? (bridge/unmapped-event? :pr/merged)))
    (is (false? (bridge/unmapped-event? :pr/conflict)))))
