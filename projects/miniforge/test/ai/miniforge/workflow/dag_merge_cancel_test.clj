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
(ns ai.miniforge.workflow.dag-merge-cancel-test
  "A cancel reaches a multi-parent task while its parent branches are
   merged. The merge runs before the task's sub-workflow, so the task's
   own phases cannot see the cancel yet."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.llm.cancellation :as cancellation]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.dag-merge :as dag-merge]
   [ai.miniforge.workflow.dag-merge-attempt :as dag-merge-attempt]
   [ai.miniforge.workflow.dag-sub-workflow :as dag-sub-workflow]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} multi-parent-task
  {:task/id :c :task/deps [:a :b]})

(def ^{:stratum 0 :private true} merged
  (dag/ok {:branch "merged"}))

(def ^{:stratum 0 :private true} conflict
  {:anomaly/category :anomalies/dag-multi-parent-conflict})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} merge-anomaly [control-state]
  (:dag/merge-anomaly (dag-sub-workflow/task-sub-opts {:control-state control-state} multi-parent-task)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-cancel-around-a-parent-merge-stops-the-task-test
  (testing "a run stopped before the merge does not merge"
    (let [merges (atom 0)
          cs     (doto (es/create-control-state) es/cancel!)]
      (with-redefs [dag-merge/merge-parent-branches! (fn [_ _] (swap! merges inc) merged)]
        (is (= :anomalies/dag-merge-stopped (:anomaly/category (merge-anomaly cs))))
        (is (zero? @merges)))))
  (testing "a merge that ends after the cancel is the task's stop, keeping the merge's own failure"
    (let [cs (es/create-control-state)]
      (with-redefs [dag-merge/merge-parent-branches! (fn [_ _] (es/cancel! cs) merged)]
        (is (= :anomalies/dag-merge-stopped (:anomaly/category (merge-anomaly cs)))))
      (let [cs (es/create-control-state)]
        (with-redefs [dag-merge/merge-parent-branches! (fn [_ _] (es/cancel! cs) conflict)]
          (is (= conflict (:merge/failure (merge-anomaly cs))))))))
  (testing "a merge with no cancel is the merge's own result"
    (with-redefs [dag-merge/merge-parent-branches! (fn [_ _] conflict)]
      (is (= conflict (merge-anomaly (es/create-control-state)))))))

(deftest ^{:stratum 2} the-resolution-stops-on-the-runs-cancel-test
  (testing "LLM calls inside the merge see the run's cancel"
    (let [cs   (es/create-control-state)
          seen (atom nil)]
      (with-redefs [dag-merge/merge-parent-branches!
                    (fn [_ _]
                      (es/cancel! cs)
                      (reset! seen (cancellation/cancelled-timeout 0))
                      merged)]
        (merge-anomaly cs)
        (is (= :cancelled (:type @seen))))))
  (testing "the resolution agent is not called once the run is stopped"
    (let [attempts (atom 0)
          cs       (es/create-control-state)
          {edit :agent-edit-fn} (dag-merge-attempt/derive-resolution-overrides
                                 {:control-state cs
                                  :dag/resolution-overrides {:agent-edit-fn (fn [& _] (swap! attempts inc))}})]
      (edit "/tmp/wt" {} 0)
      (es/cancel! cs)
      (edit "/tmp/wt" {} 1)
      (is (= 1 @attempts))))
  (testing "verify fails once the run is stopped, so a cut-short resolution is not committed"
    (let [cs (es/create-control-state)
          {verify :verify-fn} (dag-merge-attempt/derive-resolution-overrides {:control-state cs})]
      (is (response/success? (verify "/tmp/wt")))
      (es/cancel! cs)
      (is (response/error? (verify "/tmp/wt"))))))
