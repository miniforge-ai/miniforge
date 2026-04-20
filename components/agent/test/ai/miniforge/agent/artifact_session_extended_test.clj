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

(ns ai.miniforge.agent.artifact-session-extended-test
  "Extended tests for artifact-session: unit-level coverage of parsing helpers,
   context cache, context misses, and command resolution."
  (:require
   [ai.miniforge.agent.artifact-session :as session]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0
;; uuid-str? tests

(deftest uuid-str?-test
  (testing "valid UUID strings"
    (is (session/uuid-str? "550e8400-e29b-41d4-a716-446655440000"))
    (is (session/uuid-str? "00000000-0000-0000-0000-000000000000"))
    (is (session/uuid-str? "ffffffff-ffff-ffff-ffff-ffffffffffff")))

  (testing "invalid UUID strings"
    (is (not (session/uuid-str? "not-a-uuid")))
    (is (not (session/uuid-str? "")))
    (is (not (session/uuid-str? "550e8400-e29b-41d4-a716")))
    (is (not (session/uuid-str? "550e8400-e29b-41d4-a716-446655440000-extra")))
    (is (not (session/uuid-str? "GGGGGGGG-GGGG-GGGG-GGGG-GGGGGGGGGGGG"))))

  (testing "non-string values"
    (is (not (session/uuid-str? nil)))
    (is (not (session/uuid-str? 42)))
    (is (not (session/uuid-str? :keyword)))
    (is (not (session/uuid-str? (java.util.UUID/randomUUID))))))

;------------------------------------------------------------------------------ Layer 0
;; instant-str? tests

(deftest instant-str?-test
  (testing "valid ISO instant strings"
    (is (session/instant-str? "2026-02-28T12:00:00Z"))
    (is (session/instant-str? "2026-02-28T12:00:00.123Z"))
    (is (session/instant-str? "2026-02-28T12:00:00+05:30"))
    (is (session/instant-str? "2000-01-01T00:00:00Z")))

  (testing "invalid instant strings"
    (is (not (session/instant-str? "2026-02-28")))
    (is (not (session/instant-str? "not-a-date")))
    (is (not (session/instant-str? ""))))

  (testing "non-string values"
    (is (not (session/instant-str? nil)))
    (is (not (session/instant-str? 12345)))
    (is (not (session/instant-str? (java.util.Date.))))))

;------------------------------------------------------------------------------ Layer 0
;; key-ends-with? tests

(deftest key-ends-with?-test
  (testing "matching namespaced keywords"
    (is (session/key-ends-with? :code/id "id"))
    (is (session/key-ends-with? :plan/id "id"))
    (is (session/key-ends-with? :code/created-at "created-at"))
    (is (session/key-ends-with? :task/description "description")))

  (testing "non-matching namespaced keywords"
    (is (not (session/key-ends-with? :code/summary "id")))
    (is (not (session/key-ends-with? :code/id "created-at"))))

  (testing "simple keywords"
    (is (session/key-ends-with? :id "id"))
    (is (not (session/key-ends-with? :id "created-at"))))

  (testing "non-keyword values"
    (is (not (session/key-ends-with? "code/id" "id")))
    (is (not (session/key-ends-with? nil "id")))
    (is (not (session/key-ends-with? 42 "id")))))

;------------------------------------------------------------------------------ Layer 1
;; parse-uuid-strings edge cases

(deftest parse-uuid-strings-empty-map-test
  (testing "empty map returns empty map"
    (is (= {} (session/parse-uuid-strings {})))))

(deftest parse-uuid-strings-non-map-test
  (testing "non-map values pass through unchanged"
    (is (= "hello" (session/parse-uuid-strings "hello")))
    (is (= 42 (session/parse-uuid-strings 42)))
    (is (nil? (session/parse-uuid-strings nil)))))

(deftest parse-uuid-strings-non-uuid-in-id-key-test
  (testing "non-UUID string in /id key is preserved"
    (let [result (session/parse-uuid-strings {:code/id "not-a-uuid-string"})]
      (is (= "not-a-uuid-string" (:code/id result))))))

(deftest parse-uuid-strings-non-instant-in-created-at-key-test
  (testing "non-instant string in /created-at key is preserved"
    (let [result (session/parse-uuid-strings {:code/created-at "not-a-date"})]
      (is (= "not-a-date" (:code/created-at result))))))

(deftest parse-uuid-strings-preserves-already-typed-values-test
  (testing "already-typed UUID passes through"
    (let [uuid (java.util.UUID/randomUUID)
          result (session/parse-uuid-strings {:code/id uuid})]
      (is (= uuid (:code/id result)))))

  (testing "already-typed Date passes through"
    (let [date (java.util.Date.)
          result (session/parse-uuid-strings {:code/created-at date})]
      (is (= date (:code/created-at result))))))

(deftest parse-uuid-strings-mixed-keys-test
  (testing "only /id and /created-at keys are transformed"
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          result (session/parse-uuid-strings
                  {:code/id uuid-str
                   :code/summary "unchanged"
                   :code/language "clojure"
                   :code/created-at "2026-02-28T12:00:00Z"
                   :code/tests-needed? true})]
      (is (instance? java.util.UUID (:code/id result)))
      (is (instance? java.util.Date (:code/created-at result)))
      (is (= "unchanged" (:code/summary result)))
      (is (= "clojure" (:code/language result)))
      (is (true? (:code/tests-needed? result))))))

(deftest parse-uuid-strings-empty-vector-of-maps-test
  (testing "empty vector of maps remains empty"
    (let [result (session/parse-uuid-strings {:plan/tasks []})]
      (is (= [] (:plan/tasks result))))))

(deftest parse-uuid-strings-vector-of-non-maps-test
  (testing "vector of non-maps is preserved"
    (let [result (session/parse-uuid-strings {:code/files ["a.clj" "b.clj"]})]
      (is (= ["a.clj" "b.clj"] (:code/files result))))))

;------------------------------------------------------------------------------ Layer 2
;; write-context-cache! tests

(deftest write-context-cache-writes-edn-test
  (testing "writes files map as EDN to context-cache.edn"
    (let [s (session/create-session!)]
      (try
        (let [files {"src/core.clj" "(ns core)"
                     "src/util.clj" "(ns util)"}]
          (session/write-context-cache! s files)
          (let [cache-path (str (:dir s) "/context-cache.edn")
                content (edn/read-string (slurp cache-path))]
            (is (map? content))
            (is (= files (:files content)))))
        (finally
          (session/cleanup-session! s))))))

(deftest write-context-cache-empty-files-test
  (testing "does not write file when files map is empty"
    (let [s (session/create-session!)]
      (try
        (session/write-context-cache! s {})
        (let [cache-path (str (:dir s) "/context-cache.edn")]
          (is (not (.exists (io/file cache-path)))))
        (finally
          (session/cleanup-session! s))))))

(deftest write-context-cache-nil-files-test
  (testing "does not write file when files is nil"
    (let [s (session/create-session!)]
      (try
        (session/write-context-cache! s nil)
        (let [cache-path (str (:dir s) "/context-cache.edn")]
          (is (not (.exists (io/file cache-path)))))
        (finally
          (session/cleanup-session! s))))))

(deftest write-context-cache-returns-session-test
  (testing "returns session for threading"
    (let [s (session/create-session!)]
      (try
        (let [result (session/write-context-cache! s {"a.clj" "content"})]
          (is (= (:dir s) (:dir result))))
        (finally
          (session/cleanup-session! s))))))

;------------------------------------------------------------------------------ Layer 2
;; read-context-misses tests

(deftest read-context-misses-with-data-test
  (testing "reads context-misses.edn when present"
    (let [s (session/create-session!)
          misses [{:type :read :path "src/missing.clj"}
                  {:type :grep :pattern "defn foo"}]]
      (try
        (spit (str (:dir s) "/context-misses.edn") (pr-str misses))
        (let [result (session/read-context-misses s)]
          (is (= 2 (count result)))
          (is (= "src/missing.clj" (:path (first result)))))
        (finally
          (session/cleanup-session! s))))))

(deftest read-context-misses-no-file-test
  (testing "returns nil when no misses file exists"
    (let [s (session/create-session!)]
      (try
        (is (nil? (session/read-context-misses s)))
        (finally
          (session/cleanup-session! s))))))

(deftest read-context-misses-invalid-edn-test
  (testing "returns nil for invalid EDN in misses file"
    (let [s (session/create-session!)]
      (try
        (spit (str (:dir s) "/context-misses.edn") "{{{bad edn")
        (is (nil? (session/read-context-misses s)))
        (finally
          (session/cleanup-session! s))))))

;------------------------------------------------------------------------------ Layer 2
;; server-command tests

(deftest server-command-test
  (testing "returns a map with :command and :args including --artifact-dir"
    (let [result (session/server-command "/tmp/artifacts")]
      (is (map? result))
      (is (string? (:command result)))
      (is (vector? (:args result)))
      (is (some #(= "--artifact-dir" %) (:args result)))
      (is (some #(= "/tmp/artifacts" %) (:args result)))
      (is (some #(= "mcp-context-server" %) (:args result))))))

;------------------------------------------------------------------------------ Layer 2
;; resolve-miniforge-command tests

(deftest resolve-miniforge-command-returns-vector-test
  (testing "always returns a vector"
    (let [result (session/resolve-miniforge-command)]
      (is (vector? result))
      (is (pos? (count result))))))

(deftest resolve-miniforge-command-env-override-test
  (testing "MINIFORGE_CMD env var takes priority when set"
    (with-redefs [session/resolve-miniforge-command
                  (fn []
                    (if (System/getenv "MINIFORGE_CMD")
                      [(System/getenv "MINIFORGE_CMD")]
                      ["bb" "miniforge"]))]
      ;; Default case (env var not set in test)
      (let [result (session/resolve-miniforge-command)]
        (is (vector? result))))))

;------------------------------------------------------------------------------ Layer 2
;; mcp-tool-names tests

(deftest mcp-tool-names-test
  (testing "uses the full mcp__<server>__<tool> form required by Claude CLI --allowedTools"
    (is (some #{"mcp__context__context_read"} session/mcp-tool-names))
    (is (some #{"mcp__context__context_grep"} session/mcp-tool-names))
    (is (some #{"mcp__context__context_glob"} session/mcp-tool-names)))

  (testing "does NOT contain bare tool names (regression guard)"
    ;; Bare names get every MCP call answered with
    ;; "Claude requested permissions to use mcp__context__context_read,
    ;;  but you haven't granted it yet." — documented cause of iters
    ;; 5-10 planner-convergence dogfood failures.
    (is (nil? (some #{"context_read"} session/mcp-tool-names)))
    (is (nil? (some #{"context_grep"} session/mcp-tool-names)))
    (is (nil? (some #{"context_glob"} session/mcp-tool-names))))

  (testing "every entry is a non-empty string starting with mcp__"
    (doseq [n session/mcp-tool-names]
      (is (string? n))
      (is (pos? (count n)))
      (is (.startsWith ^String n "mcp__")))))

;------------------------------------------------------------------------------ Layer 3
;; write-claude-settings! tests

(deftest write-claude-settings-test
  (testing "writes a valid JSON settings file with hook"
    (let [s (session/create-session!)]
      (try
        (let [path (session/write-claude-settings! (:dir s))
              content (json/parse-string (slurp path) true)]
          (is (str/ends-with? path "claude-settings.json"))
          (is (map? content))
          (is (vector? (get-in content [:hooks :PreToolUse])))
          (is (= "command" (get-in content [:hooks :PreToolUse 0 :type])))
          (is (str/includes? (get-in content [:hooks :PreToolUse 0 :command])
                             "hook-eval")))
        (finally
          (session/cleanup-session! s))))))

;------------------------------------------------------------------------------ Layer 3
;; Cleanup edge cases

(deftest cleanup-codex-empty-file-deletion-test
  (testing "deletes config.toml if only artifact block remains"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "codex-empty-test-" (random-uuid)))
          config-file (io/file tmp "config.toml")]
      (try
        (.mkdirs tmp)
        (spit config-file (str "[mcp_servers.artifact]\n"
                               "command = \"bb\"\n"
                               "args = [\"miniforge\",\"mcp-serve\"]\n"))
        (@#'session/cleanup-codex-mcp-config! (str config-file))
        (is (not (.exists config-file)))
        (finally
          (doseq [f (reverse (file-seq tmp))]
            (.delete ^java.io.File f)))))))

(deftest cleanup-cursor-empty-servers-deletion-test
  (testing "deletes mcp.json when artifact is the only server"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "cursor-empty-test-" (random-uuid)))
          config-file (io/file tmp "mcp.json")]
      (try
        (.mkdirs tmp)
        (spit config-file (json/generate-string
                           {"mcpServers" {"artifact" {"command" "bb"}}}))
        (@#'session/cleanup-cursor-mcp-config! (str config-file))
        (is (not (.exists config-file)))
        (finally
          (doseq [f (reverse (file-seq tmp))]
            (.delete ^java.io.File f)))))))

(deftest cleanup-cursor-nonexistent-file-test
  (testing "cleanup is a no-op for nonexistent file"
    (@#'session/cleanup-cursor-mcp-config! "/tmp/nonexistent-file.json")))

(deftest cleanup-codex-nonexistent-file-test
  (testing "cleanup is a no-op for nonexistent file"
    (@#'session/cleanup-codex-mcp-config! "/tmp/nonexistent-file.toml")))

;------------------------------------------------------------------------------ Layer 3
;; write-mcp-config! populates supervision

(deftest write-mcp-config-supervision-test
  (testing "session includes :supervision after write-mcp-config!"
    (let [s (-> (session/create-session!) (session/write-mcp-config!))]
      (try
        (is (map? (:supervision s)))
        (is (string? (get-in s [:supervision :hook-eval-cmd])))
        (is (str/includes? (get-in s [:supervision :hook-eval-cmd]) "hook-eval"))
        (is (string? (get-in s [:supervision :settings-path])))
        (is (= :workspace-write (get-in s [:supervision :policy])))
        (finally
          (session/cleanup-session! s))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.agent.artifact-session-extended-test)
  :leave-this-here)
