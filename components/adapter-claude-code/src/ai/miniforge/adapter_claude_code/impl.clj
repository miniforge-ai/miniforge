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

(ns ai.miniforge.adapter-claude-code.impl
  "Implementation for the Claude Code control-plane adapter."
  (:require
   [ai.miniforge.adapter-claude-code.discovery :as discovery]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.control-plane.interface :as control-plane]
   [ai.miniforge.control-plane-adapter.interface :as proto]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Internal helpers

(def ^:const default-decisions-dir
  "Default directory where decisions are dropped for Claude Code agents."
  (str (config/miniforge-home) "/decisions"))

(defn- adapter-decisions-dir
  [{:keys [decisions-dir]}]
  (or decisions-dir
      default-decisions-dir))

(defn- ensure-decisions-dir!
  [config agent-id]
  (let [dir (io/file (adapter-decisions-dir config) (str agent-id))]
    (.mkdirs dir)
    dir))

(defn- pid-alive?
  [pid]
  (try
    (let [proc (.start (ProcessBuilder. ["kill" "-0" (str pid)]))]
      (.waitFor proc)
      (zero? (.exitValue proc)))
    (catch Exception _
      false)))

(defn- session-file-active-within-ms?
  [session-file threshold-ms]
  (and session-file
       (.exists (io/file session-file))
       (< (- (System/currentTimeMillis)
             (.lastModified (io/file session-file)))
          threshold-ms)))

(defn- poll-status
  [agent-record]
  (let [pid (get-in agent-record [:agent/metadata :pid])
        session-file (get-in agent-record [:agent/metadata :session-file])]
    (cond
      (and pid (pid-alive? pid))
      {:status (if (session-file-active-within-ms? session-file (* 60 1000))
                 :running
                 :idle)}

      (session-file-active-within-ms? session-file (* 5 60 1000))
      {:status :running}

      :else
      nil)))

(defn- deliver-decision!
  [config agent-record decision-resolution]
  (try
    (let [agent-id (:agent/id agent-record)
          decision-id (:decision/id decision-resolution)
          dir (ensure-decisions-dir! config agent-id)
          file (io/file dir (str decision-id ".edn"))]
      (spit file (pr-str decision-resolution))
      {:delivered? true})
    (catch Exception e
      {:delivered? false :error (ex-message e)})))

(defn- send-signal!
  [agent-record command]
  (let [pid (get-in agent-record [:agent/metadata :pid])
        signal-map {:pause "-TSTP" :resume "-CONT" :terminate "-TERM"}]
    (if pid
      (if-let [signal (get signal-map command)]
        (try
          (.start (ProcessBuilder. ["kill" signal (str pid)]))
          {:success? true}
          (catch Exception e
            {:success? false :error (ex-message e)}))
        {:success? false
         :error (control-plane/t :adapter/unknown-command {:command command})})
      {:success? false
       :error (control-plane/t :adapter/no-pid)})))

;------------------------------------------------------------------------------ Layer 1
;; Protocol implementation

(defrecord ClaudeCodeAdapter [config]
  proto/ControlPlaneAdapter

  (adapter-id [_] :claude-code)

  (discover-agents [_ adapter-config]
    (discovery/discover-sessions adapter-config))

  (poll-agent-status [_ agent-record]
    (poll-status agent-record))

  (deliver-decision [_ agent-record decision-resolution]
    (deliver-decision! config agent-record decision-resolution))

  (send-command [_ agent-record command]
    (send-signal! agent-record command)))

;------------------------------------------------------------------------------ Layer 0
;; Factory

(defn create-adapter
  [config]
  (->ClaudeCodeAdapter config))
