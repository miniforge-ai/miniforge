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

(ns ai.miniforge.adapter-claude-code.interface
  "Claude Code adapter for the control plane.

   Discovers local Claude Code sessions, polls their status,
   and delivers decisions via file-drop mechanism.

   Layer 0: Adapter creation
   Layer 1: Protocol implementation"
  (:require
   [ai.miniforge.adapter-claude-code.discovery :as discovery]
   [ai.miniforge.control-plane.messages :as messages]
   [ai.miniforge.control-plane-adapter.protocol :as proto]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Decision file-drop directory

(def ^:const decisions-dir
  "Directory where decisions are dropped for Claude Code agents."
  (str (System/getProperty "user.home") "/.miniforge/decisions"))

(defn- ensure-decisions-dir! [agent-id]
  (let [dir (io/file decisions-dir (str agent-id))]
    (.mkdirs dir)
    dir))

;------------------------------------------------------------------------------ Layer 1
;; Protocol implementation

(defrecord ClaudeCodeAdapter [config]
  proto/ControlPlaneAdapter

  (adapter-id [_] :claude-code)

  (discover-agents [_ config]
    (discovery/discover-sessions config))

  (poll-agent-status [_ agent-record]
    (let [pid (get-in agent-record [:agent/metadata :pid])
          session-file (get-in agent-record [:agent/metadata :session-file])]
      (cond
        ;; Check PID first — most reliable
        (and pid (try
                   (let [proc (.start (ProcessBuilder. ["kill" "-0" (str pid)]))]
                     (.waitFor proc)
                     (zero? (.exitValue proc)))
                   (catch Exception _ false)))
        (let [recent? (and session-file
                           (.exists (io/file session-file))
                           (< (- (System/currentTimeMillis)
                                  (.lastModified (io/file session-file)))
                              (* 60 1000)))]
          {:status (if recent? :running :idle)})

        ;; No PID or process dead — check log recency
        (and session-file
             (.exists (io/file session-file))
             (< (- (System/currentTimeMillis)
                    (.lastModified (io/file session-file)))
                (* 5 60 1000)))
        {:status :running}

        ;; Agent is gone
        :else nil)))

  (deliver-decision [_ agent-record decision-resolution]
    (try
      (let [agent-id (:agent/id agent-record)
            decision-id (:decision/id decision-resolution)
            dir (ensure-decisions-dir! agent-id)
            file (io/file dir (str decision-id ".edn"))]
        (spit file (pr-str decision-resolution))
        {:delivered? true})
      (catch Exception e
        {:delivered? false :error (ex-message e)})))

  (send-command [_ agent-record command]
    (let [pid (get-in agent-record [:agent/metadata :pid])
          signal-map {:pause "-TSTP" :resume "-CONT" :terminate "-TERM"}]
      (if pid
        (if-let [signal (get signal-map command)]
          (try
            (.start (ProcessBuilder. ["kill" signal (str pid)]))
            {:success? true}
            (catch Exception e
              {:success? false :error (ex-message e)}))
          {:success? false :error (messages/t :adapter/unknown-command {:command command})})
        {:success? false :error (messages/t :adapter/no-pid)}))))

;------------------------------------------------------------------------------ Layer 0
;; Factory

(defn create-adapter
  "Create a Claude Code adapter instance.

   Options:
   - :projects-dir - Override default Claude projects directory

   Example:
     (def adapter (create-adapter))"
  [& [config]]
  (->ClaudeCodeAdapter (or config {})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def adapter (create-adapter))
  (proto/adapter-id adapter)
  (proto/discover-agents adapter {})
  :end)
