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
(ns ai.miniforge.release-executor.gate-before-publish-test
  "Recovery scenario C1: the release phase pushes and opens a PR only after
   its gates pass. Runs the real release interceptor through the workflow's
   enter -> gates -> leave lifecycle with the release executor's two halves
   stubbed, and checks which half ran."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase-software-factory.release]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.release-executor.interface :as release-executor]
   [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} release-ctx
  "A release-phase ctx over a git worktree holding one changed file, with
   verify passed and review decided `decision`."
  [worktree decision]
  {:execution/id (random-uuid)
   :execution/input {:description "Add feature" :title "Add feature"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/workflow {:workflow/pipeline [{:phase :implement} {:phase :verify}
                                            {:phase :review} {:phase :release}]}
   :execution/phase-results {:implement {:result {:status :success}}
                             :verify {:status :completed :result {:status :success}}
                             :review {:status :completed
                                      :result {:status :success
                                               :output {:review/decision decision}}}}
   :execution/errors []
   :worktree-path worktree
   :event-stream (es/create-event-stream {:sinks []})})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-release-lifecycle
  "Run the release phase through enter -> gates -> leave over a temporary
   repository, review decided `decision`. Returns {:phase phase-result
   :prepared n :published n}, counting calls to each executor half."
  [decision]
  (let [worktree (str (fs/create-temp-dir {:prefix "release-gate-"}))
        calls    (atom {:prepared 0 :published 0})]
    (try
      (shell/sh "git" "init" :dir worktree)
      (spit (fs/file worktree "feature.clj") "(ns feature)")
      (with-redefs [release-executor/prepare-release
                    (fn [_ _ _]
                      (swap! calls update :prepared inc)
                      {:success? true :artifacts [] :prepared {:branch "b"} :metrics {}})
                    release-executor/publish-release
                    (fn [_ _]
                      (swap! calls update :published inc)
                      {:success? true :artifacts [] :metrics {}})]
        (let [interceptor (phase/get-phase-interceptor {:phase :release})
              [_ phase-result] (lifecycle/execute-phase-lifecycle
                                interceptor (release-ctx worktree decision))]
          (assoc @calls :phase phase-result)))
      (finally
        (fs/delete-tree worktree)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} unready-release-publishes-nothing-test
  (testing "review requested changes: the gate denies and nothing is pushed"
    (let [{:keys [phase prepared published]} (run-release-lifecycle :changes-requested)]
      (is (= 1 prepared))
      (is (zero? published))
      (is (= :failed (:status phase)))
      (is (= :not-release-ready
             (-> phase :phase/gate-failures first :errors first :type))))))

(deftest ^{:stratum 2} ready-release-publishes-once-test
  (testing "verify passed and review approved: the gate allows and publish runs once"
    (let [{:keys [phase prepared published]} (run-release-lifecycle :approved)]
      (is (= 1 prepared))
      (is (= 1 published))
      (is (= :completed (:status phase))))))
