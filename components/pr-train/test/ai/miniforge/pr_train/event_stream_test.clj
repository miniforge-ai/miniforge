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

(ns ai.miniforge.pr-train.event-stream-test
  "Tests for event-stream integration in PR train manager."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.interface :as train]
   [ai.miniforge.event-stream.interface :as es]))

(deftest complete-merge-emits-pr-merged-event
  (testing "complete-merge publishes :pr/merged event to event-stream"
    ;; Use a real event-stream (no with-redefs — safe under pmap)
    (let [stream (es/create-event-stream)
          captured (atom [])
          mgr (train/create-manager {:event-stream stream})]

      ;; Subscribe to capture :pr/merged events
      (es/subscribe! stream :test-subscriber
                     (fn [event]
                       (swap! captured conj event))
                     (fn [event]
                       (= :pr/merged (:event/type event))))

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
          (is (= 1 (count @captured)) "exactly one :pr/merged event should be published")
          (let [event (first @captured)]
            (is (= :pr/merged (:event/type event)))
            (is (= 42 (:pr/number event)))
            (is (= train-id (:train/id event)))
            (is (inst? (:timestamp event)))))))))

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
