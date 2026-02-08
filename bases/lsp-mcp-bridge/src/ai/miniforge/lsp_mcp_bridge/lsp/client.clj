(ns ai.miniforge.lsp-mcp-bridge.lsp.client
  "LSP client for Babashka.

   Port of ai.miniforge.tool-registry.lsp.client without core.async.
   Uses Thread + atoms/promises for async message handling.

   Layer 0: Client state
   Layer 1: Message I/O
   Layer 2: Request/response handling
   Layer 3: High-level operations"
  (:require
   [ai.miniforge.lsp-mcp-bridge.lsp.protocol :as proto]
   [ai.miniforge.lsp-mcp-bridge.lsp.process :as process])
  (:import
   [java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter]))

;------------------------------------------------------------------------------ Layer 0
;; Client state

(defrecord LSPClient
  [process           ; LSPProcess record
   tool-id           ; Tool identifier keyword
   reader            ; BufferedReader (from LSP stdout)
   writer            ; BufferedWriter (to LSP stdin)
   pending-requests  ; atom: {id -> promise}
   server-capabilities ; atom
   initialized?      ; atom
   notification-handler ; fn or nil
   diagnostics-buffer   ; atom: {uri -> [diagnostic ...]}
   reader-thread])      ; Thread

(def default-timeout-ms 30000)

;------------------------------------------------------------------------------ Layer 1
;; Message I/O — delegated to LSP protocol

(defn- start-reader-loop
  "Start a background thread reading messages from the LSP server."
  [client]
  (let [{:keys [reader pending-requests notification-handler diagnostics-buffer]} client
        thread (Thread.
                (fn []
                  (try
                    (loop []
                      (when-let [msg (proto/read-message reader)]
                        (cond
                          ;; Response to a pending request
                          (proto/response? msg)
                          (let [id (:id msg)
                                p  (let [pending @pending-requests]
                                     (when (contains? pending id)
                                       (swap! pending-requests dissoc id)
                                       (get pending id)))]
                            (when p
                              (deliver p msg)))

                          ;; Notification from server
                          (proto/notification? msg)
                          (do
                            ;; Buffer diagnostics
                            (when (and diagnostics-buffer
                                       (= (:method msg) "textDocument/publishDiagnostics"))
                              (let [uri (get-in msg [:params :uri])
                                    diags (get-in msg [:params :diagnostics])]
                                (swap! diagnostics-buffer assoc uri (vec diags))))
                            ;; Call notification handler
                            (when notification-handler
                              (try
                                (notification-handler msg)
                                (catch Exception e
                                  (binding [*out* *err*]
                                    (println "Notification handler error:" (.getMessage e)))))))

                          ;; Request from server (rare)
                          (proto/request? msg)
                          (binding [*out* *err*]
                            (println "Server request (unhandled):" (:method msg))))

                        (recur)))
                    (catch Exception e
                      (when (process/process-alive? (:process client))
                        (binding [*out* *err*]
                          (println "LSP reader error:" (.getMessage e))))))))]
    (.setDaemon thread true)
    (.setName thread (str "lsp-reader-" (name (:tool-id client))))
    (.start thread)
    thread))

;------------------------------------------------------------------------------ Layer 2
;; Request/response handling

(defn send-request
  "Send a request and return a promise for the response."
  [client request]
  (let [{:keys [writer pending-requests]} client
        id (:id request)
        p  (promise)]
    (swap! pending-requests assoc id p)
    (proto/write-message writer request)
    p))

(defn send-request-sync
  "Send a request and wait for the response synchronously.

   Returns:
   - {:result any} on success
   - {:error message} on failure/timeout"
  ([client request]
   (send-request-sync client request default-timeout-ms))
  ([client request timeout-ms]
   (let [p (send-request client request)]
     (try
       (let [result (deref p timeout-ms ::timeout)]
         (cond
           (= result ::timeout)
           (do
             (swap! (:pending-requests client) dissoc (:id request))
             {:error "Request timed out"})

           (nil? result)
           {:error "No response received"}

           (proto/error? result)
           {:error (get-in result [:error :message] "Unknown error")
            :code (get-in result [:error :code])}

           :else
           {:result (:result result)}))
       (catch Exception e
         {:error (.getMessage e)})))))

(defn send-notification
  "Send a notification (no response expected)."
  [client notification]
  (proto/write-message (:writer client) notification))

;------------------------------------------------------------------------------ Layer 3
;; High-level operations

(defn create-client
  "Create an LSP client from a running process.

   Arguments:
   - lsp-process          - Process record from process/start-process
   - notification-handler - Optional fn to handle server notifications"
  [lsp-process notification-handler]
  (let [in-stream (process/get-input-stream lsp-process)
        out-stream (process/get-output-stream lsp-process)
        reader (BufferedReader. (InputStreamReader. in-stream "UTF-8"))
        writer (BufferedWriter. (OutputStreamWriter. out-stream "UTF-8"))
        client (map->LSPClient
                {:process lsp-process
                 :tool-id (:tool-id lsp-process)
                 :reader reader
                 :writer writer
                 :pending-requests (atom {})
                 :server-capabilities (atom nil)
                 :initialized? (atom false)
                 :notification-handler notification-handler
                 :diagnostics-buffer (atom {})
                 :reader-thread nil})]
    ;; Start background reader
    (let [thread (start-reader-loop client)]
      (assoc client :reader-thread thread))))

(defn initialize
  "Initialize the LSP server.

   Returns:
   - {:capabilities server-capabilities} on success
   - {:error message} on failure"
  [client root-uri capabilities]
  (let [request (proto/initialize-request root-uri capabilities)
        result (send-request-sync client request 60000)]
    (if (:error result)
      result
      (do
        (reset! (:server-capabilities client) (:capabilities (:result result)))
        (send-notification client (proto/initialized-notification))
        (reset! (:initialized? client) true)
        {:capabilities (:capabilities (:result result))}))))

(defn shutdown
  "Shutdown the LSP server gracefully."
  [client]
  (let [result (send-request-sync client (proto/shutdown-request) 10000)]
    (if (:error result)
      result
      (do
        (send-notification client (proto/exit-notification))
        (reset! (:initialized? client) false)
        {}))))

(defn initialized?
  "Check if the client has been initialized."
  [client]
  @(:initialized? client))

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

;; Language features

(defn hover
  "Get hover information at a position."
  [client uri line character]
  (send-request-sync client
                     (proto/hover-request uri line character)))

(defn completion
  "Get completion items at a position."
  [client uri line character]
  (send-request-sync client
                     (proto/completion-request uri line character)))

(defn definition
  "Get definition location for symbol at position."
  [client uri line character]
  (send-request-sync client
                     (proto/definition-request uri line character)))

(defn references
  "Get references to symbol at position."
  [client uri line character include-declaration?]
  (send-request-sync client
                     (proto/references-request uri line character include-declaration?)))

(defn format-document
  "Format a document."
  [client uri options]
  (send-request-sync client
                     (proto/formatting-request uri options)))

(defn document-symbols
  "Get symbols in a document."
  [client uri]
  (send-request-sync client
                     (proto/document-symbol-request uri)))

(defn code-actions
  "Get available code actions for a range."
  [client uri range diagnostics]
  (send-request-sync client
                     (proto/code-action-request uri range diagnostics)))

(defn rename-symbol
  "Rename a symbol."
  [client uri line character new-name]
  (send-request-sync client
                     (proto/rename-request uri line character new-name)))

(defn get-diagnostics
  "Get buffered diagnostics for a URI.
   Returns vector of diagnostics or empty vector."
  [client uri]
  (get @(:diagnostics-buffer client) uri []))

(defn clear-diagnostics
  "Clear buffered diagnostics for a URI."
  [client uri]
  (swap! (:diagnostics-buffer client) dissoc uri))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example usage (requires running LSP server)
  ;; (def proc (process/start-process :lsp/clojure ["clojure-lsp"] {}))
  ;; (def client (create-client proc nil))
  ;; (initialize client "file:///workspace" {})
  ;; (hover client "file:///workspace/src/foo.clj" 10 5)
  ;; (shutdown client)

  :leave-this-here)
