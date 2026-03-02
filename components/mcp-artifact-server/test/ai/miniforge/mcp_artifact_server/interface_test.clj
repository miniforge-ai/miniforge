(ns ai.miniforge.mcp-artifact-server.interface-test
  "Unit tests for MCP artifact server tool handlers and protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.miniforge.mcp-artifact-server.interface :as mcp]
            [ai.miniforge.mcp-artifact-server.protocol :as protocol]))

;------------------------------------------------------------------------------ Helpers

(defn- with-temp-dir [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "mcp-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq (io/file dir)))]
          (.delete ^java.io.File file))))))

(defn- read-artifact [dir]
  (edn/read-string (slurp (str dir "/artifact.edn"))))

;------------------------------------------------------------------------------ Tool handler tests

(deftest submit-code-artifact-test
  (with-temp-dir
    (fn [dir]
      (testing "submits code artifact successfully"
        (let [result (mcp/handle-tool-call
                       "submit_code_artifact"
                       {"files" [{"path" "src/core.clj"
                                  "content" "(ns core)"
                                  "action" "create"}]
                        "summary" "Add core namespace"
                        "language" "clojure"}
                       dir)
              artifact (read-artifact dir)]
          (is (some? (get-in result [:content 0 :text])))
          (is (string? (:code/id artifact)))
          (is (= "Add core namespace" (:code/summary artifact)))
          (is (= 1 (count (:code/files artifact))))
          (is (= :create (:action (first (:code/files artifact))))))))))

(deftest submit-plan-test
  (with-temp-dir
    (fn [dir]
      (testing "submits plan artifact successfully"
        (let [result (mcp/handle-tool-call
                       "submit_plan"
                       {"name" "Auth feature"
                        "tasks" [{"description" "Design API" "type" "design"}
                                 {"description" "Implement" "type" "implement"
                                  "dependencies" [0]}]
                        "complexity" "medium"
                        "risks" ["Token expiry"]}
                       dir)
              artifact (read-artifact dir)]
          (is (some? (get-in result [:content 0 :text])))
          (is (string? (:plan/id artifact)))
          (is (= "Auth feature" (:plan/name artifact)))
          (is (= 2 (count (:plan/tasks artifact))))
          (is (= :medium (:plan/estimated-complexity artifact)))
          (is (= ["Token expiry"] (:plan/risks artifact))))))))

(deftest submit-test-artifact-test
  (with-temp-dir
    (fn [dir]
      (testing "submits test artifact successfully"
        (let [test-content "(deftest foo-test (is (= 1 1)))"
              result (mcp/handle-tool-call
                       "submit_test_artifact"
                       {"files" [{"path" "test/foo_test.clj" "content" test-content}]
                        "summary" "Test foo"
                        "type" "unit"
                        "framework" "clojure.test"}
                       dir)
              artifact (read-artifact dir)]
          (is (some? (get-in result [:content 0 :text])))
          (is (string? (:test/id artifact)))
          (is (= :unit (:test/type artifact)))
          (is (pos? (:test/cases-count artifact))))))))

(deftest submit-release-artifact-test
  (with-temp-dir
    (fn [dir]
      (testing "submits release artifact successfully"
        (let [result (mcp/handle-tool-call
                       "submit_release_artifact"
                       {"branch_name" "feature/auth"
                        "commit_message" "feat: add auth"
                        "pr_title" "Add auth"
                        "pr_description" "## Summary"}
                       dir)
              artifact (read-artifact dir)]
          (is (some? (get-in result [:content 0 :text])))
          (is (string? (:release/id artifact)))
          (is (= "feature/auth" (:release/branch-name artifact))))))))

;------------------------------------------------------------------------------ Validation tests

(deftest missing-required-params-test
  (with-temp-dir
    (fn [dir]
      (testing "throws on missing files"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"files is required"
              (mcp/handle-tool-call "submit_code_artifact"
                                    {"summary" "no files"}
                                    dir))))

      (testing "throws on empty files"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"files is required"
              (mcp/handle-tool-call "submit_code_artifact"
                                    {"files" [] "summary" "empty"}
                                    dir)))))))

(deftest unknown-tool-test
  (with-temp-dir
    (fn [dir]
      (testing "throws on unknown tool"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown tool"
              (mcp/handle-tool-call "nonexistent" {} dir)))))))

;------------------------------------------------------------------------------ Protocol tests

(deftest parse-message-test
  (testing "parses valid JSON"
    (let [msg (protocol/parse-message "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")]
      (is (= "2.0" (get msg "jsonrpc")))
      (is (= 1 (get msg "id")))))

  (testing "returns nil on invalid JSON"
    (is (nil? (protocol/parse-message "not json")))))

;------------------------------------------------------------------------------ Tool definitions tests

(deftest tool-definitions-test
  (testing "registry has 4 tools"
    (is (= 4 (count mcp/tool-definitions))))

  (testing "each tool has required fields"
    (doseq [tool mcp/tool-definitions]
      (is (string? (:name tool)))
      (is (string? (:description tool)))
      (is (map? (:inputSchema tool))))))
