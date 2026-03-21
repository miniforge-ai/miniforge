(ns ai.miniforge.lsp-mcp-bridge.mcp.server
  "MCP server — stdio JSON-RPC loop.

   Reads JSON-RPC requests from stdin, dispatches to handlers,
   writes responses to stdout. Logs to stderr.

   Layer 0: Request handlers
   Layer 1: Server loop"
  (:require
   [ai.miniforge.lsp-mcp-bridge.mcp.protocol :as proto]
   [ai.miniforge.lsp-mcp-bridge.mcp.tools :as tools]
   [ai.miniforge.response.interface :as response])
  (:import
   [java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter]))

;------------------------------------------------------------------------------ Layer 0
;; Request handlers

(defn handle-initialize
  "Handle MCP initialize request."
  [_params]
  (proto/initialize-result))

(defn handle-tools-list
  "Handle MCP tools/list request."
  [_params]
  (proto/tools-list-result tools/tool-definitions))

(defn handle-tools-call
  "Handle MCP tools/call request."
  [params manager]
  (tools/call-tool params manager))

;------------------------------------------------------------------------------ Layer 1
;; Server loop

(defn dispatch-request
  "Dispatch a JSON-RPC request to the appropriate handler.

   Returns the result to include in the response, or nil for notifications."
  [method params manager]
  (case method
    "initialize"                  (handle-initialize params)
    "tools/list"                  (handle-tools-list params)
    "tools/call"                  (handle-tools-call params manager)
    ;; Notifications — no response needed
    "notifications/initialized"   nil
    "notifications/cancelled"     nil
    ;; Unknown method
    (throw (ex-info (str "Method not found: " method)
                    {:code -32601}))))

(defn start-server
  "Start the MCP server.

   Reads JSON-RPC messages from stdin, dispatches to handlers,
   writes responses to stdout. Blocks until stdin closes.

   Arguments:
   - manager - LSP manager state"
  [manager]
  (let [reader (BufferedReader. (InputStreamReader. System/in "UTF-8"))
        writer (BufferedWriter. (OutputStreamWriter. System/out "UTF-8"))]

    (binding [*out* *err*]
      (println "miniforge-lsp MCP server started"))

    (loop []
      (when-let [msg (proto/read-message reader)]
        (when (proto/request? msg)
          (let [id (:id msg)
                method (:method msg)
                params (:params msg)]
            (try
              (when-let [result (dispatch-request method params manager)]
                (proto/write-message writer (proto/response id result)))
              (catch Exception e
                (let [anomaly (response/from-exception e)
                      code (or (:code (ex-data e)) -32603)]
                  (binding [*out* *err*]
                    (println "Error handling" method ":" (:anomaly/message anomaly)))
                  (proto/write-message writer
                                      (proto/error-response id code (:anomaly/message anomaly))))))))
        (recur)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; The server is started from main.clj:
  ;; (start-server manager)

  :leave-this-here)
