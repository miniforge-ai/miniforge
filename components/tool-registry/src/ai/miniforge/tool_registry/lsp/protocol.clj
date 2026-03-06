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

(ns ai.miniforge.tool-registry.lsp.protocol
  "LSP JSON-RPC protocol types and message builders.

   Implements the Language Server Protocol as defined at:
   https://microsoft.github.io/language-server-protocol/

   Layer 0: Constants and message IDs
   Layer 1: Message builders (request, response, notification)
   Layer 2: Message parsers
   Layer 3: Standard LSP methods"
  (:require
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and message IDs

(def ^:private id-counter (atom 0))

(defn next-id
  "Generate the next message ID."
  []
  (swap! id-counter inc))

(def jsonrpc-version "2.0")

;; LSP Error Codes
(def error-codes
  {:parse-error      -32700
   :invalid-request  -32600
   :method-not-found -32601
   :invalid-params   -32602
   :internal-error   -32603
   ;; LSP-specific
   :server-not-initialized -32002
   :unknown-error    -32001
   :request-cancelled -32800
   :content-modified -32801})

;------------------------------------------------------------------------------ Layer 1
;; Message builders

(defn request
  "Build a JSON-RPC request message.

   Arguments:
   - method - LSP method name (string)
   - params - Parameters map (optional)

   Returns {:id int :jsonrpc \"2.0\" :method string :params map}"
  ([method]
   (request method nil))
  ([method params]
   (cond-> {:id (next-id)
            :jsonrpc jsonrpc-version
            :method method}
     params (assoc :params params))))

(defn response
  "Build a JSON-RPC response message.

   Arguments:
   - id     - Request ID to respond to
   - result - Result value (on success)

   Returns {:id int :jsonrpc \"2.0\" :result any}"
  [id result]
  {:id id
   :jsonrpc jsonrpc-version
   :result result})

(defn error-response
  "Build a JSON-RPC error response message.

   Arguments:
   - id      - Request ID to respond to
   - code    - Error code (keyword or int)
   - message - Error message
   - data    - Optional additional data

   Returns {:id int :jsonrpc \"2.0\" :error {...}}"
  ([id code message]
   (error-response id code message nil))
  ([id code message data]
   (let [code-int (if (keyword? code)
                    (get error-codes code code)
                    code)]
     (cond-> {:id id
              :jsonrpc jsonrpc-version
              :error {:code code-int
                      :message message}}
       data (assoc-in [:error :data] data)))))

(defn notification
  "Build a JSON-RPC notification message (no response expected).

   Arguments:
   - method - LSP method name
   - params - Parameters map (optional)

   Returns {:jsonrpc \"2.0\" :method string :params map}"
  ([method]
   (notification method nil))
  ([method params]
   (cond-> {:jsonrpc jsonrpc-version
            :method method}
     params (assoc :params params))))

;------------------------------------------------------------------------------ Layer 2
;; Message encoding/decoding

(defn encode-message
  "Encode a message for LSP transport (with Content-Length header).

   Returns a string with headers and JSON body."
  [msg]
  (let [body (json/generate-string msg)
        length (count (.getBytes body "UTF-8"))]
    (str "Content-Length: " length "\r\n\r\n" body)))


(defn decode-message
  "Decode a JSON-RPC message from JSON string.

   Returns the parsed message map."
  [json-str]
  (json/parse-string json-str true))

(defn request?
  "Check if a message is a request (has id and method)."
  [msg]
  (and (contains? msg :id)
       (contains? msg :method)))

(defn response?
  "Check if a message is a response (has id, no method)."
  [msg]
  (and (contains? msg :id)
       (not (contains? msg :method))))

(defn notification?
  "Check if a message is a notification (no id, has method)."
  [msg]
  (and (not (contains? msg :id))
       (contains? msg :method)))

(defn error?
  "Check if a message is an error response."
  [msg]
  (contains? msg :error))

;------------------------------------------------------------------------------ Layer 3
;; Standard LSP methods

;; Lifecycle
(defn initialize-request
  "Build an initialize request.

   Arguments:
   - root-uri    - Workspace root URI
   - capabilities - Client capabilities map"
  [root-uri capabilities]
  (request "initialize"
           {:processId (-> (java.lang.ProcessHandle/current) .pid)
            :rootUri root-uri
            :capabilities (or capabilities {})
            :trace "off"}))

(defn initialized-notification
  "Build an initialized notification (sent after initialize response)."
  []
  (notification "initialized" {}))

(defn shutdown-request
  "Build a shutdown request."
  []
  (request "shutdown"))

(defn exit-notification
  "Build an exit notification."
  []
  (notification "exit"))

;; Text synchronization
(defn did-open-notification
  "Build a textDocument/didOpen notification.

   Arguments:
   - uri         - Document URI
   - language-id - Language identifier
   - version     - Document version
   - text        - Document content"
  [uri language-id version text]
  (notification "textDocument/didOpen"
                {:textDocument {:uri uri
                                :languageId language-id
                                :version version
                                :text text}}))

(defn did-change-notification
  "Build a textDocument/didChange notification.

   Arguments:
   - uri     - Document URI
   - version - New version
   - changes - Vector of content changes"
  [uri version changes]
  (notification "textDocument/didChange"
                {:textDocument {:uri uri
                                :version version}
                 :contentChanges changes}))

(defn did-save-notification
  "Build a textDocument/didSave notification."
  [uri]
  (notification "textDocument/didSave"
                {:textDocument {:uri uri}}))

(defn did-close-notification
  "Build a textDocument/didClose notification."
  [uri]
  (notification "textDocument/didClose"
                {:textDocument {:uri uri}}))

;; Language features
(defn hover-request
  "Build a textDocument/hover request."
  [uri line character]
  (request "textDocument/hover"
           {:textDocument {:uri uri}
            :position {:line line :character character}}))

(defn completion-request
  "Build a textDocument/completion request."
  [uri line character]
  (request "textDocument/completion"
           {:textDocument {:uri uri}
            :position {:line line :character character}}))

(defn definition-request
  "Build a textDocument/definition request."
  [uri line character]
  (request "textDocument/definition"
           {:textDocument {:uri uri}
            :position {:line line :character character}}))

(defn references-request
  "Build a textDocument/references request."
  [uri line character include-declaration?]
  (request "textDocument/references"
           {:textDocument {:uri uri}
            :position {:line line :character character}
            :context {:includeDeclaration include-declaration?}}))

(defn formatting-request
  "Build a textDocument/formatting request."
  [uri options]
  (request "textDocument/formatting"
           {:textDocument {:uri uri}
            :options (or options {:tabSize 2
                                  :insertSpaces true})}))

(defn document-symbol-request
  "Build a textDocument/documentSymbol request."
  [uri]
  (request "textDocument/documentSymbol"
           {:textDocument {:uri uri}}))

(defn code-action-request
  "Build a textDocument/codeAction request."
  [uri range diagnostics]
  (request "textDocument/codeAction"
           {:textDocument {:uri uri}
            :range range
            :context {:diagnostics (or diagnostics [])}}))

(defn rename-request
  "Build a textDocument/rename request."
  [uri line character new-name]
  (request "textDocument/rename"
           {:textDocument {:uri uri}
            :position {:line line :character character}
            :newName new-name}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Build an initialize request
  (initialize-request "file:///workspace" {:textDocument {:hover {:contentFormat ["markdown"]}}})

  ;; Encode for transport
  (encode-message (hover-request "file:///foo.clj" 10 5))
  ;; => "Content-Length: ...\r\n\r\n{...}"

  ;; Check message types
  (request? {:id 1 :method "initialize"})   ;; => true
  (notification? {:method "initialized"})    ;; => true
  (response? {:id 1 :result {}})             ;; => true

  :leave-this-here)
