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
(ns ai.miniforge.agent.artifact-session-capsule-workdir-test
  "A governed artifact session is created in the capsule's own checkout,
   not the host worktree path a runner-acquired capsule records."
  (:require [ai.miniforge.agent.artifact-session :as session]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} governed-context
  "A governed run whose context carries both paths."
  {:execution/mode :governed
   :execution/executor ::capsule
   :execution/environment-id "env-1"
   :execution/execute-fn (fn [& _] {:data {:stdout "" :exit-code 0}})
   :execution/worktree-path "/host/worktrees/task-1"
   :execution/capsule-workdir "/workspace"})

(defn ^{:stratum 0} capsule-session-workdir
  "The workdir RUN! creates its capsule session in."
  [run!]
  (let [seen (atom nil)]
    (with-redefs [session/create-capsule-session! (fn [_executor _env-id workdir _exec!]
                                                    (reset! seen workdir)
                                                    (throw (ex-info "stop" {::stop true})))]
      (try (run!) (catch clojure.lang.ExceptionInfo e
                    (when-not (::stop (ex-data e)) (throw e)))))
    @seen))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} governed-sessions-use-the-capsule-checkout-test
  (testing "with-session"
    (is (= "/workspace" (capsule-session-workdir #(session/with-session governed-context (fn [_] nil))))))
  (testing "with-readonly-session"
    (is (= "/workspace" (capsule-session-workdir #(session/with-readonly-session governed-context (fn [_] nil)))))))
