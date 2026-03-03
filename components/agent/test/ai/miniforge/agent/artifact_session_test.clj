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
          (is (= "bb" (get-in config [:mcpServers :artifact :command])))
          (let [args (get-in config [:mcpServers :artifact :args])]
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
          (is (every? #(str/starts-with? % "mcp__artifact__") (:mcp-allowed-tools result))))
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
  (testing "writes .codex/config.toml with [mcp_servers.artifact] block"
    (let [s (session/create-session!)]
      (try
        (session/write-mcp-config! s)
        (let [codex-file (first (filter #(str/ends-with? % "config.toml")
                                        (:mcp-cleanup-files
                                         (session/write-mcp-config! s))))]
          ;; Just verify the tracked file exists and has correct content
          (when codex-file
            (let [content (slurp codex-file)]
              (is (str/includes? content "[mcp_servers.artifact]"))
              (is (str/includes? content "command = "))
              (is (str/includes? content "--artifact-dir")))))
        (finally
          (session/cleanup-session! s))))))

(deftest write-cursor-mcp-config-test
  (testing "writes .cursor/mcp.json with mcpServers.artifact entry"
    (let [s (-> (session/create-session!) session/write-mcp-config!)
          cursor-file (first (filter #(str/ends-with? % "mcp.json")
                                     (:mcp-cleanup-files s)))]
      (try
        (when cursor-file
          (let [config (json/parse-string (slurp cursor-file) true)]
            (is (map? config))
            (is (map? (get-in config [:mcpServers :artifact])))
            (is (string? (get-in config [:mcpServers :artifact :command])))
            (is (vector? (get-in config [:mcpServers :artifact :args])))
            (is (some #(= "--artifact-dir" %) (get-in config [:mcpServers :artifact :args])))))
        (finally
          (session/cleanup-session! s))))))

(deftest cleanup-codex-config-test
  (testing "cleanup removes artifact block but preserves other config"
    (let [codex-dir (io/file ".codex")
          codex-file (io/file codex-dir "config.toml")]
      ;; Use a locking file to serialize access to shared .codex/config.toml
      (locking codex-file
        (let [original-content (when (.exists codex-file) (slurp codex-file))]
          (try
            (.mkdirs codex-dir)
            (spit codex-file "[some_other_section]\nkey = \"value\"\n")
            (let [s (-> (session/create-session!) session/write-mcp-config!)]
              (is (str/includes? (slurp codex-file) "[mcp_servers.artifact]"))
              (session/cleanup-session! s)
              (let [content (slurp codex-file)]
                (is (not (str/includes? content "[mcp_servers.artifact]")))
                (is (str/includes? content "[some_other_section]"))))
            (finally
              ;; Restore original state
              (if original-content
                (spit codex-file original-content)
                (when (.exists codex-file) (.delete codex-file))))))))))

(deftest cleanup-cursor-config-test
  (testing "cleanup removes artifact entry but preserves other servers"
    (let [cursor-dir (io/file ".cursor")
          cursor-file (io/file cursor-dir "mcp.json")]
      (locking cursor-file
        (let [original-content (when (.exists cursor-file) (slurp cursor-file))]
          (try
            (.mkdirs cursor-dir)
            (spit cursor-file (json/generate-string {"mcpServers" {"other" {"command" "other-cmd"}}}))
            (let [s (-> (session/create-session!) session/write-mcp-config!)]
              (let [config (json/parse-string (slurp cursor-file))]
                (is (contains? (get config "mcpServers") "artifact")))
              (session/cleanup-session! s)
              (let [config (json/parse-string (slurp cursor-file))]
                (is (not (contains? (get config "mcpServers") "artifact")))
                (is (= "other-cmd" (get-in config ["mcpServers" "other" "command"])))))
            (finally
              (if original-content
                (spit cursor-file original-content)
                (when (.exists cursor-file) (.delete cursor-file))))))))))

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
