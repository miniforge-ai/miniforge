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
(ns ai.miniforge.event-stream.bounded-stream-test
  "A stream created with :retention keeps memory bounded, keeps each
   retained workflow's lifecycle events and newest others, and still
   delivers every event to sinks and subscribers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} small-policy
  "Two workflows, ten non-lifecycle events each; compaction every eleven
   publishes."
  {:max-workflows 2 :max-events-per-workflow 10})

(def ^{:stratum 0} lifecycle-order
  [:workflow/started :workflow/phase-started :workflow/phase-completed :workflow/completed])

(defn- ^{:stratum 0} event [event-type workflow-id]
  {:event/type event-type :event/id (random-uuid) :workflow/id workflow-id})

(defn- ^{:stratum 0} of-workflow [workflow-id events]
  (filterv #(= workflow-id (:workflow/id %)) events))

(defn- ^{:stratum 0} chunk-ids [events]
  (->> events (filter #(= :agent/chunk (:event/type %))) (mapv :event/id)))

(defn- ^{:stratum 0} count-call [counter _event]
  (swap! counter inc))

(defn- ^{:stratum 0} publish-all! [stream events]
  (doseq [e events] (es/publish! stream e))
  stream)

(deftest ^{:stratum 0} invalid-retention-limits-are-rejected-test
  (is (thrown? IllegalArgumentException
               (es/create-event-stream {:sinks [] :retention {:max-workflows 0}}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} lifecycle-types [events]
  (filterv (set lifecycle-order) (map :event/type events)))

(defn- ^{:stratum 1} workflow-run
  "One workflow's events in publish order: start, a phase with `chunks`
   agent chunks, completion."
  [workflow-id chunks]
  (vec (concat [(event :workflow/started workflow-id)
                (event :workflow/phase-started workflow-id)]
               (repeatedly chunks (partial event :agent/chunk workflow-id))
               [(event :workflow/phase-completed workflow-id)
                (event :workflow/completed workflow-id)])))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} bounded-stream-keeps-lifecycle-and-newest-events-test
  (let [wf       (random-uuid)
        run      (workflow-run wf 1000)
        stream   (publish-all! (es/create-event-stream {:sinks [] :retention small-policy}) run)
        retained (es/get-events stream)]
    (testing "1004 published events leave at most 10 + 4 lifecycle + 10 slack in memory"
      (is (<= (count retained) 24)))
    (testing "every lifecycle event a workflow summary reads survives"
      (is (= lifecycle-order (lifecycle-types retained))))
    (testing "the retained chunks are the newest ones, in publish order"
      (let [kept (chunk-ids retained)]
        (is (<= 10 (count kept) 20))
        (is (= (take-last (count kept) (chunk-ids run)) kept))))
    (testing "the newest event is always retained"
      (is (= (:event/id (peek run)) (:event/id (peek retained)))))))

(deftest ^{:stratum 2} bounded-stream-drops-least-recently-active-workflows-test
  (let [workflows (repeatedly 5 random-uuid)
        stream    (es/create-event-stream {:sinks [] :retention small-policy})]
    (doseq [wf workflows]
      (publish-all! stream (workflow-run wf 30)))
    (let [retained (es/get-events stream)
          [_ _ _ previous latest] workflows]
      (is (= #{previous latest} (set (map :workflow/id retained)))
          "only the two workflows that published last remain")
      (is (= lifecycle-order (lifecycle-types (of-workflow previous retained)))
          "a finished workflow still in memory keeps its whole lifecycle"))))

(deftest ^{:stratum 2} bounded-stream-delivers-every-event-test
  (let [delivered (atom 0)
        sunk      (atom 0)
        stream    (es/create-event-stream {:sinks [(partial count-call sunk)]
                                           :retention small-policy})
        run       (workflow-run (random-uuid) 500)]
    (es/subscribe! stream ::counter (partial count-call delivered))
    (publish-all! stream run)
    (is (= (count run) @delivered) "subscribers see every event")
    (is (= (count run) @sunk) "sinks see every event")
    (is (< (count (es/get-events stream)) (count run)) "memory keeps only the retained subset")))

(deftest ^{:stratum 2} default-stream-keeps-every-event-test
  (testing "one-shot runs keep the unbounded history their readers rely on"
    (let [run    (workflow-run (random-uuid) 1200)
          stream (publish-all! (es/create-event-stream {:sinks []}) run)]
      (is (= (mapv :event/id run) (mapv :event/id (es/get-events stream)))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.bounded-stream-test))
