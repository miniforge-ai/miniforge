(ns ai.miniforge.lsp-mcp-bridge.lsp.manager
  "Multi-server LSP manager for the MCP bridge.

   Manages multiple LSP server instances, one per language.
   Starts servers on-demand and tracks open documents.

   Layer 0: Manager state
   Layer 1: Pipeline helpers and steps
   Layer 2: Server lifecycle
   Layer 3: Client access and document management
   Layer 4: Status and cleanup"
  (:require
   [ai.miniforge.lsp-mcp-bridge.lsp.process :as process]
   [ai.miniforge.lsp-mcp-bridge.lsp.client :as client]
   [ai.miniforge.lsp-mcp-bridge.config :as config]
   [ai.miniforge.lsp-mcp-bridge.installer :as installer]
   [ai.miniforge.response.interface :as response]))

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
;; Pipeline helpers and steps

(defn failed? [state] (contains? state :failure))

(defn step-ensure-installed
  "Ensure the LSP binary is installed."
  [state]
  (if (failed? state) state
      (let [{:keys [registry tool-id command]} state
            install-result (installer/ensure-installed registry tool-id command)]
        (if (response/anomaly-map? install-result)
          (assoc state :failure install-result)
          (assoc state :actual-command (:command install-result))))))

(defn step-start-process
  "Start the LSP server subprocess."
  [state]
  (if (failed? state) state
      (let [{:keys [tool-id actual-command opts log-fn]} state]
        (log-fn "Starting LSP server:" (name tool-id) "command:" actual-command)
        (let [proc-result (process/start-process tool-id actual-command opts)]
          (if (response/anomaly-map? proc-result)
            (assoc state :failure proc-result)
            (assoc state :process proc-result))))))

(defn step-create-client
  "Create the LSP client from the running process."
  [state]
  (if (failed? state) state
      (assoc state :client (client/create-client (:process state) nil))))

(defn step-initialize
  "Initialize the LSP server connection."
  [state]
  (if (failed? state) state
      (let [{:keys [client root-uri init-opts process]} state
            init-result (client/initialize client root-uri init-opts)]
        (if (response/anomaly-map? init-result)
          (do
            (process/stop-process process)
            (assoc state :failure init-result))
          state))))

(defn step-register
  "Register the running server in the manager's servers atom."
  [state]
  (if (failed? state) state
      (let [{:keys [manager tool-id process client log-fn]} state
            entry {:process process
                   :client client
                   :open-docs (atom #{})
                   :started-at (System/currentTimeMillis)
                   :last-used (atom (System/currentTimeMillis))}]
        (swap! (:servers manager) assoc tool-id entry)
        (log-fn "LSP server started:" (name tool-id))
        state)))

(defn pipeline->result
  "Convert pipeline state to result."
  [state]
  (if (failed? state)
    (:failure state)
    {:client (:client state)}))

;------------------------------------------------------------------------------ Layer 2
;; Server lifecycle

(defn start-server
  "Start an LSP server for a tool.

   Returns {:client LSPClient} on success, anomaly map on failure."
  [manager tool-config]
  (let [tool-id (:tool/id tool-config)
        lsp-config (:tool/config tool-config)
        project-dir (:project-dir manager)]
    (-> {:manager manager
         :tool-id tool-id
         :registry (get-in manager [:config :registry])
         :command (:lsp/command lsp-config)
         :root-uri (str "file://" (or project-dir (System/getProperty "user.dir")))
         :init-opts (:lsp/init-options lsp-config {})
         :opts {:env (:lsp/env lsp-config)
                :working-dir (or (:lsp/working-dir lsp-config) project-dir)}
         :log-fn (:log-fn manager)}
        step-ensure-installed
        step-start-process
        step-create-client
        step-initialize
        step-register
        pipeline->result)))

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

;------------------------------------------------------------------------------ Layer 3
;; Client access and document management

(defn get-client
  "Get the LSP client for a tool, starting the server if needed.

   Returns {:client LSPClient} or anomaly map."
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
        (if-let [tool-config (get-in (:config manager) [:tool-index tool-id])]
          (start-server manager tool-config)
          (response/make-anomaly :anomalies/not-found
                                 (str "No tool config for: " tool-id))))

      ;; Not started — start
      :else
      (if-let [tool-config (get-in (:config manager) [:tool-index tool-id])]
        (start-server manager tool-config)
        (response/make-anomaly :anomalies/not-found
                               (str "No tool config for: " tool-id))))))

(defn resolve-client-for-file
  "Get the LSP client for a file, starting the server if needed.

   Arguments:
   - manager   - Manager state
   - file-path - Absolute file path

   Returns {:client LSPClient :tool-id keyword} or anomaly map."
  [manager file-path]
  (if-let [tool-config (config/resolve-tool-for-file (:config manager) file-path)]
    (let [tool-id (:tool/id tool-config)
          result (get-client manager tool-id)]
      (if (response/anomaly-map? result)
        result
        (assoc result :tool-id tool-id)))
    (response/make-anomaly :anomalies/not-found
                           (str "No LSP server configured for file: " file-path))))

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

;------------------------------------------------------------------------------ Layer 4
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
