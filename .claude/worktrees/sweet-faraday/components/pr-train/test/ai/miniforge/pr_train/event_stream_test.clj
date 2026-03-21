(ns ai.miniforge.pr-train.event-stream-test
  "Tests for event-stream integration in PR train manager."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.interface :as train]))

(deftest complete-merge-emits-pr-merged-event
  (testing "complete-merge publishes :pr/merged event to event-stream"
    (let [;; Mock event-stream: an atom that collects published events
          published (atom [])
          mock-stream (reify Object)
          ;; Stub requiring-resolve to return our mock publish! fn
          mgr (train/create-manager {:event-stream mock-stream})]

      ;; Override the publish! call by redefining the resolved var behavior.
      ;; Since complete-merge uses requiring-resolve, we can use with-redefs
      ;; on the resolved function directly.
      (with-redefs [ai.miniforge.event-stream.interface/publish!
                    (fn [stream event]
                      (swap! published conj {:stream stream :event event})
                      event)]

        ;; Set up a train with one PR and drive it through to merge
        (let [train-id (train/create-train mgr "Event Test" (random-uuid) nil)]
          (train/add-pr mgr train-id "acme/repo" 42 "url" "branch" "PR 42")
          (train/link-prs mgr train-id)

          ;; Transition PR through: draft -> open -> reviewing -> approved
          (train/update-pr-status mgr train-id 42 :open)
          (train/update-pr-status mgr train-id 42 :reviewing)
          (train/update-pr-status mgr train-id 42 :approved)
          (train/update-pr-ci-status mgr train-id 42 :passed)

          ;; Merge next marks it as :merging
          (train/merge-next mgr train-id)

          ;; Complete the merge — should emit event
          (let [result (train/complete-merge mgr train-id 42)]
            (is (some? result) "complete-merge should return updated train")
            (is (= :merged (:pr/status (train/get-pr-from-train mgr train-id 42))))

            ;; Verify event was published
            (is (= 1 (count @published)) "exactly one event should be published")
            (let [{:keys [stream event]} (first @published)]
              (is (= mock-stream stream) "event should be published to the manager's event-stream")
              (is (= :pr/merged (:event/type event)))
              (is (= 42 (:pr/number event)))
              (is (= train-id (:train/id event)))
              (is (inst? (:timestamp event))))))))))

(deftest complete-merge-without-event-stream
  (testing "complete-merge works normally when no event-stream is configured"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "No Stream" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/repo" 99 "url" "branch" "PR 99")
      (train/link-prs mgr train-id)

      (train/update-pr-status mgr train-id 99 :open)
      (train/update-pr-status mgr train-id 99 :reviewing)
      (train/update-pr-status mgr train-id 99 :approved)
      (train/update-pr-ci-status mgr train-id 99 :passed)
      (train/merge-next mgr train-id)

      (let [result (train/complete-merge mgr train-id 99)]
        (is (some? result))
        (is (= :merged (:pr/status (train/get-pr-from-train mgr train-id 99))))))))
