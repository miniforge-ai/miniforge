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
(ns ai.miniforge.event-stream.persisted-publication
  "Publish an event and return it only when it was persisted. A current
   stream returns its receipt after a durable journal commit. A legacy
   stream's `publish!` returns the event even when its file sink failed to
   write it, so here the event is returned only when every durable sink
   persisted it; otherwise the result is an :unavailable anomaly. Callers
   that must not proceed on an unrecorded fact (a gate decision) publish
   through this."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.current-publication :as current]
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.event-stream.sink-durability :as durability]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} not-persisted
  [reason event]
  (anomaly/anomaly :unavailable (messages/system :publication/not-persisted)
                   {:publication/reason reason
                    :event/id (:event/id event)
                    :event/type (:event/type event)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-persisted!
  "Publish `event` to `stream` and return the result only when it was
   persisted. A current stream's result passes through (its commit is the
   persistence). On a legacy stream an anomaly or a rejection passes
   through; an event passes when at least one durable sink exists and every
   durable sink wrote it, and otherwise becomes an :unavailable anomaly whose
   :publication/reason is :no-durable-sink or :sink-write-failed."
  [stream event]
  (let [result (core/publish! stream event)]
    (cond
      (current/active? stream) result
      (anomaly/any-anomaly? result) result
      (:rejected? result) result
      :else (if-let [reason (durability/not-persisted-reason
                             (get (meta result) durability/outcomes-key))]
              (not-persisted reason event)
              result))))

(comment
  (publish-persisted! (core/create-event-stream {:sinks []})
                      {:event/type :gate/decision :workflow/id (random-uuid)}))
