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

(ns ai.miniforge.tool-registry.lsp.client
  "LSP client for communicating with language servers.

   Layer 0: Client state and protocol
   Layer 1: Message I/O (read/write)
   Layer 2: Request/response handling
   Layer 3: High-level operations"
  (:require
   [ai.miniforge.tool-registry.lsp.protocol :as proto]
   [ai.miniforge.tool-registry.lsp.process :as process]
   [ai.miniforge.tool-registry.schema :as schema]
   [clojure.core.async :as async :refer [go-loop]]
   [clojure.string :as str])
  (:import
   [java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter]))

;------------------------------------------------------------------------------ Layer 0
;; Client state and protocol

(defrecord LSPClient
  [process
   tool-id
   reader
   writer
   pending-requests  ; atom: {id -> {:promise :timeout-ch}}
   server-capabilities
   initialized?
   notification-handler])

(def default-timeout-ms 30000)

;------------------------------------------------------------------------------ Layer 1
;; Message I/O

(defn- read-headers
  "Read LSP headers from the reader until blank line."
  [^BufferedReader reader]
  (loop [headers {}]
    (let [line (.readLine reader)]
      (cond
        (nil? line)
        nil ; EOF

        (str/blank? line)
        headers

        :else
        (let [[_ key value] (re-matches #"([^:]+):\s*(.+)" line)]
          (recur (assoc headers (str/lower-case (or key "")) value)))))))

(defn- read-message
  "Read a single LSP message from the reader.
   Returns the parsed message or nil on EOF/error."
  [^BufferedReader reader]
  (try
    (when-let [headers (read-headers reader)]
      (when-let [content-length (some-> (get headers "content-length") parse-long)]
        (let [buffer (char-array content-length)
              _ (.read reader buffer 0 content-length)
              json-str (String. buffer)]
          (proto/decode-message json-str))))
    (catch Exception e
      (println "LSP read error:" (.getMessage e))
      nil)))

(defn- write-message
  "Write an LSP message to the writer."
  [^BufferedWriter writer msg]
  (let [encoded (proto/encode-message msg)]
    (.write writer encoded)
    (.flush writer)))

(defn- start-reader-loop
  "Start a background loop reading messages from the server."
  [client]
  (let [{:keys [reader pending-requests notification-handler]} client]
    (go-loop []
      (when-let [msg (read-message reader)]
        (cond
          ;; Response to a request
          (proto/response? msg)
          (when-let [{:keys [promise]} (get @pending-requests (:id msg))]
            (swap! pending-requests dissoc (:id msg))
            (deliver promise msg))

          ;; Notification from server
          (proto/notification? msg)
          (when notification-handler
            (try
              (notification-handler msg)
              (catch Exception e
                (println "Notification handler error:" (.getMessage e)))))

          ;; Request from server (not common, but possible)
          (proto/request? msg)
          (println "Server request (unhandled):" (:method msg)))

        (recur)))))

;------------------------------------------------------------------------------ Layer 2
;; Request/response handling

(defn send-request
  "Send a request and return a promise for the response.

   Arguments:
   - client  - LSPClient instance
   - request - Request message map

   Returns a promise that will be delivered with the response."
  [client request]
  (let [{:keys [writer pending-requests]} client
        id (:id request)
        p (promise)]
    (swap! pending-requests assoc id {:promise p})
    (write-message writer request)
    p))

(defn send-request-sync
  "Send a request and wait for the response synchronously.

   Arguments:
   - client     - LSPClient instance
   - request    - Request message map
   - timeout-ms - Timeout in milliseconds (default 30000)

   Returns:
   - {:success? true :result any} on success
   - {:success? false :error string} on failure/timeout"
  ([client request]
   (send-request-sync client request default-timeout-ms))
  ([client request timeout-ms]
   (let [p (send-request client request)
         result (deref p timeout-ms ::timeout)]
     (cond
       (= result ::timeout)
       (schema/err "Request timed out")

       (proto/error? result)
       (schema/err :code (get-in result [:error :code])
                   (get-in result [:error :message] "Unknown error"))

       :else
       (schema/ok :result (:result result))))))

(defn send-notification
  "Send a notification (no response expected).

   Arguments:
   - client       - LSPClient instance
   - notification - Notification message map"
  [client notification]
  (let [{:keys [writer]} client]
    (write-message writer notification)))

;------------------------------------------------------------------------------ Layer 3
;; High-level operations

(defn create-client
  "Create an LSP client from a running process.

   Arguments:
   - lsp-process          - LSPProcess record
   - notification-handler - Optional fn to handle server notifications

   Returns an LSPClient record."
  [lsp-process notification-handler]
  (let [in-stream (process/get-input-stream lsp-process)
        out-stream (process/get-output-stream lsp-process)
        reader (BufferedReader. (InputStreamReader. in-stream "UTF-8"))
        writer (BufferedWriter. (OutputStreamWriter. out-stream "UTF-8"))
        client (->LSPClient lsp-process
                            (:tool-id lsp-process)
                            reader
                            writer
                            (atom {})
                            (atom nil)
                            (atom false)
                            notification-handler)]
    ;; Start background reader
    (start-reader-loop client)
    client))

(defn initialize
  "Initialize the LSP server.

   Arguments:
   - client     - LSPClient instance
   - root-uri   - Workspace root URI
   - capabilities - Client capabilities map

   Returns:
   - {:success? true :capabilities server-capabilities}
   - {:success? false :error string}"
  [client root-uri capabilities]
  (let [request (proto/initialize-request root-uri capabilities)
        {:keys [success? result error]} (send-request-sync client request 60000)]
    (if success?
      (do
        ;; Store server capabilities
        (reset! (:server-capabilities client) (:capabilities result))
        ;; Send initialized notification
        (send-notification client (proto/initialized-notification))
        (reset! (:initialized? client) true)
        (process/mark-running! (:process client))
        (schema/ok :capabilities (:capabilities result)))
      (schema/err error))))

(defn shutdown
  "Shutdown the LSP server gracefully.

   Returns:
   - {:success? true}
   - {:success? false :error string}"
  [client]
  (let [{:keys [success? error]} (send-request-sync client (proto/shutdown-request) 10000)]
    (if success?
      (do
        (send-notification client (proto/exit-notification))
        (reset! (:initialized? client) false)
        (schema/ok))
      (schema/err error))))

(defn initialized?
  "Check if the client has been initialized."
  [client]
  @(:initialized? client))

(defn server-capabilities
  "Get the server's capabilities."
  [client]
  @(:server-capabilities client))

;; Document operations

(defn open-document
  "Notify server that a document was opened."
  [client uri language-id text]
  (send-notification client
                     (proto/did-open-notification uri language-id 1 text)))

(defn close-document
  "Notify server that a document was closed."
  [client uri]
  (send-notification client
                     (proto/did-close-notification uri)))

(defn change-document
  "Notify server of document changes."
  [client uri version changes]
  (send-notification client
                     (proto/did-change-notification uri version changes)))

;; Language features

(defn hover
  "Get hover information at a position.

   Returns:
   - {:success? true :result hover-info}
   - {:success? false :error string}"
  [client uri line character]
  (send-request-sync client
                     (proto/hover-request uri line character)))

(defn completion
  "Get completion items at a position.

   Returns:
   - {:success? true :result completion-list}
   - {:success? false :error string}"
  [client uri line character]
  (send-request-sync client
                     (proto/completion-request uri line character)))

(defn definition
  "Get definition location for symbol at position.

   Returns:
   - {:success? true :result location(s)}
   - {:success? false :error string}"
  [client uri line character]
  (send-request-sync client
                     (proto/definition-request uri line character)))

(defn references
  "Get references to symbol at position.

   Returns:
   - {:success? true :result locations}
   - {:success? false :error string}"
  [client uri line character include-declaration?]
  (send-request-sync client
                     (proto/references-request uri line character include-declaration?)))

(defn format-document
  "Format a document.

   Returns:
   - {:success? true :result text-edits}
   - {:success? false :error string}"
  [client uri options]
  (send-request-sync client
                     (proto/formatting-request uri options)))

(defn document-symbols
  "Get symbols in a document.

   Returns:
   - {:success? true :result symbols}
   - {:success? false :error string}"
  [client uri]
  (send-request-sync client
                     (proto/document-symbol-request uri)))

(defn code-actions
  "Get available code actions for a range.

   Returns:
   - {:success? true :result code-actions}
   - {:success? false :error string}"
  [client uri range diagnostics]
  (send-request-sync client
                     (proto/code-action-request uri range diagnostics)))

(defn rename-symbol
  "Rename a symbol.

   Returns:
   - {:success? true :result workspace-edit}
   - {:success? false :error string}"
  [client uri line character new-name]
  (send-request-sync client
                     (proto/rename-request uri line character new-name)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example usage (requires running LSP server)
  ;; (def proc (process/start-process :lsp/clojure ["clojure-lsp"] {}))
  ;; (def client (create-client (:process proc) println))
  ;; (initialize client "file:///workspace" {})
  ;; (hover client "file:///workspace/src/foo.clj" 10 5)
  ;; (shutdown client)

  :leave-this-here)
