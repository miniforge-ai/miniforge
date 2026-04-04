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

(ns ai.miniforge.lsp-mcp-bridge.lsp.protocol-test
  "Tests for LSP JSON-RPC protocol layer (Content-Length framing)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.lsp-mcp-bridge.lsp.protocol :as proto])
  (:import
   [java.io BufferedReader BufferedWriter StringReader StringWriter]))

;------------------------------------------------------------------------------ Layer 1: Message builders

(deftest request-builder-test
  (testing "builds request with params"
    (let [req (proto/request "textDocument/hover"
                             {:textDocument {:uri "file:///foo"}})]
      (is (integer? (:id req)))
      (is (= "2.0" (:jsonrpc req)))
      (is (= "textDocument/hover" (:method req)))
      (is (= "file:///foo" (get-in req [:params :textDocument :uri])))))

  (testing "builds request without params"
    (let [req (proto/request "shutdown")]
      (is (= "shutdown" (:method req)))
      (is (not (contains? req :params))))))

(deftest response-builder-test
  (testing "builds response"
    (let [resp (proto/response 1 {:value "ok"})]
      (is (= 1 (:id resp)))
      (is (= "2.0" (:jsonrpc resp)))
      (is (= {:value "ok"} (:result resp))))))

(deftest notification-builder-test
  (testing "builds notification without params"
    (let [n (proto/notification "initialized")]
      (is (= "2.0" (:jsonrpc n)))
      (is (= "initialized" (:method n)))
      (is (not (contains? n :id)))
      (is (not (contains? n :params)))))

  (testing "builds notification with params"
    (let [n (proto/notification "textDocument/didOpen" {:textDocument {:uri "file:///foo"}})]
      (is (= {:textDocument {:uri "file:///foo"}} (:params n))))))

;------------------------------------------------------------------------------ Layer 2: Encoding/Decoding

(deftest encode-decode-roundtrip-test
  (testing "encode then decode a message via Content-Length framing"
    (let [msg {:id 1 :jsonrpc "2.0" :method "initialize" :params {:processId 123}}
          encoded (proto/encode-message msg)
          ;; Parse the encoded string
          reader (BufferedReader. (StringReader. encoded))
          decoded (proto/read-message reader)]
      (is (= 1 (:id decoded)))
      (is (= "initialize" (:method decoded)))
      (is (= 123 (get-in decoded [:params :processId]))))))

(deftest write-read-message-roundtrip-test
  (testing "write-message and read-message roundtrip"
    (let [sw (StringWriter.)
          writer (BufferedWriter. sw)
          msg (proto/request "textDocument/hover"
                             {:textDocument {:uri "file:///foo"}
                              :position {:line 10 :character 5}})]
      (proto/write-message writer msg)
      (let [reader (BufferedReader. (StringReader. (.toString sw)))
            read-msg (proto/read-message reader)]
        (is (= (:id msg) (:id read-msg)))
        (is (= "textDocument/hover" (:method read-msg)))
        (is (= 10 (get-in read-msg [:params :position :line])))
        (is (= 5 (get-in read-msg [:params :position :character])))))))

(deftest read-message-eof-test
  (testing "returns nil on empty input"
    (let [reader (BufferedReader. (StringReader. ""))]
      (is (nil? (proto/read-message reader))))))

;------------------------------------------------------------------------------ Layer 2: Predicates

(deftest message-predicates-test
  (testing "request?"
    (is (proto/request? {:id 1 :method "test"}))
    (is (not (proto/request? {:id 1 :result {}})))
    (is (not (proto/request? {:method "test"}))))

  (testing "response?"
    (is (proto/response? {:id 1 :result {}}))
    (is (proto/response? {:id 1 :error {:code -1 :message "x"}}))
    (is (not (proto/response? {:id 1 :method "test"}))))

  (testing "notification?"
    (is (proto/notification? {:method "test"}))
    (is (not (proto/notification? {:id 1 :method "test"}))))

  (testing "error?"
    (is (proto/error? {:id 1 :error {:code -1 :message "x"}}))
    (is (not (proto/error? {:id 1 :result {}})))))

;------------------------------------------------------------------------------ Layer 3: Standard LSP methods

(deftest hover-request-test
  (testing "builds hover request"
    (let [req (proto/hover-request "file:///foo.clj" 10 5)]
      (is (= "textDocument/hover" (:method req)))
      (is (= "file:///foo.clj" (get-in req [:params :textDocument :uri])))
      (is (= 10 (get-in req [:params :position :line])))
      (is (= 5 (get-in req [:params :position :character]))))))

(deftest completion-request-test
  (testing "builds completion request"
    (let [req (proto/completion-request "file:///foo.clj" 5 3)]
      (is (= "textDocument/completion" (:method req)))
      (is (= "file:///foo.clj" (get-in req [:params :textDocument :uri]))))))

(deftest definition-request-test
  (testing "builds definition request"
    (let [req (proto/definition-request "file:///foo.clj" 20 10)]
      (is (= "textDocument/definition" (:method req))))))

(deftest references-request-test
  (testing "builds references request with includeDeclaration"
    (let [req (proto/references-request "file:///foo.clj" 10 5 true)]
      (is (= "textDocument/references" (:method req)))
      (is (true? (get-in req [:params :context :includeDeclaration]))))))

(deftest formatting-request-test
  (testing "builds formatting request with defaults"
    (let [req (proto/formatting-request "file:///foo.clj" nil)]
      (is (= "textDocument/formatting" (:method req)))
      (is (= 2 (get-in req [:params :options :tabSize])))))

  (testing "builds formatting request with custom options"
    (let [req (proto/formatting-request "file:///foo.clj" {:tabSize 4 :insertSpaces false})]
      (is (= 4 (get-in req [:params :options :tabSize]))))))

(deftest did-open-notification-test
  (testing "builds didOpen notification"
    (let [n (proto/did-open-notification "file:///foo.clj" "clojure" 1 "(ns foo)")]
      (is (= "textDocument/didOpen" (:method n)))
      (is (= "clojure" (get-in n [:params :textDocument :languageId])))
      (is (= "(ns foo)" (get-in n [:params :textDocument :text]))))))

(deftest did-close-notification-test
  (testing "builds didClose notification"
    (let [n (proto/did-close-notification "file:///foo.clj")]
      (is (= "textDocument/didClose" (:method n)))
      (is (= "file:///foo.clj" (get-in n [:params :textDocument :uri]))))))

(deftest rename-request-test
  (testing "builds rename request"
    (let [req (proto/rename-request "file:///foo.clj" 10 5 "new-name")]
      (is (= "textDocument/rename" (:method req)))
      (is (= "new-name" (get-in req [:params :newName]))))))

(deftest document-symbol-request-test
  (testing "builds documentSymbol request"
    (let [req (proto/document-symbol-request "file:///foo.clj")]
      (is (= "textDocument/documentSymbol" (:method req)))
      (is (= "file:///foo.clj" (get-in req [:params :textDocument :uri]))))))
