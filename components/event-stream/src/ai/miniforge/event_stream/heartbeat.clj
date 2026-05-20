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

(ns ai.miniforge.event-stream.heartbeat
  "Phase heartbeat scheduler."
  (:require
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.logging.interface :as log])
  (:import
   [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(def ^:const default-interval-ms
  "Default interval between heartbeat events: 30 seconds."
  30000)

(defn- daemon-thread-factory [phase-id]
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable (str "miniforge-phase-heartbeat-" (name phase-id)))
        (.setDaemon true)))))

(defn- resolve-interval-ms [opts]
  (let [interval-ms (or (:interval-ms opts) default-interval-ms)]
    (when-not (pos-int? interval-ms)
      (throw (ex-info "Heartbeat interval must be a positive integer"
                      {:interval-ms interval-ms})))
    interval-ms))

(defn- make-heartbeat-task
  "Return one scheduled heartbeat emission task."
  [event-stream workflow-id phase-id active-since seq-num last-tick]
  (fn []
    (try
      (let [now    (System/currentTimeMillis)
            gap-ms (- now @last-tick)
            seq-n  (swap! seq-num inc)
            event  (-> (core/phase-heartbeat
                         event-stream workflow-id phase-id
                         {:phase/active-since            active-since
                          :phase/events-emitted          seq-n
                          :phase/gap-since-last-event-ms gap-ms
                          :phase/last-event-at           (java.util.Date. ^long @last-tick)})
                       (assoc :heartbeat/phase-id              phase-id
                              :heartbeat/seq-in-phase           seq-n
                              :heartbeat/gap-since-last-event-ms gap-ms))]
        (when-not (:rejected? (core/publish! event-stream event))
          (reset! last-tick now)))
      (catch Exception e
        (log/warn (some-> event-stream deref :logger)
                  :event-stream
                  :heartbeat/emission-error
                  {:message "Phase heartbeat emission failed"
                   :data {:phase-id phase-id
                          :error (ex-message e)}})))))

(defn start-heartbeat!
  "Start :workflow/phase-heartbeat emission for phase-id.
   opts: {:interval-ms long}. Returns an opaque stop handle."
  ([event-stream workflow-id phase-id]
   (start-heartbeat! event-stream workflow-id phase-id {}))
  ([event-stream workflow-id phase-id opts]
   (let [interval-ms (resolve-interval-ms opts)
         seq-num     (atom 0)
         start-ms    (System/currentTimeMillis)
         active-since (java.util.Date. ^long start-ms)
         last-tick   (atom start-ms)
         executor    (Executors/newSingleThreadScheduledExecutor
                       (daemon-thread-factory phase-id))
         task        (make-heartbeat-task event-stream workflow-id phase-id
                                          active-since seq-num last-tick)]
     (.scheduleAtFixedRate ^ScheduledExecutorService executor
                           task
                           interval-ms
                           interval-ms
                           TimeUnit/MILLISECONDS)
     {:heartbeat/executor    executor
      :heartbeat/phase-id    phase-id
      :heartbeat/last-tick   last-tick
      :heartbeat/seq-num     seq-num})))

(defn stop-heartbeat!
  "Stop a heartbeat scheduler returned by start-heartbeat!. Nil-safe."
  [handle]
  (when-let [^ScheduledExecutorService executor (:heartbeat/executor handle)]
    (.shutdown executor)))
