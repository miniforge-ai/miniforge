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

(ns ai.miniforge.workflow.phase-outcome-from-inner-result-test
  "Regression tests: an inner :result :status of :error/:failed/:failure MUST
   propagate to the phase outcome. The phase wrapper setting :status :completed
   only means the interceptor ran without crashing — not that the agent inside
   succeeded. Observed in production 2026-04-18 workflow 687816fd: plan phase
   reported :phase/outcome :success over an agent :result {:status :error},
   making the DAG skip ambiguous."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner-events :as events]
   [ai.miniforge.event-stream.interface :as event-stream]))

(defn- build [result]
  ;; exercise the private fn through phase-completed which consumes it
  (#'events/build-phase-event-data result))

(deftest outer-completed-inner-error-reports-failure
  (testing "phase :status :completed but inner :result :status :error → :failure"
    (let [phase-result {:name :plan
                        :status :completed
                        :result {:status :error
                                 :error {:message "planner exploded"}
                                 :metrics {:tokens 100}}}
          data (build phase-result)]
      (is (= :failure (:outcome data)))
      (is (= {:message "planner exploded"} (:error data))))))

(deftest outer-completed-inner-failed-reports-failure
  (testing "inner :status :failed also propagates"
    (is (= :failure (:outcome (build {:name :x :status :completed
                                       :result {:status :failed}}))))))

(deftest outer-completed-inner-failure-reports-failure
  (testing "inner :status :failure also propagates (response/failure shape)"
    (is (= :failure (:outcome (build {:name :x :status :completed
                                       :result {:status :failure}}))))))

(deftest inner-success-false-reports-failure
  (testing ":result :success? false propagates as failure (DAG-result shape)"
    (is (= :failure (:outcome (build {:name :x :status :completed
                                       :result {:success? false}}))))))

(deftest genuine-success-still-success
  (testing "both outer and inner healthy → :success"
    (let [phase-result {:name :plan
                        :status :completed
                        :result {:status :success
                                 :output {:plan/id (random-uuid)}}}]
      (is (= :success (:outcome (build phase-result)))))))

(deftest event-emitted-with-failure-on-inner-error
  (testing "phase-completed event reports :failure when inner result errored"
    (let [stream (event-stream/create-event-stream)
          ctx {:execution/id (random-uuid)}
          phase-result {:name :plan :status :completed
                        :result {:status :error
                                 :error {:message "boom"}}}]
      (events/publish-phase-completed! stream ctx :plan phase-result)
      (let [evts (event-stream/get-events stream)
            evt (first (filter #(= :workflow/phase-completed (:event/type %)) evts))]
        (is evt "phase-completed event should be published")
        (is (= :failure (:phase/outcome evt)))
        (is (= {:message "boom"} (:phase/error evt)))))))

(deftest error-attached-at-phase-error-surfaces-test
  (testing "[:phase :error] is read when nothing higher carries the payload"
    ;; Regression: error-* interceptors that fall through to
    ;; `phase/fail-phase` attach the error map at `[:phase :error]` rather
    ;; than at the top level or at `[:result :error]`. Before this branch
    ;; was added, `build-phase-event-data` couldn't find that error and
    ;; emitted a bare failure event with no payload — observed on the
    ;; 2026-05-02 dogfood as "Phase :release failure (0ms)" with no
    ;; diagnostic context whatsoever.
    (let [phase-result {:name :release
                        :status :failed
                        :phase {:error {:message "Release phase received code artifact with zero files"
                                        :data {:phase :release :worktree-path "/tmp/x"}}}}
          data (build phase-result)]
      (is (= :failure (:outcome data)))
      (is (= "Release phase received code artifact with zero files"
             (get-in data [:error :message])))
      (is (= "/tmp/x" (get-in data [:error :data :worktree-path]))
          "the error data payload is preserved end-to-end so consumers can
           debug the failure without parsing the run log"))))
