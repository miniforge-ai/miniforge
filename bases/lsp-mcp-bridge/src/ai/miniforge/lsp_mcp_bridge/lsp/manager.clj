(ns ai.miniforge.lsp-mcp-bridge.lsp.manager
  "Multi-server LSP manager for the MCP bridge.

   Manages multiple LSP server instances, one per language.
   Starts servers on-demand and tracks open documents.

   Layer 0: Manager state
   Layer 1: Server lifecycle
   Layer 2: Client access and document management
   Layer 3: Status and cleanup"
  (:require
   [ai.miniforge.lsp-mcp-bridge.lsp.process :as process]
   [ai.miniforge.lsp-mcp-bridge.lsp.client :as client]
   [ai.miniforge.lsp-mcp-bridge.config :as config]
   [ai.miniforge.lsp-mcp-bridge.installer :as installer]))

;------------------------------------------------------------------------------ Layer 0
;; Manager state

(defn create-manager
  "Create a new LSP manager.

   Arguments:
   - config      - Config map from config/load-config
   - project-dir - Project root directory

   Returns manager state map."
  [cfg project-dir]
  {:config cfg
   :project-dir project-dir
   :servers (atom {})         ; {tool-id {:process :client :open-docs :started-at :last-used}}
   :log-fn (fn [& args]
             (binding [*out* *err*]
               (apply println args)))})

;------------------------------------------------------------------------------ Layer 1
;; Server lifecycle

(defn- start-server
  "Start an LSP server for a tool.

   Returns {:client LSPClient} on success, {:error msg} on failure."
  [manager tool-config]
  (let [{:keys [config project-dir log-fn]} manager
        tool-id (:tool/id tool-config)
        lsp-config (:tool/config tool-config)
        command (:lsp/command lsp-config)
        registry (:registry config)]

    ;; Ensure binary is installed
    (let [install-result (installer/ensure-installed registry tool-id command)]
      (if (:error install-result)
        install-result
        (let [actual-command (:command install-result)
              root-uri (str "file://" (or project-dir (System/getProperty "user.dir")))
              opts {:env (:lsp/env lsp-config)
                    :working-dir (or (:lsp/working-dir lsp-config) project-dir)}]

          (log-fn "Starting LSP server:" (name tool-id) "command:" actual-command)

          (let [proc-result (process/start-process tool-id actual-command opts)]
            (if (:error proc-result)
              proc-result
              (let [lsp-client (client/create-client proc-result nil)
                    init-opts (:lsp/init-options lsp-config {})
                    init-result (client/initialize lsp-client root-uri init-opts)]
                (if (:error init-result)
                  (do
                    (process/stop-process proc-result)
                    init-result)
                  (let [entry {:process proc-result
                               :client lsp-client
                               :open-docs (atom #{})
                               :started-at (System/currentTimeMillis)
                               :last-used (atom (System/currentTimeMillis))}]
                    (swap! (:servers manager) assoc tool-id entry)
                    (log-fn "LSP server started:" (name tool-id))
                    {:client lsp-client}))))))))))

(defn stop-server
  "Stop an LSP server."
  [manager tool-id]
  (when-let [entry (get @(:servers manager) tool-id)]
    (try
      (client/shutdown (:client entry))
      (catch Exception _))
    (process/stop-process (:process entry))
    (swap! (:servers manager) dissoc tool-id)
    ((:log-fn manager) "LSP server stopped:" (name tool-id))))

;------------------------------------------------------------------------------ Layer 2
;; Client access and document management

(defn get-client
  "Get the LSP client for a tool, starting the server if needed.

   Returns {:client LSPClient} or {:error msg}."
  [manager tool-id]
  (let [entry (get @(:servers manager) tool-id)]
    (cond
      ;; Running and alive
      (and entry (process/process-alive? (:process entry)))
      (do
        (reset! (:last-used entry) (System/currentTimeMillis))
        {:client (:client entry)})

      ;; Dead — clean up and restart
      entry
      (do
        (swap! (:servers manager) dissoc tool-id)
        (when-let [tool-config (get-in (:config manager) [:tool-index tool-id])]
          (start-server manager tool-config)))

      ;; Not started — start
      :else
      (when-let [tool-config (get-in (:config manager) [:tool-index tool-id])]
        (start-server manager tool-config)))))

(defn resolve-client-for-file
  "Get the LSP client for a file, starting the server if needed.

   Arguments:
   - manager   - Manager state
   - file-path - Absolute file path

   Returns {:client LSPClient :tool-id keyword} or {:error msg}."
  [manager file-path]
  (if-let [tool-config (config/resolve-tool-for-file (:config manager) file-path)]
    (let [tool-id (:tool/id tool-config)
          result (get-client manager tool-id)]
      (if (:error result)
        result
        (assoc result :tool-id tool-id)))
    {:error (str "No LSP server configured for file: " file-path)}))

(defn ensure-document-open
  "Ensure a document is open on the LSP server.
   Reads the file from disk and sends didOpen if not already open."
  [manager tool-id lsp-client file-path]
  (let [uri (str "file://" file-path)
        entry (get @(:servers manager) tool-id)
        open-docs (:open-docs entry)]
    (when (and open-docs (not (contains? @open-docs uri)))
      (let [language (config/language-id file-path)
            content (slurp file-path)]
        (client/open-document lsp-client uri (or language "text") content)
        (swap! open-docs conj uri)))))

;------------------------------------------------------------------------------ Layer 3
;; Status and cleanup

(defn list-servers
  "List all LSP servers with their status."
  [manager]
  (for [[tool-id entry] @(:servers manager)]
    {:tool-id tool-id
     :alive? (process/process-alive? (:process entry))
     :uptime-ms (- (System/currentTimeMillis) (:started-at entry))
     :open-docs (count @(:open-docs entry))
     :last-used @(:last-used entry)}))

(defn stop-all-servers
  "Stop all running LSP servers."
  [manager]
  (doseq [tool-id (keys @(:servers manager))]
    (stop-server manager tool-id)))

(defn available-tools
  "List all available LSP tool configs."
  [manager]
  (get-in manager [:config :tools]))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Usage:
  ;; (def cfg (config/load-config "/path/to/project"))
  ;; (def mgr (create-manager cfg "/path/to/project"))
  ;; (resolve-client-for-file mgr "/path/to/project/src/foo.clj")
  ;; (list-servers mgr)
  ;; (stop-all-servers mgr)

  :leave-this-here)
