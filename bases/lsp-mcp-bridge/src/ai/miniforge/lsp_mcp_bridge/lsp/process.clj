(ns ai.miniforge.lsp-mcp-bridge.lsp.process
  "LSP server process management for Babashka.

   Uses babashka.process for subprocess management.

   Layer 0: Process lifecycle
   Layer 1: Process state and health"
  (:require
   [babashka.process :as bp]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Process lifecycle

(defn start-process
  "Start an LSP server subprocess.

   Arguments:
   - tool-id - Tool identifier keyword
   - command - Vector of command + args (e.g. [\"clojure-lsp\"])
   - opts    - Options map with :env, :working-dir

   Returns:
   - {:process Process :tool-id :command :state atom :started-at ms}
   - Anomaly map on failure"
  [tool-id command opts]
  (try
    (let [env (merge {} (:env opts))
          dir (:working-dir opts)
          proc (bp/process (into command [])
                           (cond-> {:in :pipe
                                    :out :pipe
                                    :err :pipe
                                    :shutdown bp/destroy-tree}
                             (seq env) (assoc :extra-env env)
                             dir (assoc :dir dir)))]
      {:process proc
       :tool-id tool-id
       :command command
       :state (atom :running)
       :started-at (System/currentTimeMillis)})
    (catch Exception e
      (response/from-exception e))))

(defn stop-process
  "Stop an LSP server process gracefully."
  [lsp-process]
  (when-let [proc (:process lsp-process)]
    (try
      (bp/destroy-tree proc)
      (reset! (:state lsp-process) :stopped)
      (catch Exception _))))

(defn process-alive?
  "Check if the LSP server process is still running."
  [lsp-process]
  (when-let [proc (:process lsp-process)]
    (.isAlive ^Process (:proc proc))))

(defn get-input-stream
  "Get the stdout stream from the LSP process (for reading responses)."
  [lsp-process]
  (:out (:process lsp-process)))

(defn get-output-stream
  "Get the stdin stream of the LSP process (for sending requests)."
  [lsp-process]
  (:in (:process lsp-process)))

(defn get-error-stream
  "Get the stderr stream of the LSP process."
  [lsp-process]
  (:err (:process lsp-process)))

;------------------------------------------------------------------------------ Layer 1
;; Process state and health

(defn get-state
  "Get the current state of the process."
  [lsp-process]
  @(:state lsp-process))

(defn uptime-ms
  "Get the uptime of the process in milliseconds."
  [lsp-process]
  (- (System/currentTimeMillis) (:started-at lsp-process)))

(defn process-info
  "Get summary info about the process."
  [lsp-process]
  {:tool-id (:tool-id lsp-process)
   :command (:command lsp-process)
   :state (get-state lsp-process)
   :alive? (process-alive? lsp-process)
   :uptime-ms (uptime-ms lsp-process)})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Start a process
  (def proc (start-process :lsp/clojure ["clojure-lsp"] {}))
  (process-alive? proc)
  (process-info proc)
  (stop-process proc)

  :leave-this-here)
