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
;; Test fixtures and factories

(def ^:private default-uuid-str
  "Stable UUID literal used across artifact fixtures. Real values are random;
   tests pin a constant so assertions can compare against a known UUID."
  "550e8400-e29b-41d4-a716-446655440000")

(defn- code-artifact
  "Factory for code-artifact EDN maps written to a session's `:artifact-path`.
   Defaults exercise the parser's UUID/instant coercion paths; pass any
   subset of overrides to vary individual fields per test."
  [& {:as overrides}]
  (merge {:code/id           default-uuid-str
          :code/summary      "test artifact"
          :code/created-at   "2026-02-28T12:00:00Z"}
         overrides))

(defn- plan-artifact
  "Factory for plan-artifact EDN maps written to `.miniforge/plan.edn` in
   the worktree. Defaults yield an empty task vector; override `:plan/tasks`
   to exercise nested-vector UUID parsing."
  [& {:as overrides}]
  (merge {:plan/id    default-uuid-str
          :plan/name  "t"
          :plan/tasks []}
         overrides))

;------------------------------------------------------------------------------ Layer 1
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
          (session/cleanup-session! s)))))

  (testing ":explicit-workdir? is false when no workdir provided"
    ;; The JVM CWD fallback must not be treated as an explicit workdir —
    ;; otherwise stale .miniforge/ artifacts in the dev workspace pollute
    ;; the worktree-artifacts scan and suppress the WARN signal.
    (let [s (session/create-session!)]
      (try
        (is (false? (:explicit-workdir? s))
            "no-arg create-session! must set :explicit-workdir? false")
        (finally
          (session/cleanup-session! s)))))

  (testing ":explicit-workdir? is true when workdir explicitly provided"
    (let [s (session/create-session! {:workdir "/tmp"})]
      (try
        (is (true? (:explicit-workdir? s))
            "create-session! with explicit :workdir must set :explicit-workdir? true")
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
          ;; Mixed-shape: MCP entries are {:mcp/server :mcp/tool} maps;
          ;; native entries are bare keywords. Adapters translate.
          (is (every? #(or (map? %) (keyword? %)) (:mcp-allowed-tools result))))
        (finally
          (session/cleanup-session! s))))))

;------------------------------------------------------------------------------ Layer 2
;; Artifact reading tests

(deftest read-artifact-valid-edn-test
  (testing "reads and parses valid artifact EDN"
    (let [s (session/create-session!)
          artifact (code-artifact)]
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
          (session/cleanup-session! s)))))

  (testing "does NOT emit ERROR to stderr when MCP file is absent"
    ;; Worktree-promotion is the primary submission channel; a missing MCP
    ;; artifact file is not an error on its own. run-session emits WARN only
    ;; when BOTH sources are empty.
    (let [s   (session/create-session!)
          err (java.io.StringWriter.)]
      (try
        (binding [*err* err]
          (session/read-artifact s))
        (is (not (str/includes? (str err) "ERROR"))
            "read-artifact must not emit ERROR when artifact file is missing")
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
    (let [wt (make-worktree-with-plan (pr-str (plan-artifact)))]
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
          artifact (code-artifact :code/summary "test")]
      (try
        (spit (:artifact-path s) (pr-str artifact))
        (let [result (session/read-artifact s)]
          (is (instance? java.util.UUID (:code/id result)))
          (is (= (java.util.UUID/fromString default-uuid-str) (:code/id result))))
        (finally
          (session/cleanup-session! s)))))

  (testing "created-at string converted to java.util.Date"
    (let [s (session/create-session!)
          artifact (code-artifact)]
      (try
        (spit (:artifact-path s) (pr-str artifact))
        (let [result (session/read-artifact s)]
          (is (instance? java.util.Date (:code/created-at result))))
        (finally
          (session/cleanup-session! s)))))

  (testing "non-UUID/instant values are preserved"
    (let [s (session/create-session!)
          artifact (code-artifact :code/summary "unchanged"
                                  :code/language "clojure")]
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
          artifact (plan-artifact
                    :plan/tasks [{:task/id uuid1
                                  :task/description "First task"}
                                 {:task/id uuid2
                                  :task/description "Second task"}])]
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
        (is (= 3 (count (:mcp-cleanup-files s))))
        (is (some #(str/ends-with? % "config.toml") (:mcp-cleanup-files s)))
        (is (some #(str/ends-with? % "mcp.json") (:mcp-cleanup-files s)))
        (is (some #(str/ends-with? % "cli.json") (:mcp-cleanup-files s)))
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
        ;; Verify the artifact block is marked required so Codex fails
        ;; loudly if our MCP server can't initialize, instead of silently
        ;; running without it.
        (let [content (slurp codex-file)]
          (is (str/includes? content "[mcp_servers.artifact]"))
          (is (str/includes? content "default_tools_approval_mode = \"approve\""))
          (is (str/includes? content "required = true")))
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

(deftest cursor-permission-allow-test
  (testing "mcp-tools translate to Mcp(...) + Write(**) rules"
    (let [allow (session/cursor-permission-allow session/mcp-tools nil)]
      (is (some #(= "Mcp(context:context_read)" %) allow))
      (is (some #(= "Mcp(context:submit)" %) allow))
      (is (some #(= "Write(**)" %) allow))
      (testing "Write/Edit/MultiEdit collapse to a single Write(**) rule"
        (is (= 1 (count (filter #(= "Write(**)" %) allow)))))))
  (testing "default-deny: disallowing all write tools drops the write rule"
    (let [allow (session/cursor-permission-allow session/mcp-tools ["Write" "Edit" "MultiEdit"])]
      (is (not (some #(= "Write(**)" %) allow)))
      (is (some #(str/starts-with? % "Mcp(") allow))))
  (testing "disallowing ANY write tool drops Write(**) (Cursor can't distinguish)"
    (doseq [blocked [["Write"] ["Edit"] ["MultiEdit"]]]
      (let [allow (session/cursor-permission-allow session/mcp-tools blocked)]
        (is (not (some #(= "Write(**)" %) allow))
            (str "Write(**) should be dropped when disallowing " blocked))))))

(deftest write-cursor-permissions-test
  (testing "writes .cursor/cli.json with allow + secret-deny baseline"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "cursor-perms-" (random-uuid)))]
      (try
        (.mkdirs tmp)
        (let [path   (session/write-cursor-permissions! (str tmp) session/mcp-tools nil)
              config (json/parse-string (slurp path))]
          (is (str/ends-with? path "cli.json"))
          (is (contains? (set (get-in config ["permissions" "allow"])) "Write(**)"))
          (is (= (set session/cursor-permission-deny)
                 (set (get-in config ["permissions" "deny"])))))
        (finally
          (doseq [f (reverse (file-seq tmp))] (.delete ^java.io.File f)))))))

(deftest write-cursor-permissions-narrows-on-rewrite-test
  (testing "rewriting with a narrower disallow set shrinks the allowlist"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "cursor-perms-narrow-" (random-uuid)))]
      (try
        (.mkdirs tmp)
        ;; First write: full allow (no disallow) includes Write(**).
        (session/write-cursor-permissions! (str tmp) session/mcp-tools nil)
        (let [path (str (io/file tmp ".cursor" "cli.json"))]
          (is (some #(= "Write(**)" %)
                    (get-in (json/parse-string (slurp path)) ["permissions" "allow"])))
          ;; Rewrite disallowing Write: managed Write(**) is stripped, MCP stays.
          (session/write-cursor-permissions! (str tmp) session/mcp-tools ["Write" "Edit" "MultiEdit"])
          (let [allow (get-in (json/parse-string (slurp path)) ["permissions" "allow"])]
            (is (not (some #(= "Write(**)" %) allow)))
            (is (some #(str/starts-with? % "Mcp(") allow))))
        (finally
          (doseq [f (reverse (file-seq tmp))] (.delete ^java.io.File f)))))))

(deftest write-cursor-permissions-for-session-test
  (testing "no-op for capsule sessions"
    (is (nil? (session/write-cursor-permissions-for-session!
               {:capsule? true :config-root "/tmp/should-not-write"} ["Write"]))))
  (testing "host session writes to its config-root"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "cursor-perms-session-" (random-uuid)))]
      (try
        (.mkdirs tmp)
        (let [path (session/write-cursor-permissions-for-session!
                    {:config-root (str tmp) :mcp-allowed-tools session/mcp-tools}
                    ["Write" "Edit" "MultiEdit"])
              allow (get-in (json/parse-string (slurp path)) ["permissions" "allow"])]
          (is (str/ends-with? path "cli.json"))
          (is (not (some #(= "Write(**)" %) allow))))
        (finally
          (doseq [f (reverse (file-seq tmp))] (.delete ^java.io.File f)))))))

(deftest cleanup-cursor-permissions-restores-original-test
  (testing "cleanup restores a pre-existing cli.json byte-for-byte"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "cursor-perms-restore-" (random-uuid)))
          dir (io/file tmp ".cursor")
          cli (io/file dir "cli.json")
          cleanup-fn @#'session/cleanup-cursor-permissions!]
      (try
        (.mkdirs dir)
        ;; A user rule that is byte-identical to a managed rule — the case the
        ;; old string-stripping cleanup could not preserve.
        (let [original (json/generate-string {"permissions" {"allow" ["Shell(git)" "Write(**)"]}})]
          (spit cli original)
          (session/backup-cursor-permissions! (str tmp))
          (session/write-cursor-permissions! (str tmp) session/mcp-tools ["Write" "Edit" "MultiEdit"])
          (is (not= original (slurp cli)) "writer mutates the live file")
          (cleanup-fn (str cli))
          (is (= original (slurp cli)) "cleanup restores the exact original")
          (is (not (.exists (io/file dir (str "cli.json" @#'session/cursor-permissions-backup-suffix))))
              "backup file is removed after restore"))
        (finally
          (doseq [f (reverse (file-seq tmp))] (.delete ^java.io.File f)))))))

(deftest cleanup-cursor-permissions-deletes-generated-test
  (testing "cleanup deletes the file when miniforge created it (no prior file)"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "cursor-perms-del-" (random-uuid)))
          cli (io/file tmp ".cursor" "cli.json")
          cleanup-fn @#'session/cleanup-cursor-permissions!]
      (try
        (.mkdirs tmp)
        (session/backup-cursor-permissions! (str tmp)) ; no-op: nothing to back up
        (session/write-cursor-permissions! (str tmp) session/mcp-tools nil)
        (is (.exists cli))
        (cleanup-fn (str cli))
        (is (not (.exists cli)) "generated file is deleted, leaving no stray config")
        (finally
          (doseq [f (reverse (file-seq tmp))] (.delete ^java.io.File f)))))))

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
                          (pr-str (code-artifact :code/summary "from macro test")))
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
      (is (nil? (:artifact result)))))

  (testing "deprecated macro: does not emit WARN when no artifact is written"
    ;; `with-artifact-session` is a simplified legacy surface that bypasses
    ;; `run-session` and its WARN logic entirely. Document this explicitly so
    ;; callers know to migrate to `with-session` for the full WARN semantics.
    (let [err    (java.io.StringWriter.)
          result (binding [*err* err]
                   (session/with-artifact-session [_sess] :no-artifact))]
      (is (= :no-artifact (:llm-result result)))
      (is (nil? (:artifact result)))
      (is (not (str/includes? (str err) "WARN"))
          "with-artifact-session intentionally omits WARN — migrate to with-session"))))

;------------------------------------------------------------------------------ Layer 3.5
;; run-session WARN graduation tests (via with-session public surface)
;;
;; run-session is private; these tests drive it through with-session, which
;; is the public, production-path entry that selects host vs. capsule mode.

(deftest with-session-warn-when-both-sources-absent-test
  (testing "suppresses WARN when no explicit workdir was provided"
    ;; Without an explicit workdir, the worktree-artifact scan is intentionally
    ;; skipped. Its empty result should not be diagnosed as a failed artifact
    ;; submission.
    (let [err    (java.io.StringWriter.)
          result (binding [*err* err]
                   (session/with-session {} (constantly :no-submit)))]
      (is (= :no-submit (:llm-result result)))
      (is (nil? (:artifact result)))
      (is (not (str/includes? (str err) "WARN"))
          "run-session must not warn when the worktree scan was not engaged")
      (is (not (str/includes? (str err) "ERROR"))
          "ERROR must never be emitted from the artifact-session layer")))

  (testing "emits WARN when explicit workdir has neither MCP nor worktree artifact"
    ;; Both submission channels are empty after an explicit worktree scan -
    ;; this is the one case that should surface a WARN.
    (let [wt     (make-worktree-with-plan nil)
          err    (java.io.StringWriter.)
          ctx    {:execution/worktree-path wt}
          result (binding [*err* err]
                   (session/with-session ctx (constantly :no-submit)))]
      (try
        (is (= :no-submit (:llm-result result)))
        (is (nil? (:artifact result)))
        (is (empty? (:worktree-artifacts result)))
        (is (str/includes? (str err) "WARN")
            "run-session must warn when explicit workdir scan finds no artifact")
        (finally
          (doseq [^java.io.File f (reverse (file-seq (io/file wt)))]
            (io/delete-file f true))))))

  (testing "suppresses WARN when worktree artifact exists but MCP file is absent"
    ;; Container-promotion path: agent wrote .miniforge/plan.edn into the
    ;; worktree but did not call MCP submit_artifact. WARN must be suppressed
    ;; because the primary submission channel was satisfied.
    (let [wt     (make-worktree-with-plan (pr-str (plan-artifact)))
          err    (java.io.StringWriter.)
          ctx    {:execution/worktree-path wt}
          result (binding [*err* err]
                   (session/with-session ctx (constantly :worktree-only)))]
      (try
        (is (= :worktree-only (:llm-result result)))
        (is (map? (get (:worktree-artifacts result) :plan))
            "worktree-artifacts must contain :plan when .miniforge/plan.edn exists")
        (is (not (str/includes? (str err) "WARN"))
            "WARN must be suppressed when a worktree artifact covers the missing MCP file")
        (finally
          (doseq [^java.io.File f (reverse (file-seq (io/file wt)))]
            (.delete f))))))

  (testing "suppresses WARN when MCP artifact is written (no worktree)"
    ;; Third combination: MCP submit_artifact was called → file present,
    ;; but no worktree-promotion path exists. WARN must NOT fire because the
    ;; primary submission channel (MCP) was satisfied.
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (session/with-session {}
          (fn [sess]
            (spit (:artifact-path sess) (pr-str (code-artifact)))
            :mcp-submitted)))
      (is (not (str/includes? (str err) "WARN"))
          "WARN must be suppressed when MCP artifact is present"))))

(deftest with-session-no-warn-when-mcp-artifact-present-test
  (testing "no WARN emitted when MCP artifact file is written"
    ;; Explicit cross-check: MCP artifact present, worktree absent.
    ;; Verifies the WARN gate checks mcp-artifact before firing.
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (session/with-session {}
          (fn [sess]
            (spit (:artifact-path sess) (pr-str (code-artifact)))
            :done)))
      (is (not (str/includes? (str err) "WARN"))
          "no WARN when MCP artifact is present and worktree is absent"))))

(deftest with-session-no-artifact-warn-suppressed-when-parse-failed-test
  (testing "no-artifact WARN is suppressed when worktree file exists but fails to parse"
    ;; If the agent wrote plan.edn but it contains malformed EDN, the parse
    ;; warning (:warn/worktree-artifact-parse) is the correct diagnostic.
    ;; :warn/no-artifact-found must NOT also fire — the file DID exist.
    (let [wt  (make-worktree-with-plan "{{{invalid edn not parseable")
          err (java.io.StringWriter.)
          ctx {:execution/worktree-path wt}]
      (try
        (binding [*err* err]
          (session/with-session ctx (constantly :bad-edn)))
        (let [stderr (str err)]
          (is (str/includes? stderr "failed to parse worktree artifact")
              "parse WARN must still fire to alert on the malformed file")
          (is (not (str/includes? stderr "no artifact found after session"))
              "no-artifact WARN must be suppressed when the file existed — parse WARN covers it"))
        (finally
          (doseq [^java.io.File f (reverse (file-seq (io/file wt)))]
            (.delete f)))))))

(deftest with-session-emits-mcp-skipped-info-when-worktree-only-test
  (testing "INFO message emitted when worktree artifact found but MCP not submitted"
    ;; Normal Write-based submission: agent wrote .miniforge/plan.edn, skipped
    ;; MCP submit_artifact. Should surface a diagnostic INFO (not a WARN) so
    ;; operators can confirm the worktree-promotion path was used.
    (let [wt  (make-worktree-with-plan (pr-str (plan-artifact)))
          err (java.io.StringWriter.)
          ctx {:execution/worktree-path wt}]
      (try
        (binding [*err* err]
          (session/with-session ctx (constantly :worktree-only)))
        (let [stderr (str err)]
          (is (str/includes? stderr "MCP artifact not submitted")
              "INFO must note that MCP was skipped and worktree-promotion succeeded")
          (is (not (str/includes? stderr "WARN"))
              "INFO message must not be prefixed WARN — it is not a failure"))
        (finally
          (doseq [^java.io.File f (reverse (file-seq (io/file wt)))]
            (.delete f)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.artifact-session-test)
  :leave-this-here)

(deftest with-readonly-session-test
  (testing "runs body-fn with a configured session and returns its result
            directly (no artifact-promotion map), for read-only agents"
    (let [ran (atom false)
          result (session/with-readonly-session
                  {}
                  (fn [s]
                    (reset! ran true)
                    (is (:mcp-config-path s) "session has an mcp-config path")
                    (is (:mcp-allowed-tools s) "session carries the MCP tool allowlist")
                    :review-result))]
      (is @ran "body-fn ran")
      (is (= :review-result result)
          "returns the body-fn value directly, not a normalized artifact map"))))
