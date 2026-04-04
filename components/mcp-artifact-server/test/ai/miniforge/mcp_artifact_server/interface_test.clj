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

(ns ai.miniforge.mcp-artifact-server.interface-test
  "Unit tests for MCP artifact server tool handlers and protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.miniforge.mcp-artifact-server.interface :as mcp]
            [ai.miniforge.mcp-artifact-server.tools :as tools]
            [ai.miniforge.mcp-artifact-server.protocol :as protocol]))

;------------------------------------------------------------------------------ Helpers

(defn with-temp-dir [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "mcp-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq (io/file dir)))]
          (.delete ^java.io.File file))))))

(defn read-artifact [dir]
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
  (testing "registry has at least 7 tools (4 artifact + 3 context)"
    (is (>= (count (mcp/tool-definitions)) 7)))

  (testing "each tool has required fields"
    (doseq [tool (mcp/tool-definitions)]
      (is (string? (:name tool)))
      (is (string? (:description tool)))
      (is (map? (:inputSchema tool)))))

  (testing "context tools are present"
    (let [names (set (map :name (mcp/tool-definitions)))]
      (is (contains? names "context_read"))
      (is (contains? names "context_grep"))
      (is (contains? names "context_glob")))))

(deftest handler-dispatch-test
  (testing "handler tools dispatch without writing artifacts"
    (tools/register-handler! :test-echo
      (fn [params] {:content [{:type "text" :text (get params "msg")}]}))
    (tools/register-tool! "test_echo"
      {:handler :test-echo
       :tool-def {:name "test_echo" :description "echo" :inputSchema {:type "object"}}
       :required-params {}})
    (let [result (mcp/handle-tool-call "test_echo" {"msg" "hello"} "/tmp/unused")]
      (is (= "hello" (get-in result [:content 0 :text]))))))
