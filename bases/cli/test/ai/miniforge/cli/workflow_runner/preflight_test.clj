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
   [ai.miniforge.cli.workflow-runner :as sut]))

(defn- temp-repo-root []
  (let [root (str (fs/create-temp-dir {:prefix "mf-preflight-"}))]
    (fs/create-dirs (fs/path root ".git"))
    root))

(deftest assert-runtime-alignment-rejects-worktree-mismatch-test
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

(deftest assert-runtime-alignment-rejects-source-dir-outside-root-test
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

(deftest print-runtime-provenance-includes-startup-warnings-test
  (testing "startup provenance prints upstream and source checkout warnings"
    (let [output (with-out-str
                   (#'sut/print-runtime-provenance!
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

(deftest assert-runtime-alignment-normalizes-parent-segments-test
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

(deftest run-backend-preflight-stamps-resolved-cli-and-version-test
  (testing "backend preflight resolves the inherited CLI path and prints the stamp"
    (let [llm-client (llm/mock-client {:output "{\"ok\":true}"})
          preflight-client (atom nil)
          output (with-out-str
                   (with-redefs-fn {#'sut/resolve-cli-command-path (fn [_] "/Users/chris/.local/bin/claude")
                                    #'sut/read-cli-version (fn [_] {:success true :version "2.1.126"})
                                    #'sut/run-claude-backend-preflight (fn [cmd-path workdir]
                                                                         (is (= "/Users/chris/.local/bin/claude" cmd-path))
                                                                         (is (= "/tmp/runtime-worktree" workdir))
                                                                         {:success true :content "{\"ok\":true}" :exit-code 0})
                                    #'llm/create-client (fn [opts]
                                                         (reset! preflight-client opts)
                                                         {:config {:backend (:backend opts) :model (:model opts)}})
                                    #'llm/complete (fn [_client _request]
                                                     (is false "Claude preflight should use the direct CLI probe path"))}
                     (fn []
                       (#'sut/run-backend-preflight!
                        false
                        llm-client
                        {:worktree-path "/tmp/runtime-worktree"}))))]
      (is (str/includes? output "Backend: claude"))
      (is (str/includes? output "Backend Path: /Users/chris/.local/bin/claude"))
      (is (str/includes? output "Backend Version: 2.1.126"))
      (is (nil? @preflight-client)))))

(deftest run-backend-preflight-fails-closed-on-bad-cli-health-test
  (testing "backend preflight carries the resolved path and version when the probe fails"
    (let [llm-client (llm/mock-client {:output "{\"ok\":true}"})]
      (try+
        (with-redefs-fn {#'sut/resolve-cli-command-path (fn [_] "/opt/homebrew/bin/claude")
                         #'sut/read-cli-version (fn [_] {:success true :version "2.1.89"})
                         #'sut/run-claude-backend-preflight (fn [_cmd-path _workdir]
                                                              {:success false
                                                               :error {:type "backend_preflight_timeout"
                                                                       :message "Process timed out after 10000ms"}
                                                               :exit-code -1})}
          (fn []
            (#'sut/run-backend-preflight!
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

(deftest run-backend-preflight-accepts-claude-result-wrapper-test
  (testing "Claude preflight accepts success envelopes whose result field contains the canonical payload"
    (let [llm-client (llm/mock-client {:output "{\"ok\":true}"})
          output (with-out-str
                   (with-redefs-fn {#'sut/resolve-cli-command-path (fn [_] "/opt/homebrew/bin/claude")
                                    #'sut/read-cli-version (fn [_] {:success true :version "2.1.118 (Claude Code)"})
                                    #'sut/run-cli-command (fn [_cmd _timeout-ms & _]
                                                            {:out "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"{\\\"ok\\\":true}\"}"
                                                             :err ""
                                                             :exit 0})}
                     (fn []
                       (#'sut/run-backend-preflight!
                        false
                        llm-client
                        {:worktree-path "/tmp/runtime-worktree"}))))]
      (is (str/includes? output "Backend: claude"))
      (is (str/includes? output "Backend Path: /opt/homebrew/bin/claude"))
      (is (str/includes? output "Backend Version: 2.1.118 (Claude Code)")))))

(deftest run-backend-preflight-exercises-generic-cli-success-path-test
  (testing "non-Claude CLI backends decode streamed CLI output and accept the canonical ok payload"
    (let [llm-client (llm/create-client {:backend :codex})
          seen-cmd (atom nil)
          seen-timeout (atom nil)
          seen-workdir (atom nil)
          output (with-out-str
                   (with-redefs-fn {#'sut/resolve-cli-command-path (fn [_] "/Users/chris/.local/bin/codex")
                                    #'sut/read-cli-version (fn [_] {:success true :version "1.2.3"})
                                    #'sut/run-cli-command (fn [cmd timeout-ms & {:keys [workdir]}]
                                                            (reset! seen-cmd cmd)
                                                            (reset! seen-timeout timeout-ms)
                                                            (reset! seen-workdir workdir)
                                                            {:out "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"{\\\"ok\\\":true}\"}}\n{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"
                                                             :err ""
                                                             :exit 0})}
                     (fn []
                       (#'sut/run-backend-preflight!
                        false
                        llm-client
                        {:worktree-path "/tmp/runtime-worktree"}))))]
      (is (str/includes? output "Backend: codex"))
      (is (str/includes? output "Backend Path: /Users/chris/.local/bin/codex"))
      (is (str/includes? output "Backend Version: 1.2.3"))
      (is (= "/Users/chris/.local/bin/codex" (first @seen-cmd)))
      (is (= ["exec" "--json"] (take 2 (rest @seen-cmd))))
      (is (= "Reply with exactly {\"ok\":true}" (last @seen-cmd)))
      (is (= 10000 @seen-timeout))
      (is (= "/tmp/runtime-worktree" @seen-workdir)))))
