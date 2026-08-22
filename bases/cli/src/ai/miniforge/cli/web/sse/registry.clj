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
(ns ai.miniforge.cli.web.sse.registry
  "Stream/subscription registry for SSE workflow streaming. Split out of
   `ai.miniforge.cli.web.sse` (rule 210: the combined namespace measured 4
   real layers, max 3); this namespace owns the `streams`/`subscriptions`
   atoms and their CRUD. See `ai.miniforge.cli.web.sse` for the httpkit
   channel wiring (`on-open`/`handle-stream`) that reads these atoms."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} streams (atom {}))

;; `streams` values are event-stream atoms (`es/create-event-stream`), not
;; maps — assoc-in/update-in on a `streams` entry would try to `assoc`
;; onto that atom directly and throw `ClassCastException`. Per-channel
;; subscription ids are tracked separately here instead:
;; {workflow-id {channel sub-id}}.
(def ^{:stratum 0} subscriptions (atom {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} get-or-create-stream [workflow-id]
  (or (get @streams workflow-id)
      (let [stream (es/create-event-stream)]
        (supervisory/attach! stream)
        ;; N15-6: route routing-causality through the witness surface.
        (correlator/attach! stream)
        (swap! streams assoc workflow-id stream)
        stream)))

(defn ^{:stratum 1} subscribe! [workflow-id channel sub-id]
  (swap! subscriptions assoc-in [workflow-id channel] sub-id)
  sub-id)

(defn ^{:stratum 1} on-close [workflow-id channel]
  (when-let [sub-id (get-in @subscriptions [workflow-id channel])]
    (when-let [event-stream (get @streams workflow-id)]
      (es/unsubscribe! event-stream sub-id))
    (swap! subscriptions
           (fn [m]
             (let [remaining (dissoc (get m workflow-id) channel)]
               (if (empty? remaining)
                 (dissoc m workflow-id)
                 (assoc m workflow-id remaining)))))))

(defn ^{:stratum 1} register! [workflow-id event-stream]
  (swap! streams assoc workflow-id event-stream)
  workflow-id)

(defn ^{:stratum 1} unregister! [workflow-id]
  (when-let [event-stream (get @streams workflow-id)]
    (doseq [[_channel sub-id] (get @subscriptions workflow-id)]
      (es/unsubscribe! event-stream sub-id)))
  (swap! streams dissoc workflow-id)
  (swap! subscriptions dissoc workflow-id)
  nil)

(defn ^{:stratum 1} get-stream [workflow-id]
  (get @streams workflow-id))
