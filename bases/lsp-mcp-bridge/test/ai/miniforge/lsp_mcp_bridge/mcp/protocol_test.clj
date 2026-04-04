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

(ns ai.miniforge.lsp-mcp-bridge.mcp.protocol-test
  "Tests for MCP JSON-RPC protocol layer."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.lsp-mcp-bridge.mcp.protocol :as proto])
  (:import
   [java.io BufferedReader BufferedWriter StringReader StringWriter]))

;------------------------------------------------------------------------------ Layer 1: Message builders

(deftest response-test
  (testing "builds a valid JSON-RPC response"
    (let [resp (proto/response 1 {:foo "bar"})]
      (is (= 1 (:id resp)))
      (is (= "2.0" (:jsonrpc resp)))
      (is (= {:foo "bar"} (:result resp))))))

(deftest error-response-test
  (testing "builds error response without data"
    (let [resp (proto/error-response 1 -32600 "Invalid")]
      (is (= 1 (:id resp)))
      (is (= -32600 (get-in resp [:error :code])))
      (is (= "Invalid" (get-in resp [:error :message])))
      (is (nil? (get-in resp [:error :data])))))

  (testing "builds error response with data"
    (let [resp (proto/error-response 1 -32600 "Invalid" {:detail "extra"})]
      (is (= {:detail "extra"} (get-in resp [:error :data]))))))

(deftest notification-test
  (testing "builds notification without params"
    (let [n (proto/notification "notifications/initialized")]
      (is (= "2.0" (:jsonrpc n)))
      (is (= "notifications/initialized" (:method n)))
      (is (not (contains? n :params)))))

  (testing "builds notification with params"
    (let [n (proto/notification "test/method" {:key "val"})]
      (is (= {:key "val"} (:params n))))))

;------------------------------------------------------------------------------ Layer 2: Message I/O

(deftest write-and-read-message-roundtrip-test
  (testing "write then read a message via JSON lines"
    (let [sw (StringWriter.)
          writer (BufferedWriter. sw)
          msg {:id 1 :jsonrpc "2.0" :result {:hover "info"}}]
      ;; Write
      (proto/write-message writer msg)
      ;; Read back
      (let [reader (BufferedReader. (StringReader. (.toString sw)))
            read-msg (proto/read-message reader)]
        (is (= 1 (:id read-msg)))
        (is (= "2.0" (:jsonrpc read-msg)))
        (is (= "info" (get-in read-msg [:result :hover])))))))

(deftest read-message-eof-test
  (testing "returns nil on empty input"
    (let [reader (BufferedReader. (StringReader. ""))]
      (is (nil? (proto/read-message reader))))))

(deftest read-message-blank-line-test
  (testing "skips blank lines"
    (let [reader (BufferedReader. (StringReader. "\n"))]
      (is (nil? (proto/read-message reader))))))

;------------------------------------------------------------------------------ Layer 2: Predicates

(deftest request-predicate-test
  (testing "identifies requests (has id + method)"
    (is (proto/request? {:id 1 :method "initialize"}))
    (is (not (proto/request? {:id 1 :result {}})))
    (is (not (proto/request? {:method "initialized"})))))

(deftest response-predicate-test
  (testing "identifies responses (has id, no method)"
    (is (proto/response? {:id 1 :result {}}))
    (is (not (proto/response? {:id 1 :method "initialize"})))))

(deftest notification-predicate-test
  (testing "identifies notifications (no id, has method)"
    (is (proto/notification? {:method "initialized"}))
    (is (not (proto/notification? {:id 1 :method "initialize"})))))

;------------------------------------------------------------------------------ Layer 3: MCP-specific builders

(deftest initialize-result-test
  (testing "builds MCP initialize result"
    (let [result (proto/initialize-result)]
      (is (string? (:protocolVersion result)))
      (is (= {:tools {:listChanged false}} (:capabilities result)))
      (is (= "miniforge-lsp" (get-in result [:serverInfo :name]))))))

(deftest tools-list-result-test
  (testing "builds tools list result"
    (let [tools [{:name "lsp_hover" :description "hover"}]
          result (proto/tools-list-result tools)]
      (is (= tools (:tools result))))))

(deftest tool-call-result-test
  (testing "builds successful tool call result"
    (let [result (proto/tool-call-result "some content")]
      (is (= [{:type "text" :text "some content"}] (:content result)))
      (is (not (contains? result :isError)))))

  (testing "builds error tool call result"
    (let [result (proto/tool-call-error "something failed")]
      (is (= [{:type "text" :text "something failed"}] (:content result)))
      (is (true? (:isError result))))))
