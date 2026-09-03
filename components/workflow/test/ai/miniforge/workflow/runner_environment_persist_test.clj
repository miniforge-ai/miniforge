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
(ns ai.miniforge.workflow.runner-environment-persist-test
  "A persist that does not happen must say so. Every non-persisting
   outcome of persist-workspace-at-phase-boundary! leaves a log entry
   naming the phase and the reason; a worktree known only to the phase
   context still persists."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.runner-environment :as env]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capturing-logger
  "A logger whose entries land in `sink`."
  [sink]
  (log/create-logger {:min-level :debug
                      :output (fn [entry] (swap! sink conj entry))}))

(defn- ^{:stratum 0} events-of [sink]
  (mapv (juxt :log/event #(get-in % [:data :reason])) @sink))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} skipped-persist-is-logged-with-its-reason
  (let [sink (atom [])]
    (with-redefs [env/env-logger (capturing-logger sink)
                  dag/persist-workspace! (fn [& _] (result/ok {:persisted? true}))]
      (testing "no worktree anywhere -> :no-worktree"
        (is (nil? (env/persist-workspace-at-phase-boundary!
                   {:execution/executor ::stub :execution/environment-id (random-uuid)}
                   {:execution/current-phase :implement})))
        (is (= [[:workflow/persist-skipped :no-worktree]] (events-of sink))))
      (testing "no environment id -> :no-environment-id"
        (reset! sink [])
        (env/persist-workspace-at-phase-boundary!
         {:execution/executor ::stub :execution/worktree-path "/tmp/wt"}
         {:execution/current-phase :implement})
        (is (= [[:workflow/persist-skipped :no-environment-id]] (events-of sink))))
      (testing "no executor -> :no-executor"
        (reset! sink [])
        (env/persist-workspace-at-phase-boundary! {} {:execution/current-phase :verify})
        (is (= [[:workflow/persist-skipped :no-executor]] (events-of sink)))))))

(deftest ^{:stratum 1} worktree-known-only-to-the-phase-context-still-persists
  (let [sink (atom []) opts-seen (atom nil)]
    (with-redefs [env/env-logger (capturing-logger sink)
                  dag/persist-workspace! (fn [_ _ opts] (reset! opts-seen opts)
                                           (result/ok {:persisted? false :no-changes? true :branch "task-x"}))]
      (env/persist-workspace-at-phase-boundary!
       {:execution/executor ::stub :execution/environment-id (random-uuid)}
       {:execution/current-phase :implement :execution/worktree-path "/tmp/phase-wt"})
      (is (= "/tmp/phase-wt" (:workdir @opts-seen)) "the phase context's worktree is used")
      (is (= [[:workflow/persist-no-changes nil]] (events-of sink))
          "a clean worktree is reported, not silent"))))

(deftest ^{:stratum 1} rejected-persist-is-logged
  (let [sink (atom [])]
    (with-redefs [env/env-logger (capturing-logger sink)
                  dag/persist-workspace! (fn [& _] (result/err :worktree-missing "gone"))]
      (env/persist-workspace-at-phase-boundary!
       {:execution/executor ::stub :execution/environment-id (random-uuid)
        :execution/worktree-path "/tmp/wt"}
       {:execution/current-phase :implement})
      (is (= [:workflow/persist-rejected] (mapv :log/event @sink))))))
