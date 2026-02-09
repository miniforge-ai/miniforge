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

(ns ai.miniforge.tool-registry.lsp.process
  "LSP server process lifecycle management.

   Layer 0: Process state definitions
   Layer 1: Process start/stop
   Layer 2: Health checking
   Layer 3: Process info and cleanup"
  (:require
   [ai.miniforge.tool-registry.schema :as schema]
   [clojure.java.io :as io])
  (:import
   [java.lang ProcessBuilder ProcessBuilder$Redirect]
   [java.io InputStream OutputStream]))

;------------------------------------------------------------------------------ Layer 0
;; Process state definitions

(def process-states
  "Valid process states."
  #{:stopped :starting :running :stopping :error})

(defrecord LSPProcess
  [^Process process
   ^InputStream stdin
   ^OutputStream stdout
   tool-id
   command
   started-at
   state-atom
   env
   working-dir])

;------------------------------------------------------------------------------ Layer 1
;; Process start/stop

(defn start-process
  "Start an LSP server process.

   Arguments:
   - tool-id     - Tool identifier
   - command     - Vector of command and args [\"clojure-lsp\"]
   - opts        - Options map:
                   :env - Environment variables map
                   :working-dir - Working directory path

   Returns:
   - {:success? true :process LSPProcess} on success
   - {:success? false :error string} on failure"
  [tool-id command opts]
  (try
    (let [{:keys [env working-dir]} opts
          pb (ProcessBuilder. ^java.util.List (vec command))
          _ (when working-dir
              (.directory pb (io/file working-dir)))
          _ (when env
              (let [pb-env (.environment pb)]
                (doseq [[k v] env]
                  (.put pb-env k v))))
          ;; Don't redirect stderr - let it go to parent process for debugging
          _ (.redirectError pb ProcessBuilder$Redirect/INHERIT)
          process (.start pb)
          stdin (.getOutputStream process)   ; We write to process stdin
          stdout (.getInputStream process)   ; We read from process stdout
          state-atom (atom :starting)]
      (schema/ok :process (->LSPProcess process
                                        stdout  ; LSP messages come from process stdout
                                        stdin   ; We send to process stdin
                                        tool-id
                                        command
                                        (System/currentTimeMillis)
                                        state-atom
                                        env
                                        working-dir)))
    (catch Exception e
      (schema/err "Failed to start process: " (.getMessage e)))))

(defn stop-process
  "Stop an LSP server process.

   Arguments:
   - lsp-process - LSPProcess record

   Returns:
   - {:success? true} on success
   - {:success? false :error string} on failure"
  [lsp-process]
  (try
    (let [{:keys [process state-atom stdin stdout]} lsp-process]
      (reset! state-atom :stopping)

      ;; Close streams
      (try (.close stdin) (catch Exception _))
      (try (.close stdout) (catch Exception _))

      ;; Destroy process
      (when (.isAlive process)
        (.destroy process)
        ;; Wait up to 5 seconds for graceful shutdown
        (let [exited? (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)]
          (when-not exited?
            ;; Force kill if still running
            (.destroyForcibly process))))

      (reset! state-atom :stopped)
      (schema/ok))
    (catch Exception e
      (schema/err "Failed to stop process: " (.getMessage e)))))

(defn process-alive?
  "Check if a process is still alive."
  [lsp-process]
  (when-let [process (:process lsp-process)]
    (.isAlive process)))

;------------------------------------------------------------------------------ Layer 2
;; Health checking

(defn get-state
  "Get the current state of an LSP process."
  [lsp-process]
  (if lsp-process
    (let [state @(:state-atom lsp-process)
          alive? (process-alive? lsp-process)]
      (cond
        (and (= state :running) (not alive?)) :error
        (and (= state :starting) (not alive?)) :error
        :else state))
    :stopped))

(defn set-state!
  "Set the state of an LSP process."
  [lsp-process new-state]
  (when (and lsp-process (contains? process-states new-state))
    (reset! (:state-atom lsp-process) new-state)))

(defn mark-running!
  "Mark an LSP process as running (after successful initialization)."
  [lsp-process]
  (set-state! lsp-process :running))

(defn mark-error!
  "Mark an LSP process as errored."
  [lsp-process]
  (set-state! lsp-process :error))

;------------------------------------------------------------------------------ Layer 3
;; Process info and cleanup

(defn process-info
  "Get information about an LSP process."
  [lsp-process]
  (when lsp-process
    {:tool-id (:tool-id lsp-process)
     :command (:command lsp-process)
     :started-at (:started-at lsp-process)
     :state (get-state lsp-process)
     :alive? (process-alive? lsp-process)
     :uptime-ms (when (:started-at lsp-process)
                  (- (System/currentTimeMillis) (:started-at lsp-process)))
     :pid (when-let [process (:process lsp-process)]
            (try (.pid process) (catch Exception _ nil)))}))

(defn get-input-stream
  "Get the input stream (for reading server responses)."
  [lsp-process]
  (:stdin lsp-process))

(defn get-output-stream
  "Get the output stream (for sending requests to server)."
  [lsp-process]
  (:stdout lsp-process))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Start a process
  (def proc (start-process :lsp/test ["echo" "hello"] {}))

  ;; Check state
  (get-state (:process proc))

  ;; Stop process
  (stop-process (:process proc))

  ;; Get info
  (process-info (:process proc))

  :leave-this-here)
