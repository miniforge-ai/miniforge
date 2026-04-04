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

(ns ai.miniforge.lsp-mcp-bridge.lsp.protocol
  "LSP JSON-RPC protocol types and message builders.

   Port of ai.miniforge.tool-registry.lsp.protocol for Babashka compatibility.
   Uses Content-Length framed messages (standard LSP transport).

   Layer 0: Constants and message IDs
   Layer 1: Message builders
   Layer 2: Message encoding/decoding (Content-Length framing)
   Layer 3: Standard LSP methods"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and message IDs

(def id-counter (atom 0))

(defn next-id
  "Generate the next message ID."
  []
  (swap! id-counter inc))

(def jsonrpc-version "2.0")

;------------------------------------------------------------------------------ Layer 1
;; Message builders

(defn request
  "Build a JSON-RPC request message."
  ([method]
   (request method nil))
  ([method params]
   (cond-> {:id (next-id)
            :jsonrpc jsonrpc-version
            :method method}
     params (assoc :params params))))

(defn response
  "Build a JSON-RPC response message."
  [id result]
  {:id id
   :jsonrpc jsonrpc-version
   :result result})

(defn notification
  "Build a JSON-RPC notification message."
  ([method]
   (notification method nil))
  ([method params]
   (cond-> {:jsonrpc jsonrpc-version
            :method method}
     params (assoc :params params))))

;------------------------------------------------------------------------------ Layer 2
;; Message encoding/decoding — Content-Length framing

(defn encode-message
  "Encode a message for LSP transport (with Content-Length header).
   Returns a string with headers and JSON body."
  [msg]
  (let [body (json/generate-string msg)
        length (count (.getBytes ^String body "UTF-8"))]
    (str "Content-Length: " length "\r\n\r\n" body)))

(defn decode-message
  "Decode a JSON-RPC message from JSON string."
  [json-str]
  (json/parse-string json-str true))

(defn read-headers
  "Read LSP headers from a reader until blank line.
   Returns headers map or nil on EOF."
  [^java.io.BufferedReader reader]
  (loop [headers {}]
    (let [line (.readLine reader)]
      (cond
        (nil? line)
        nil

        (str/blank? line)
        headers

        :else
        (let [[_ key value] (re-matches #"([^:]+):\s*(.+)" line)]
          (recur (assoc headers (str/lower-case (or key "")) (str/trim (or value "")))))))))

(defn read-message
  "Read a single LSP message from the reader.
   Returns the parsed message or nil on EOF/error."
  [^java.io.BufferedReader reader]
  (try
    (when-let [headers (read-headers reader)]
      (when-let [content-length (some-> (get headers "content-length") parse-long)]
        (let [buffer (char-array content-length)
              _ (.read reader buffer 0 content-length)
              json-str (String. buffer)]
          (decode-message json-str))))
    (catch Exception _e
      nil)))

(defn write-message
  "Write an LSP message to a writer (with Content-Length header)."
  [^java.io.BufferedWriter writer msg]
  (let [encoded (encode-message msg)]
    (.write writer ^String encoded)
    (.flush writer)))

(defn request?
  "Check if a message is a request."
  [msg]
  (and (contains? msg :id)
       (contains? msg :method)))

(defn response?
  "Check if a message is a response."
  [msg]
  (and (contains? msg :id)
       (not (contains? msg :method))))

(defn notification?
  "Check if a message is a notification."
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
  "Build an initialize request."
  [root-uri capabilities]
  (request "initialize"
           {:processId (.pid (java.lang.ProcessHandle/current))
            :rootUri root-uri
            :capabilities (or capabilities {})
            :trace "off"}))

(defn initialized-notification []
  (notification "initialized" {}))

(defn shutdown-request []
  (request "shutdown"))

(defn exit-notification []
  (notification "exit"))

;; Text synchronization
(defn did-open-notification
  "Build a textDocument/didOpen notification."
  [uri language-id version text]
  (notification "textDocument/didOpen"
                {:textDocument {:uri uri
                                :languageId language-id
                                :version version
                                :text text}}))

(defn did-close-notification [uri]
  (notification "textDocument/didClose"
                {:textDocument {:uri uri}}))

(defn did-change-notification [uri version changes]
  (notification "textDocument/didChange"
                {:textDocument {:uri uri :version version}
                 :contentChanges changes}))

;; Language features
(defn hover-request [uri line character]
  (request "textDocument/hover"
           {:textDocument {:uri uri}
            :position {:line line :character character}}))

(defn completion-request [uri line character]
  (request "textDocument/completion"
           {:textDocument {:uri uri}
            :position {:line line :character character}}))

(defn definition-request [uri line character]
  (request "textDocument/definition"
           {:textDocument {:uri uri}
            :position {:line line :character character}}))

(defn references-request [uri line character include-declaration?]
  (request "textDocument/references"
           {:textDocument {:uri uri}
            :position {:line line :character character}
            :context {:includeDeclaration include-declaration?}}))

(defn formatting-request [uri options]
  (request "textDocument/formatting"
           {:textDocument {:uri uri}
            :options (or options {:tabSize 2 :insertSpaces true})}))

(defn document-symbol-request [uri]
  (request "textDocument/documentSymbol"
           {:textDocument {:uri uri}}))

(defn code-action-request [uri range diagnostics]
  (request "textDocument/codeAction"
           {:textDocument {:uri uri}
            :range range
            :context {:diagnostics (or diagnostics [])}}))

(defn rename-request [uri line character new-name]
  (request "textDocument/rename"
           {:textDocument {:uri uri}
            :position {:line line :character character}
            :newName new-name}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (encode-message (hover-request "file:///foo.clj" 10 5))
  (request? {:id 1 :method "initialize"})
  (notification? {:method "initialized"})
  (response? {:id 1 :result {}})

  :leave-this-here)
