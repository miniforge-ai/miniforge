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
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gh-token
  "Shaped like a GitHub token; not a credential. Built from parts so the
   source holds no token-shaped literal."
  (str "ghp" "_" "abcdefghijklmnopqrstuvwxyz0123456789"))

(defn ^{:stratum 0} writer
  "An interceptor for PHASE that outputs ARTIFACT, checked by GATES."
  [phase gates artifact]
  {:name   ::writer
   :config {:phase phase :gates gates}
   :enter  (fn [c]
             (phase/enter-context c phase :implementer gates
                                  {:tokens 1 :iterations 1 :time-seconds 1}
                                  (System/currentTimeMillis)
                                  {:status :success :output artifact}))
   :leave  identity
   :error  (fn [c _] c)})

(defn ^{:stratum 0} temp-worktree
  "A git repo with one commit, then FILES ({path content}) written over it
   uncommitted. Returns the directory."
  [files]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "gate-denied-persist" (make-array java.nio.file.attribute.FileAttribute 0)))
        git #(apply shell/sh "git" "-C" dir %&)]
    (git "init" "-q")
    (git "config" "user.email" "test@test")
    (git "config" "user.name" "test")
    (git "config" "commit.gpgsign" "false")
    (spit (str dir "/README.md") "x\n")
    (git "add" "-A")
    (git "commit" "-qm" "base")
    (doseq [[path content] files] (spit (str dir "/" path) content))
    dir))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} governed-phase!
  "Run one governed PHASE (implement by default) with GATES over ARTIFACT,
   merging EXTRA into the context. Returns the commands the capsule's
   git-persist! ran, each as one string."
  ([gates artifact]
   (governed-phase! phase-test-support/runner-test-implement gates artifact {}))
  ([phase gates artifact extra]
  (let [cmds    (atom [])
        exec!   (fn [cmd]
                  (swap! cmds conj (if (string? cmd) cmd (str/join " " cmd)))
                  {:data {:stdout "abc123" :exit-code 0}})
        wf      {:workflow/id :gate-denied-persist :workflow/version "1.0.0"
                 :workflow/pipeline [{:phase phase}]}
        context (assoc (ctx/create-context wf {:task "t"} {})
                       :execution/mode :governed
                       :execution/executor ::capsule
                       :execution/environment-id "env-1"
                       :execution/task-branch "task/x"
                       ;; The gate decision must be acknowledged for an
                       ;; allowed phase to advance; an in-memory stream.
                       :event-stream (events/create-event-stream {:sinks []}))]
    (with-redefs [dag/persist-workspace! (fn [_ _ opts] (workspace/git-persist! exec! opts))]
      (phase-step/execute-phase [(writer phase gates artifact)] (merge context extra) {}))
    @cmds)))

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

(deftest ^{:stratum 2} a-later-phase-does-not-publish-a-left-behind-secret-test
  (let [secret (str "api_key = '" gh-token "'\n")
        dir    (temp-worktree {"config.py" secret})
        extra  {:execution/worktree-path dir}]
    (testing "implement is denied over the credential it wrote"
      (is (= [] (governed-phase! phase-test-support/runner-test-implement [:no-secrets]
                                 {:code/files [{:path "config.py" :content secret}]} extra))))
    (testing "the plan phase a redirect runs next succeeds, yet commits and pushes nothing"
      (is (= [] (governed-phase! phase-test-support/runner-test-plan [] {:plan/tasks []} extra))))
    (testing "once the credential is gone, the plan boundary publishes again"
      (spit (str dir "/config.py") "x = 1\n")
      (is (some #(str/includes? % "git push")
                (governed-phase! phase-test-support/runner-test-plan [] {:plan/tasks []} extra))))))

(deftest ^{:stratum 2} an-unscannable-worktree-is-held-not-pushed-test
  (testing "a later phase whose worktree git cannot read commits locally and pushes nothing"
    (let [dir  (str (java.nio.file.Files/createTempDirectory
                     "gate-denied-not-a-repo" (make-array java.nio.file.attribute.FileAttribute 0)))
          cmds (governed-phase! phase-test-support/runner-test-plan [] {:plan/tasks []}
                                {:execution/worktree-path dir})]
      (is (some #(str/includes? % "commit") cmds))
      (is (not-any? #(str/includes? % "push") cmds)))))
