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

(ns ai.miniforge.control-plane.heartbeat
  "Heartbeat watchdog for the control plane.

   Runs a background thread that periodically checks all registered
   agents for missed heartbeats. When an agent misses 3 consecutive
   heartbeat intervals, it transitions to :unreachable.

   Layer 0: Timeout detection
   Layer 1: Watchdog thread lifecycle"
  (:require
   [ai.miniforge.control-plane.registry :as registry]
   [ai.miniforge.control-plane.state-machine :as sm]))

;------------------------------------------------------------------------------ Layer 0
;; Timeout detection

(def ^:const missed-heartbeat-multiplier
  "Number of missed intervals before marking unreachable."
  3)

(def ^:const default-check-interval-ms
  "How often the watchdog checks for stale agents."
  10000)

(defn heartbeat-stale?
  "Check if an agent's heartbeat is stale (missed too many intervals).

   Arguments:
   - agent-record - Agent record map
   - now          - Current time (java.util.Date)

   Returns: true if agent has missed heartbeats."
  [agent-record now]
  (let [last-hb (:agent/last-heartbeat agent-record)
        interval (:agent/heartbeat-interval-ms agent-record 30000)
        threshold (* interval missed-heartbeat-multiplier)]
    (when last-hb
      (> (- (.getTime now) (.getTime last-hb)) threshold))))

(defn check-stale-agents
  "Check all agents for missed heartbeats and transition stale ones.

   Arguments:
   - registry - Agent registry atom

   Returns: Seq of agent IDs transitioned to :unreachable."
  [registry]
  (let [now (java.util.Date.)
        profile (sm/get-profile)
        agents (registry/list-agents registry)
        non-terminal (remove #(sm/terminal? profile (:agent/status %)) agents)]
    (reduce
     (fn [transitioned agent-record]
       (if (and (heartbeat-stale? agent-record now)
                (not= :unreachable (:agent/status agent-record))
                (sm/valid-transition? profile (:agent/status agent-record) :unreachable))
         (do
           (registry/update-agent! registry (:agent/id agent-record)
                                   {:agent/status :unreachable})
           (conj transitioned (:agent/id agent-record)))
         transitioned))
     []
     non-terminal)))

;------------------------------------------------------------------------------ Layer 1
;; Watchdog thread lifecycle

(defn start-watchdog
  "Start the heartbeat watchdog background thread.

   Arguments:
   - registry          - Agent registry atom
   - opts              - Optional map:
     - :check-interval-ms - How often to check (default 10000)
     - :on-unreachable    - Callback fn called with agent-id when marked unreachable

   Returns: Map with :future and :stop-fn.

   Example:
     (def wd (start-watchdog registry))
     ((:stop-fn wd)) ;; stop the watchdog"
  [registry & [opts]]
  (let [interval (get opts :check-interval-ms default-check-interval-ms)
        on-unreachable (get opts :on-unreachable (fn [_]))
        running (atom true)
        fut (future
              (while @running
                (try
                  (let [transitioned (check-stale-agents registry)]
                    (doseq [agent-id transitioned]
                      (on-unreachable agent-id)))
                  (catch Exception _e
                    nil))
                (Thread/sleep interval)))]
    {:future fut
     :stop-fn (fn []
                (reset! running false)
                (future-cancel fut))}))

(defn stop-watchdog
  "Stop a running heartbeat watchdog.

   Arguments:
   - watchdog - Map returned by start-watchdog."
  [watchdog]
  (when-let [stop (:stop-fn watchdog)]
    (stop)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def reg (registry/create-registry))
  (registry/register-agent! reg {:agent/vendor :test
                                  :agent/name "Test"
                                  :agent/heartbeat-interval-ms 1000})
  ;; Wait 4 seconds...
  (check-stale-agents reg)
  :end)
