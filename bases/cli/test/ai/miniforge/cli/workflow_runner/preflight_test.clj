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
(ns ai.miniforge.cli.workflow-runner.preflight-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.cli.workflow-runner.paths :as paths]
   [ai.miniforge.cli.workflow-runner.preflight :as preflight]
   [ai.miniforge.cli.workflow-runner.preflight-probe :as probe]
   [ai.miniforge.cli.workflow-runner.process :as process]
   [ai.miniforge.cli.workflow-runner.provenance :as provenance]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-repo-root []
  (let [root (str (fs/create-temp-dir {:prefix "mf-preflight-"}))]
    (fs/create-dirs (fs/path root ".git"))
    root))

(defn- ^{:stratum 0} shell-path []
  (or (some-> (fs/which "sh") str)
      "/bin/sh"))

(deftest ^{:stratum 0} await-stream-waits-for-reader-completion-test
  (testing "stream join waits for a completed reader instead of dropping late output"
    (let [started-at (System/currentTimeMillis)
          result (#'process/await-stream (future
                                       (Thread/sleep 1100)
                                       "late-output"))
          elapsed-ms (- (System/currentTimeMillis) started-at)]
      (is (= "late-output" result))
      (is (<= 1000 elapsed-ms)))))

(deftest ^{:stratum 0} print-runtime-provenance-includes-startup-warnings-test
  (testing "startup provenance prints upstream and source checkout warnings"
    (let [output (with-out-str
                   (#'provenance/print-runtime-provenance!
                    false
                    {:source-root "/tmp/source-root"
                     :worktree-path "/tmp/runtime-worktree"
                     :git-branch "HEAD"
                     :git-commit "abc1234"
                     :git-upstream "origin/main"
                     :git-detached? true
                     :git-dirty? true}))]
      (is (str/includes? output "Source: /tmp/source-root"))
      (is (str/includes? output "Worktree: /tmp/runtime-worktree"))
      (is (str/includes? output "Upstream: origin/main"))
      (is (str/includes? output "detached HEAD"))
      (is (str/includes? output "uncommitted changes")))))

(deftest ^{:stratum 0} run-backend-preflight-stamps-resolved-cli-and-version-test
  (testing "backend preflight resolves the inherited CLI path and prints the stamp"
    (let [llm-client (llm/mock-client {:output "{\"ok\":true}"})
          preflight-client (atom nil)
          output (with-out-str
                   (with-redefs-fn {#'paths/resolve-cli-command-path (fn [_] "/Users/chris/.local/bin/claude")
                                    #'probe/read-cli-version (fn [_] {:success true :version "2.1.126"})
                                    #'probe/run-claude-backend-preflight (fn [cmd-path workdir]
                                                                         (is (= "/Users/chris/.local/bin/claude" cmd-path))
                                                                         (is (= "/tmp/runtime-worktree" workdir))
                                                                         {:success true :content "{\"ok\":true}" :exit-code 0})
                                    #'llm/create-client (fn [opts]
                                                         (reset! preflight-client opts)
                                                         {:config {:backend (:backend opts) :model (:model opts)}})
                                    #'llm/complete (fn [_client _request]
                                                     (is false "Claude preflight should use the direct CLI probe path"))}
                     (fn []
                       (#'preflight/run-backend-preflight!
                        false
                        llm-client
                        {:worktree-path "/tmp/runtime-worktree"}))))]
      (is (str/includes? output "Backend: claude"))
      (is (str/includes? output "Backend Path: /Users/chris/.local/bin/claude"))
      (is (str/includes? output "Backend Version: 2.1.126"))
      (is (nil? @preflight-client)))))

(deftest ^{:stratum 0} run-backend-preflight-fails-closed-on-bad-cli-health-test
  (testing "backend preflight carries the resolved path and version when the probe fails"
    (let [llm-client (llm/mock-client {:output "{\"ok\":true}"})]
      (try+
        (with-redefs-fn {#'paths/resolve-cli-command-path (fn [_] "/opt/homebrew/bin/claude")
                         #'probe/read-cli-version (fn [_] {:success true :version "2.1.89"})
                         #'probe/run-claude-backend-preflight (fn [_cmd-path _workdir]
                                                              {:success false
                                                               :error {:type "backend_preflight_timeout"
                                                                       :message "Process timed out after 10000ms"}
                                                               :exit-code -1})}
          (fn []
            (#'preflight/run-backend-preflight!
             true
             llm-client
             {:worktree-path "/tmp/runtime-worktree"})))
        (is false "expected backend preflight anomaly")
        (catch [:anomaly/category :anomalies/unavailable]
               {:keys [backend cmd-path cmd-version probe-response]}
          (is (= :claude backend))
          (is (= "/opt/homebrew/bin/claude" cmd-path))
          (is (= "2.1.89" cmd-version))
          (is (= "backend_preflight_timeout" (get-in probe-response [:error :type]))))))))

(deftest ^{:stratum 0} run-backend-preflight-accepts-claude-result-wrapper-test
  (testing "Claude preflight accepts success envelopes whose result field contains the canonical payload"
    (let [llm-client (llm/mock-client {:output "{\"ok\":true}"})
          output (with-out-str
                   (with-redefs-fn {#'paths/resolve-cli-command-path (fn [_] "/opt/homebrew/bin/claude")
                                    #'probe/read-cli-version (fn [_] {:success true :version "2.1.118 (Claude Code)"})
                                    #'process/run-cli-command (fn [_cmd _timeout-ms & _]
                                                            {:out "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"{\\\"ok\\\":true}\"}"
                                                             :err ""
                                                             :exit 0})}
                     (fn []
                       (#'preflight/run-backend-preflight!
                        false
                        llm-client
                        {:worktree-path "/tmp/runtime-worktree"}))))]
      (is (str/includes? output "Backend: claude"))
      (is (str/includes? output "Backend Path: /opt/homebrew/bin/claude"))
      (is (str/includes? output "Backend Version: 2.1.118 (Claude Code)")))))

(deftest ^{:stratum 0} run-backend-preflight-rejects-claude-error-wrapper-test
  (testing "Claude preflight rejects wrapped payloads when the outer result envelope is not successful"
    (let [llm-client (llm/mock-client {:output "{\"ok\":true}"})]
      (try+
        (with-redefs-fn {#'paths/resolve-cli-command-path (fn [_] "/opt/homebrew/bin/claude")
                         #'probe/read-cli-version (fn [_] {:success true :version "2.1.118 (Claude Code)"})
                         #'process/run-cli-command (fn [_cmd _timeout-ms & _]
                                                 {:out "{\"type\":\"result\",\"subtype\":\"error\",\"is_error\":true,\"result\":\"{\\\"ok\\\":true}\"}"
                                                  :err ""
                                                  :exit 0})}
          (fn []
            (#'preflight/run-backend-preflight!
             false
             llm-client
             {:worktree-path "/tmp/runtime-worktree"})))
        (is false "expected wrapped error result to fail preflight")
        (catch [:anomaly/category :anomalies/unavailable]
               {:keys [probe-response]}
          (is (= "backend_preflight_unexpected_output"
                 (get-in probe-response [:error :data :type])))
          (is (nil? (:content probe-response))))))))

(deftest ^{:stratum 0} run-backend-preflight-exercises-generic-cli-success-path-test
  (testing "non-Claude CLI backends decode streamed CLI output and accept the canonical ok payload"
    (let [llm-client (llm/create-client {:backend :codex})
          seen (atom nil)
          output (with-out-str
                   (with-redefs-fn {#'paths/resolve-cli-command-path (fn [_] "/Users/chris/.local/bin/codex")
                                    #'probe/read-cli-version (fn [_] {:success true :version "1.2.3"})
                                    #'process/run-cli-command (fn [cmd timeout-ms & {:keys [workdir stdin]}]
                                                            (reset! seen {:cmd cmd
                                                                          :timeout-ms timeout-ms
                                                                          :workdir workdir
                                                                          :stdin stdin})
                                                            {:out "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"{\\\"ok\\\":true}\"}}\n{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"
                                                             :err ""
                                                             :exit 0})}
                     (fn []
                       (#'preflight/run-backend-preflight!
                        false
                        llm-client
                        {:worktree-path "/tmp/runtime-worktree"}))))
          {:keys [cmd timeout-ms workdir stdin]} @seen]
      (is (str/includes? output "Backend: codex"))
      (is (str/includes? output "Backend Path: /Users/chris/.local/bin/codex"))
      (is (str/includes? output "Backend Version: 1.2.3"))
      (is (= "/Users/chris/.local/bin/codex" (first cmd)))
      (is (= ["exec" "--json"] (take 2 (rest cmd))))
      (is (= 30000 timeout-ms))
      (is (= "/tmp/runtime-worktree" workdir))
      (testing "codex takes its prompt on stdin, so argv ends in the `-` placeholder"
        (is (= "-" (last cmd)))
        (is (not-any? #{"Reply with exactly {\"ok\":true}"} cmd)))
      (testing "and the probe actually pipes that prompt in"
        (is (= "Reply with exactly {\"ok\":true}" stdin))))))

(deftest ^{:stratum 0} run-backend-preflight-keeps-argv-backend-prompt-in-argv-test
  (testing "a backend declaring :prompt-via :argv still carries the prompt as an argument, with no stdin"
    (let [llm-client (llm/create-client {:backend :opencode})
          seen (atom nil)
          output (with-out-str
                   (with-redefs-fn {#'paths/resolve-cli-command-path (fn [_] "/opt/homebrew/bin/opencode")
                                    #'probe/read-cli-version (fn [_] {:success true :version "0.4.1"})
                                    #'process/run-cli-command (fn [cmd _timeout-ms & {:keys [stdin]}]
                                                            (reset! seen {:cmd cmd :stdin stdin})
                                                            {:out "{\"ok\":true}"
                                                             :err ""
                                                             :exit 0})}
                     (fn []
                       (#'preflight/run-backend-preflight!
                        false
                        llm-client
                        {:worktree-path "/tmp/runtime-worktree"}))))
          {:keys [cmd stdin]} @seen]
      (is (str/includes? output "Backend: opencode"))
      (is (= ["/opt/homebrew/bin/opencode" "run" "Reply with exactly {\"ok\":true}"] cmd))
      (is (nil? stdin)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} run-cli-command-captures-short-lived-process-output-test
  (testing "probe helper captures stdout, stderr, and exit code for short-lived commands"
    (let [result (#'process/run-cli-command [(shell-path) "-lc" "printf ok; printf warn >&2"] 5000)]
      (is (= "ok" (:out result)))
      (is (= "warn" (:err result)))
      (is (= 0 (:exit result))))))

(deftest ^{:stratum 1} run-cli-command-pipes-stdin-to-the-child-test
  (testing "the :stdin option reaches the subprocess"
    (let [result (#'process/run-cli-command [(shell-path) "-lc" "cat"] 5000
                                            :stdin "prompt-on-stdin")]
      (is (= "prompt-on-stdin" (:out result)))
      (is (= 0 (:exit result))))))

(deftest ^{:stratum 1} run-cli-command-closes-stdin-without-input-test
  (testing "omitting :stdin still closes the stream so a reading child sees EOF"
    (let [result (#'process/run-cli-command [(shell-path) "-lc" "cat"] 5000)]
      (is (= "" (:out result)))
      (is (= 0 (:exit result))))))

(deftest ^{:stratum 1} run-cli-command-times-out-fast-test
  (testing "probe helper returns a timeout result instead of hanging the caller"
    (let [result (#'process/run-cli-command [(shell-path) "-lc" "sleep 1"] 10)]
      (is (= -1 (:exit result)))
      (is (= 10 (:timeout-ms result)))
      (is (str/includes? (:err result) "10")))))

(deftest ^{:stratum 1} assert-runtime-alignment-rejects-worktree-mismatch-test
  (testing "explicit execution worktree must survive into runtime context"
    (let [source-root (temp-repo-root)
          source-dir (str (fs/path source-root "work"))]
      (try+
        (#'sut/assert-runtime-alignment!
         {:spec/source-dir source-dir}
         {:source-root source-root
          :worktree-path "/tmp/runtime-worktree"
          :execution/opts {:worktree-path "/tmp/expected-worktree"}})
        (is false "expected execution worktree mismatch anomaly")
        (catch [:anomaly/category :anomalies/incorrect]
               {:keys [expected-worktree actual-worktree]}
          (is (= "/tmp/expected-worktree" expected-worktree))
          (is (= "/tmp/runtime-worktree" actual-worktree)))))))

(deftest ^{:stratum 1} assert-runtime-alignment-rejects-source-dir-outside-root-test
  (testing "spec source directory must stay under the resolved source root"
    (let [source-root (temp-repo-root)]
      (try+
        (#'sut/assert-runtime-alignment!
         {:spec/source-dir "/tmp/outside-spec-root"}
         {:source-root source-root
          :worktree-path "/tmp/runtime-worktree"
          :execution/opts {:worktree-path "/tmp/runtime-worktree"}})
        (is false "expected source-dir alignment anomaly")
        (catch [:anomaly/category :anomalies/incorrect]
               {:keys [source-dir]}
          (is (= "/tmp/outside-spec-root" source-dir)))))))

(deftest ^{:stratum 1} assert-runtime-alignment-normalizes-parent-segments-test
  (testing "source-dir alignment accepts normalized paths under the source root"
    (let [source-root (temp-repo-root)
          nested-work (str (fs/path source-root "work" "nested"))]
      (fs/create-dirs nested-work)
      (#'sut/assert-runtime-alignment!
       {:spec/source-dir (str (fs/path nested-work ".."))}
       {:source-root source-root
        :worktree-path "/tmp/runtime-worktree"
        :execution/opts {:worktree-path "/tmp/runtime-worktree"}})
      (is true))))
