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

(ns ai.miniforge.workflow.checkpoint-store-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.interface :as workflow]))

(defn- with-temp-checkpoint-root
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "mf-checkpoint-test-" (random-uuid)))
               .mkdirs)]
    (try
      (f (.getAbsolutePath root))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete ^java.io.File file))))))

(deftest persist-and-load-checkpoint-data-test
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [workflow {:workflow/id :test
                      :workflow/version "1.0.0"
                      :workflow/pipeline [{:phase :done}]}
            execution-ctx (-> (ctx/create-context workflow {:task "Test"}
                                                  {:checkpoint/root checkpoint-root})
                              (assoc-in [:execution/phase-results :done]
                                        {:status :completed
                                         :summary "done"})
                              (assoc :execution/current-phase :done
                                     :execution/started-at (java.time.Instant/now)
                                     :execution/output
                                     {:status :running
                                      :finished-at (java.time.Instant/now)}))
            _ (checkpoint-store/persist-execution-state! execution-ctx)
            checkpoint-data (checkpoint-store/load-checkpoint-data
                             (:execution/id execution-ctx)
                             {:checkpoint/root checkpoint-root})]
        (testing "loads machine snapshot and phase results"
          (is (= (:execution/id execution-ctx)
                 (get-in checkpoint-data [:machine-snapshot :execution/id])))
          (is (= {:status :completed
                  :summary "done"}
                 (get-in checkpoint-data [:phase-results :done]))))
        (testing "serializes nested instant values into EDN-readable strings"
          (is (string? (get-in checkpoint-data
                               [:machine-snapshot :execution/started-at])))
          (is (string? (get-in checkpoint-data
                               [:machine-snapshot
                                :execution/output
                                :finished-at]))))
        (testing "manifest tracks checkpointed phases"
          (is (= [:done]
                 (get-in checkpoint-data [:manifest :workflow/phases-completed]))))))))

(deftest persist-execution-state-validates-before-saving-test
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [workflow {:workflow/id :test
                      :workflow/version "1.0.0"
                      :workflow/pipeline [{:phase :done}]}
            invalid-ctx (-> (ctx/create-context workflow {:task "Test"}
                                                {:checkpoint/root checkpoint-root})
                            (assoc :execution/phase-results {:done :invalid-phase-result}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid checkpoint data"
                              (checkpoint-store/persist-execution-state! invalid-ctx)))))))

(deftest load-checkpoint-data-validates-at-interface-boundary-test
  (testing "invalid checkpoint payloads are rejected at the public interface"
    (with-redefs [checkpoint-store/load-checkpoint-data
                  (fn [_workflow-run-id _opts]
                    {:checkpoint/root "/tmp/checkpoints"
                     :manifest {:workflow/id (random-uuid)
                                :workflow/workflow-id :canonical-sdlc
                                :workflow/workflow-version "1.0.0"
                                :workflow/phases-completed [:plan]
                                :workflow/machine-snapshot-path "/tmp/checkpoints/run/machine-snapshot.edn"
                                :workflow/phase-checkpoints {:plan "/tmp/checkpoints/run/phases/plan.edn"}
                                :workflow/last-checkpoint-at "2026-04-24T00:00:00Z"}
                     :machine-snapshot {:execution/id (random-uuid)
                                        :execution/workflow-id :canonical-sdlc
                                        :execution/workflow-version "1.0.0"
                                        :execution/status :running
                                        :execution/fsm-state {:_state :running}
                                        :execution/response-chain {}
                                        :execution/errors []
                                        :execution/artifacts []
                                        :execution/metrics {}}
                     :phase-results {:plan :invalid-phase-result}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid checkpoint data"
                            (workflow/load-checkpoint-data (random-uuid) {}))))))
