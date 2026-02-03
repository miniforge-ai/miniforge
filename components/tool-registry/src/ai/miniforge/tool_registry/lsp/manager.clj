;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tool-registry.lsp.manager
  "LSP server lifecycle orchestration.

   Manages starting, stopping, and accessing LSP servers for registered tools.

   Layer 0: Manager state
   Layer 1: Server lifecycle (start/stop)
   Layer 2: Client access
   Layer 3: Status and listing"
  (:require
   [ai.miniforge.tool-registry.lsp.process :as process]
   [ai.miniforge.tool-registry.lsp.client :as client]
   [ai.miniforge.tool-registry.schema :as schema]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Manager state

(defn- get-servers-atom
  "Get the servers atom from registry state."
  [registry]
  (or (:lsp-servers @(:state registry))
      (let [servers (atom {})]
        (swap! (:state registry) assoc :lsp-servers servers)
        servers)))

(defn- get-logger
  "Get logger from registry state."
  [registry]
  (:logger @(:state registry)))

;------------------------------------------------------------------------------ Layer 1
;; Server lifecycle

(defn start-server
  "Start an LSP server for a tool.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - LSP tool identifier

   Returns:
   - {:success? true :client LSPClient} on success
   - {:success? false :error string} on failure"
  [registry tool-id]
  (let [servers (get-servers-atom registry)
        logger (get-logger registry)
        tool ((:get-tool-fn @(:state registry)) tool-id)]

    (cond
      ;; Tool not found
      (nil? tool)
      (schema/err "Tool not found: " tool-id)

      ;; Not an LSP tool
      (not (schema/lsp-tool? tool))
      (schema/err "Not an LSP tool: " tool-id)

      ;; Tool disabled
      (not (schema/enabled? tool))
      (schema/err "Tool is disabled: " tool-id)

      ;; Already running
      (get @servers tool-id)
      (let [entry (get @servers tool-id)]
        (if (process/process-alive? (:process entry))
          (schema/ok :client (:client entry))
          ;; Process died, clean up and restart
          (do
            (swap! servers dissoc tool-id)
            (start-server registry tool-id))))

      :else
      (let [config (:tool/config tool)
            command (:lsp/command config)
            opts {:env (:lsp/env config)
                  :working-dir (:lsp/working-dir config)}]

        (when logger
          (log/info logger :system :lsp/starting
                    {:data {:tool-id tool-id :command command}}))

        (let [{:keys [success? process error]} (process/start-process tool-id command opts)]
          (if-not success?
            (do
              (when logger
                (log/error logger :system :lsp/start-failed
                           {:data {:tool-id tool-id :error error}}))
              (schema/err error))

            ;; Create client and initialize
            (let [notification-handler (fn [msg]
                                         (when logger
                                           (log/debug logger :system :lsp/notification
                                                      {:data {:tool-id tool-id
                                                              :method (:method msg)}})))
                  lsp-client (client/create-client process notification-handler)
                  ;; Initialize with workspace root
                  root-uri (str "file://" (or (:lsp/working-dir config)
                                              (System/getProperty "user.dir")))
                  init-opts (:lsp/init-options config {})
                  {:keys [success? capabilities error]} (client/initialize lsp-client root-uri init-opts)]

              (if success?
                (do
                  (swap! servers assoc tool-id {:process process
                                                :client lsp-client
                                                :started-at (System/currentTimeMillis)
                                                :capabilities capabilities})
                  (when logger
                    (log/info logger :system :lsp/started
                              {:data {:tool-id tool-id}}))
                  (schema/ok :client lsp-client))

                ;; Initialization failed, clean up
                (do
                  (process/stop-process process)
                  (when logger
                    (log/error logger :system :lsp/init-failed
                               {:data {:tool-id tool-id :error error}}))
                  (schema/err "Initialization failed: " error))))))))))

(defn stop-server
  "Stop an LSP server.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - LSP tool identifier

   Returns:
   - {:success? true} on success
   - {:success? false :error string} on failure"
  [registry tool-id]
  (let [servers (get-servers-atom registry)
        logger (get-logger registry)
        entry (get @servers tool-id)]

    (if-not entry
      (schema/err "Server not running: " tool-id)

      (do
        (when logger
          (log/info logger :system :lsp/stopping
                    {:data {:tool-id tool-id}}))

        ;; Graceful shutdown
        (try
          (client/shutdown (:client entry))
          (catch Exception _))

        ;; Stop process
        (process/stop-process (:process entry))

        (swap! servers dissoc tool-id)

        (when logger
          (log/info logger :system :lsp/stopped
                    {:data {:tool-id tool-id}}))

        (schema/ok)))))

(defn restart-server
  "Restart an LSP server.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - LSP tool identifier

   Returns result of start-server."
  [registry tool-id]
  (stop-server registry tool-id)
  (start-server registry tool-id))

;------------------------------------------------------------------------------ Layer 2
;; Client access

(defn get-client
  "Get the LSP client for a tool, starting the server if needed.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - LSP tool identifier

   Returns LSP client or nil if unavailable."
  [registry tool-id]
  (let [servers (get-servers-atom registry)
        entry (get @servers tool-id)]
    (cond
      ;; Already running
      (and entry (process/process-alive? (:process entry)))
      (:client entry)

      ;; Not running, try to start
      :else
      (let [{:keys [success? client]} (start-server registry tool-id)]
        (when success? client)))))

(defn server-capabilities
  "Get the capabilities of a running LSP server.

   Returns the capabilities map or nil if not running."
  [registry tool-id]
  (let [servers (get-servers-atom registry)
        entry (get @servers tool-id)]
    (when entry
      (:capabilities entry))))

;------------------------------------------------------------------------------ Layer 3
;; Status and listing

(defn server-status
  "Get the status of an LSP server.

   Returns one of:
   - :stopped  - Server is not running
   - :starting - Server is starting up
   - :running  - Server is running and healthy
   - :error    - Server encountered an error"
  [registry tool-id]
  (let [servers (get-servers-atom registry)
        entry (get @servers tool-id)]
    (if-not entry
      :stopped
      (process/get-state (:process entry)))))

(defn list-servers
  "List all LSP servers with their status.

   Returns sequence of {:tool-id :status :uptime-ms :capabilities}."
  [registry]
  (let [servers (get-servers-atom registry)]
    (for [[tool-id entry] @servers]
      (let [info (process/process-info (:process entry))]
        {:tool-id tool-id
         :status (:state info)
         :uptime-ms (:uptime-ms info)
         :capabilities (:capabilities entry)}))))

(defn stop-all-servers
  "Stop all running LSP servers.

   Returns {:stopped [tool-ids...]}."
  [registry]
  (let [servers (get-servers-atom registry)
        tool-ids (keys @servers)]
    (doseq [tool-id tool-ids]
      (stop-server registry tool-id))
    {:stopped (vec tool-ids)}))

;------------------------------------------------------------------------------ Integration

(defn attach-to-registry
  "Attach LSP manager functions to a registry.

   This allows the registry to report LSP status for tools."
  [registry]
  (swap! (:state registry) assoc
         :lsp-manager {:status-fn (fn [tool-id] (server-status registry tool-id))}
         :get-tool-fn (fn [tool-id]
                        (get @(:tools @(:state registry)) tool-id))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example usage
  ;; (def reg (registry/create-registry))
  ;; (attach-to-registry reg)
  ;; (start-server reg :lsp/clojure)
  ;; (server-status reg :lsp/clojure)
  ;; (list-servers reg)
  ;; (stop-server reg :lsp/clojure)

  :leave-this-here)
