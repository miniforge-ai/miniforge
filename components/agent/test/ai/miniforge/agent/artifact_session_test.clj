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

(ns ai.miniforge.agent.artifact-session-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.miniforge.agent.artifact-session :as session]))

;------------------------------------------------------------------------------ Layer 0
;; Session lifecycle tests

(deftest create-session-test
  (testing "creates temp dir and returns session map"
    (let [s (session/create-session!)]
      (try
        (is (string? (:dir s)))
        (is (string? (:mcp-config-path s)))
        (is (string? (:artifact-path s)))
        (is (map? (:pre-session-snapshot s)))
        (is (.exists (io/file (:dir s))))
        (is (.startsWith (:dir s) (System/getProperty "java.io.tmpdir")))
        (is (.endsWith (:mcp-config-path s) "/mcp-config.json"))
        (is (.endsWith (:artifact-path s) "/artifact.edn"))
        (finally
          (session/cleanup-session! s))))))

(deftest validate-session-test
  (testing "valid session passes"
    (let [result (session/validate-session {:dir "/tmp/x"
                                            :mcp-config-path "/tmp/x/mcp-config.json"
                                            :artifact-path "/tmp/x/artifact.edn"})]
      (is (:valid? result))))

  (testing "missing keys fail"
    (let [result (session/validate-session {:dir "/tmp/x"})]
      (is (not (:valid? result)))
      (is (some? (:errors result)))))

  (testing "empty string values fail"
    (let [result (session/validate-session {:dir ""
                                            :mcp-config-path "/tmp/x/c.json"
                                            :artifact-path "/tmp/x/a.edn"})]
      (is (not (:valid? result)))))

  (testing "empty map fails"
    (let [result (session/validate-session {})]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 1
;; MCP config generation tests

(deftest write-mcp-config-test
  (testing "writes valid JSON with mcpServers.artifact.command and --artifact-dir"
    (let [s (session/create-session!)]
      (try
        (session/write-mcp-config! s)
        (let [config-str (slurp (:mcp-config-path s))
              config (json/parse-string config-str true)]
          (is (map? config))
          (is (= "bb" (get-in config [:mcpServers :context :command])))
          (let [args (get-in config [:mcpServers :context :args])]
            (is (vector? args))
            (is (some #(= "--artifact-dir" %) args))
            (is (some #(= (:dir s) %) args))))
        (finally
          (session/cleanup-session! s)))))

  (testing "returns session for threading (with mcp-allowed-tools added)"
    (let [s (session/create-session!)]
      (try
        (let [result (session/write-mcp-config! s)]
          (is (= (:dir s) (:dir result)))
          (is (= (:mcp-config-path s) (:mcp-config-path result)))
          (is (= (:artifact-path s) (:artifact-path result)))
          (is (vector? (:mcp-allowed-tools result)))
          (is (every? #(str/starts-with? % "mcp__context__") (:mcp-allowed-tools result))))
        (finally
          (session/cleanup-session! s))))))

;------------------------------------------------------------------------------ Layer 2
;; Artifact reading tests

(deftest read-artifact-valid-edn-test
  (testing "reads and parses valid artifact EDN"
    (let [s (session/create-session!)
          artifact {:code/id "550e8400-e29b-41d4-a716-446655440000"
                    :code/summary "test artifact"
                    :code/created-at "2026-02-28T12:00:00Z"}]
      (try
        (spit (:artifact-path s) (pr-str artifact))
        (let [result (session/read-artifact s)]
          (is (map? result))
          (is (instance? java.util.UUID (:code/id result)))
          (is (= "test artifact" (:code/summary result)))
          (is (instance? java.util.Date (:code/created-at result))))
        (finally
          (session/cleanup-session! s))))))

(deftest read-artifact-missing-file-test
  (testing "returns nil when file does not exist"
    (let [s (session/create-session!)]
      (try
        (is (nil? (session/read-artifact s)))
        (finally
          (session/cleanup-session! s))))))

(deftest read-artifact-invalid-edn-test
  (testing "returns nil for invalid EDN (no throw)"
    (let [s (session/create-session!)]
      (try
        (spit (:artifact-path s) "{{{invalid not edn")
        (is (nil? (session/read-artifact s)))
        (finally
          (session/cleanup-session! s))))))

;------------------------------------------------------------------------------ Layer 2.1
;; Worktree artifact tests — container-promotion submission path

(defn- make-worktree-with-plan [plan-edn-str]
  (let [wt (io/file (System/getProperty "java.io.tmpdir")
                    (str "mf-wt-test-" (random-uuid)))
        _  (.mkdirs (io/file wt ".miniforge"))]
    (when plan-edn-str
      (spit (io/file wt ".miniforge" "plan.edn") plan-edn-str))
    (.getPath wt)))

(deftest read-worktree-artifact-reads-plan-edn-test
  (testing "reads .miniforge/<role>.edn from the worktree"
    (let [wt (make-worktree-with-plan
              (pr-str {:plan/id "550e8400-e29b-41d4-a716-446655440000"
                       :plan/name "t"
                       :plan/tasks []}))]
      (try
        (let [result (session/read-worktree-artifact wt :plan)]
          (is (map? result))
          (is (= "t" (:plan/name result)))
          (is (instance? java.util.UUID (:plan/id result))
              "UUID strings should be parsed through parse-uuid-strings"))
        (finally
          (io/delete-file (io/file wt ".miniforge" "plan.edn") true)
          (io/delete-file (io/file wt ".miniforge") true)
          (io/delete-file (io/file wt) true))))))

(deftest read-worktree-artifact-missing-file-test
  (testing "returns nil when .miniforge/<role>.edn is absent"
    (let [wt (make-worktree-with-plan nil)]
      (try
        (is (nil? (session/read-worktree-artifact wt :plan)))
        (finally
          (io/delete-file (io/file wt ".miniforge") true)
          (io/delete-file (io/file wt) true))))))

(deftest read-worktree-artifact-nil-args-test
  (testing "returns nil for nil workdir or nil role (no throw)"
    (is (nil? (session/read-worktree-artifact nil :plan)))
    (is (nil? (session/read-worktree-artifact "/tmp" nil)))
    (is (nil? (session/read-worktree-artifact nil nil)))))

(deftest read-worktree-artifact-invalid-edn-test
  (testing "returns nil for unparseable file (no throw)"
    (let [wt (make-worktree-with-plan "{{{ not edn")]
      (try
        (is (nil? (session/read-worktree-artifact wt :plan)))
        (finally
          (io/delete-file (io/file wt ".miniforge" "plan.edn") true)
          (io/delete-file (io/file wt ".miniforge") true)
          (io/delete-file (io/file wt) true))))))

;------------------------------------------------------------------------------ Layer 2.5
;; UUID/instant parsing tests (via read-artifact round-trip)

(deftest parse-uuid-strings-test
  (testing "UUID string keys converted to java.util.UUID"
    (let [s (session/create-session!)
          uuid-str "550e8400-e29b-41d4-a716-446655440000"
          artifact {:code/id uuid-str
                    :code/summary "test"}]
      (try
        (spit (:artifact-path s) (pr-str artifact))
        (let [result (session/read-artifact s)]
          (is (instance? java.util.UUID (:code/id result)))
          (is (= (java.util.UUID/fromString uuid-str) (:code/id result))))
        (finally
          (session/cleanup-session! s)))))

  (testing "created-at string converted to java.util.Date"
    (let [s (session/create-session!)
          artifact {:code/id "550e8400-e29b-41d4-a716-446655440000"
                    :code/created-at "2026-02-28T12:00:00Z"}]
      (try
        (spit (:artifact-path s) (pr-str artifact))
        (let [result (session/read-artifact s)]
          (is (instance? java.util.Date (:code/created-at result))))
        (finally
          (session/cleanup-session! s)))))

  (testing "non-UUID/instant values are preserved"
    (let [s (session/create-session!)
          artifact {:code/id "550e8400-e29b-41d4-a716-446655440000"
                    :code/summary "unchanged"
                    :code/language "clojure"}]
      (try
        (spit (:artifact-path s) (pr-str artifact))
        (let [result (session/read-artifact s)]
          (is (= "unchanged" (:code/summary result)))
          (is (= "clojure" (:code/language result))))
        (finally
          (session/cleanup-session! s))))))

(deftest parse-uuid-strings-nested-vectors-test
  (testing "handles vector of maps with :task/id"
    (let [s (session/create-session!)
          uuid1 "550e8400-e29b-41d4-a716-446655440001"
          uuid2 "550e8400-e29b-41d4-a716-446655440002"
          artifact {:plan/id "550e8400-e29b-41d4-a716-446655440000"
                    :plan/tasks [{:task/id uuid1
                                  :task/description "First task"}
                                 {:task/id uuid2
                                  :task/description "Second task"}]}]
      (try
        (spit (:artifact-path s) (pr-str artifact))
        (let [result (session/read-artifact s)]
          (is (instance? java.util.UUID (:plan/id result)))
          (is (= 2 (count (:plan/tasks result))))
          (is (instance? java.util.UUID (:task/id (first (:plan/tasks result)))))
          (is (instance? java.util.UUID (:task/id (second (:plan/tasks result))))))
        (finally
          (session/cleanup-session! s))))))

;------------------------------------------------------------------------------ Layer 1.5
;; Multi-backend MCP config tests
;;
;; These tests use isolated temp directories to avoid conflicts with
;; concurrent test runs that also call write-mcp-config! (e.g. workflow tests).

(deftest write-mcp-config-tracks-cleanup-files-test
  (testing "session has :mcp-cleanup-files after write-mcp-config!"
    (let [s (-> (session/create-session!) session/write-mcp-config!)]
      (try
        (is (vector? (:mcp-cleanup-files s)))
        (is (= 2 (count (:mcp-cleanup-files s))))
        (is (some #(str/ends-with? % "config.toml") (:mcp-cleanup-files s)))
        (is (some #(str/ends-with? % "mcp.json") (:mcp-cleanup-files s)))
        (finally
          (session/cleanup-session! s))))))

(deftest write-codex-mcp-config-test
  (testing "writes .codex/config.toml tracked in cleanup files"
    (let [s (-> (session/create-session!) session/write-mcp-config!)
          codex-file (first (filter #(str/ends-with? % "config.toml")
                                    (:mcp-cleanup-files s)))]
      (try
        ;; Verify the codex path is tracked for cleanup
        (is (some? codex-file))
        (is (str/ends-with? codex-file "config.toml"))
        (finally
          (session/cleanup-session! s))))))

(deftest write-cursor-mcp-config-test
  (testing "writes .cursor/mcp.json tracked in cleanup files"
    (let [s (-> (session/create-session!) session/write-mcp-config!)
          cursor-file (first (filter #(str/ends-with? % "mcp.json")
                                     (:mcp-cleanup-files s)))]
      (try
        ;; Verify the cursor path is tracked for cleanup
        (is (some? cursor-file))
        (is (str/ends-with? cursor-file "mcp.json"))
        (finally
          (session/cleanup-session! s))))))

(deftest cleanup-codex-config-test
  (testing "cleanup removes artifact block but preserves other config"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "codex-test-" (random-uuid)))
          config-file (io/file tmp "config.toml")
          cleanup-fn  @#'session/cleanup-codex-mcp-config!]
      (try
        (.mkdirs tmp)
        ;; Write a config with an existing section + an artifact block
        (spit config-file (str "[some_other_section]\nkey = \"value\"\n\n"
                               "[mcp_servers.artifact]\n"
                               "command = \"bb\"\n"
                               "args = [\"miniforge\",\"mcp-serve\"]\n"))
        (is (str/includes? (slurp config-file) "[mcp_servers.artifact]"))
        ;; Cleanup should remove artifact block but preserve the rest
        (cleanup-fn (str config-file))
        (let [content (slurp config-file)]
          (is (not (str/includes? content "[mcp_servers.artifact]")))
          (is (str/includes? content "[some_other_section]")))
        (finally
          (doseq [f (reverse (file-seq tmp))]
            (.delete ^java.io.File f)))))))

(deftest cleanup-codex-config-removes-nested-artifact-tables-test
  (testing "cleanup removes nested artifact tool tables as part of the same subtree"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "codex-nested-test-" (random-uuid)))
          config-file (io/file tmp "config.toml")
          cleanup-fn  @#'session/cleanup-codex-mcp-config!]
      (try
        (.mkdirs tmp)
        (spit config-file (str "sandbox_mode = \"workspace-write\"\n"
                               "\n"
                               "[mcp_servers.artifact]\n"
                               "command = \"bb\"\n"
                               "args = [\"miniforge\",\"mcp-serve\"]\n"
                               "\n"
                               "[mcp_servers.artifact.tools.context_read]\n"
                               "approval_mode = \"approve\"\n"
                               "\n"
                               "[sandbox_workspace_write]\n"
                               "network_access = true\n"))
        (cleanup-fn (str config-file))
        (let [content (slurp config-file)]
          (is (not (str/includes? content "[mcp_servers.artifact]")))
          (is (not (str/includes? content "[mcp_servers.artifact.tools.context_read]")))
          (is (str/includes? content "sandbox_mode = \"workspace-write\""))
          (is (str/includes? content "[sandbox_workspace_write]")))
        (finally
          (doseq [f (reverse (file-seq tmp))]
            (.delete ^java.io.File f)))))))

(deftest cleanup-cursor-config-test
  (testing "cleanup removes artifact entry but preserves other servers"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "cursor-test-" (random-uuid)))
          config-file (io/file tmp "mcp.json")
          cleanup-fn  @#'session/cleanup-cursor-mcp-config!]
      (try
        (.mkdirs tmp)
        ;; Write a JSON config with an existing server + artifact
        (spit config-file (json/generate-string
                            {"mcpServers" {"other" {"command" "other-cmd"}
                                           "artifact" {"command" "bb"
                                                       "args" ["miniforge" "mcp-serve"]}}}))
        (let [config (json/parse-string (slurp config-file))]
          (is (contains? (get config "mcpServers") "artifact")))
        ;; Cleanup should remove artifact but preserve other
        (cleanup-fn (str config-file))
        (let [config (json/parse-string (slurp config-file))]
          (is (not (contains? (get config "mcpServers") "artifact")))
          (is (= "other-cmd" (get-in config ["mcpServers" "other" "command"]))))
        (finally
          (doseq [f (reverse (file-seq tmp))]
            (.delete ^java.io.File f)))))))
;------------------------------------------------------------------------------ Layer 3
;; Cleanup and macro tests

(deftest cleanup-session-test
  (testing "removes temp dir and all files"
    (let [s (session/create-session!)]
      (session/write-mcp-config! s)
      (spit (:artifact-path s) "{:test true}")
      (is (.exists (io/file (:dir s))))
      (is (.exists (io/file (:mcp-config-path s))))
      (is (.exists (io/file (:artifact-path s))))
      (session/cleanup-session! s)
      (is (not (.exists (io/file (:dir s))))))))

(deftest with-artifact-session-test
  (testing "returns {:llm-result ... :artifact ...} and cleans up"
    (let [captured-dir (atom nil)
          result (session/with-artifact-session [sess]
                   (reset! captured-dir (:dir sess))
                   ;; Simulate MCP server writing artifact
                   (spit (:artifact-path sess)
                          (pr-str {:code/id "550e8400-e29b-41d4-a716-446655440000"
                                   :code/summary "from macro test"}))
                   :body-return-value)]
      (is (= :body-return-value (:llm-result result)))
      (is (map? (:artifact result)))
      (is (instance? java.util.UUID (:code/id (:artifact result))))
      (is (= "from macro test" (:code/summary (:artifact result))))
      ;; Directory should be cleaned up
      (is (not (.exists (io/file @captured-dir))))))

  (testing "cleans up even on exception"
    (let [captured-dir (atom nil)]
      (try
        (session/with-artifact-session [sess]
          (reset! captured-dir (:dir sess))
          (throw (ex-info "test error" {})))
        (catch Exception _))
      (is (not (.exists (io/file @captured-dir))))))

  (testing "returns nil artifact when no file written"
    (let [result (session/with-artifact-session [_sess]
                   :no-artifact)]
      (is (= :no-artifact (:llm-result result)))
      (is (nil? (:artifact result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.artifact-session-test)
  :leave-this-here)
