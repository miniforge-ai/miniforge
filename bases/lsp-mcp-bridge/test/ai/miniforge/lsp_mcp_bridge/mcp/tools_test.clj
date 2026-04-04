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

(ns ai.miniforge.lsp-mcp-bridge.mcp.tools-test
  "Tests for MCP tool definitions."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.lsp-mcp-bridge.mcp.tools :as tools]))

;------------------------------------------------------------------------------ Tool definitions

(deftest tool-definitions-test
  (testing "has exactly 10 tools"
    (is (= 10 (count tools/tool-definitions))))

  (testing "all tools have required fields"
    (doseq [tool tools/tool-definitions]
      (is (string? (:name tool))
          (str "Tool should have a name: " tool))
      (is (string? (:description tool))
          (str "Tool should have a description: " (:name tool)))
      (is (map? (:inputSchema tool))
          (str "Tool should have inputSchema: " (:name tool)))))

  (testing "all tool names are unique"
    (let [names (map :name tools/tool-definitions)]
      (is (= (count names) (count (set names)))
          "Tool names should be unique")))

  (testing "all tools have valid JSON Schema inputSchema"
    (doseq [tool tools/tool-definitions]
      (let [schema (:inputSchema tool)]
        (is (= "object" (:type schema))
            (str "inputSchema type should be 'object': " (:name tool)))
        (is (map? (:properties schema))
            (str "inputSchema should have properties: " (:name tool)))
        (is (vector? (:required schema))
            (str "inputSchema should have required: " (:name tool)))))))

(deftest tool-names-test
  (testing "expected tool names are present"
    (let [names (set (map :name tools/tool-definitions))]
      (is (= #{"lsp_hover" "lsp_definition" "lsp_references" "lsp_rename"
               "lsp_format" "lsp_document_symbols" "lsp_code_actions"
               "lsp_completion" "lsp_diagnostics" "lsp_servers"}
             names)))))

(deftest file-path-tools-require-file-path
  (testing "tools that operate on files require file_path"
    (let [file-tools (remove #(= "lsp_servers" (:name %)) tools/tool-definitions)]
      (doseq [tool file-tools]
        (is (some #(= "file_path" %) (:required (:inputSchema tool)))
            (str "Tool " (:name tool) " should require file_path"))))))

(deftest call-tool-unknown-test
  (testing "calling an unknown tool returns error"
    (let [result (tools/call-tool {:name "nonexistent" :arguments {}} nil)]
      (is (true? (:isError result)))
      (is (some? (:content result))))))
