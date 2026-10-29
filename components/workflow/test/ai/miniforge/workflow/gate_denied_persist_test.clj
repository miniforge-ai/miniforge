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
(ns ai.miniforge.workflow.gate-denied-persist-test
  "A phase its gates deny is not pushed. Each test drives one governed
   phase through runner-phase-step/execute-phase with the real gates,
   and the capsule's git-persist! running against a command recorder."
  (:require [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.dag-executor.workspace :as workspace]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.phase-test-support :as phase-test-support]
            [ai.miniforge.workflow.runner-phase-step :as phase-step]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gh-token
  "Shaped like a GitHub token; not a credential. Built from parts so the
   source holds no token-shaped literal."
  (str "ghp" "_" "abcdefghijklmnopqrstuvwxyz0123456789"))

(defn ^{:stratum 0} writer
  "An implement-like interceptor that outputs ARTIFACT, checked by GATES."
  [gates artifact]
  {:name   ::writer
   :config {:phase phase-test-support/runner-test-implement :gates gates}
   :enter  (fn [c]
             (phase/enter-context c phase-test-support/runner-test-implement :implementer gates
                                  {:tokens 1 :iterations 1 :time-seconds 1}
                                  (System/currentTimeMillis)
                                  {:status :success :output artifact}))
   :leave  identity
   :error  (fn [c _] c)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} governed-phase!
  "Run one governed phase with GATES over ARTIFACT. Returns the commands
   the capsule's git-persist! ran, each as one string."
  [gates artifact]
  (let [cmds    (atom [])
        exec!   (fn [cmd]
                  (swap! cmds conj (if (string? cmd) cmd (str/join " " cmd)))
                  {:data {:stdout "abc123" :exit-code 0}})
        wf      {:workflow/id :gate-denied-persist :workflow/version "1.0.0"
                 :workflow/pipeline [{:phase phase-test-support/runner-test-implement}]}
        context (assoc (ctx/create-context wf {:task "t"} {})
                       :execution/mode :governed
                       :execution/executor ::capsule
                       :execution/environment-id "env-1"
                       :execution/task-branch "task/x"
                       ;; The gate decision must be acknowledged for an
                       ;; allowed phase to advance; an in-memory stream.
                       :event-stream (events/create-event-stream {:sinks []}))]
    (with-redefs [dag/persist-workspace! (fn [_ _ opts] (workspace/git-persist! exec! opts))]
      (phase-step/execute-phase [(writer gates artifact)] context {}))
    @cmds))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-detected-secret-is-never-committed-or-pushed-test
  (testing ":no-secrets denies the phase, so the capsule runs no git at all"
    (is (= [] (governed-phase! [:no-secrets]
                               {:code/files [{:path "config.py"
                                              :content (str "api_key = '" gh-token "'\n")}]})))))

(deftest ^{:stratum 2} another-denial-is-committed-but-not-pushed-test
  (testing "a syntax denial keeps the work in a local commit for repair, unpushed"
    (let [cmds (governed-phase! [:syntax] {:content "(defn broken [] 42"})]
      (is (some #(str/includes? % "commit") cmds))
      (is (not-any? #(str/includes? % "push") cmds)))))

(deftest ^{:stratum 2} an-allowed-phase-is-pushed-test
  (testing "control: the same harness pushes when the gates allow the phase"
    (is (some #(str/includes? % "git push")
              (governed-phase! [:no-secrets] {:code/files [{:path "a.py" :content "x = 1\n"}]})))))
