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
(ns ai.miniforge.event-stream.sink-durability
  "Which legacy-stream sinks persist events, and whether a publish was
   persisted. A legacy stream's sinks run in isolation and their failures
   are logged, not raised, so `publish!` returning the event shows only that
   the stream delivered it. A sink marked durable (the file sink) persists
   an event exactly when it returns without throwing; `publish!` records
   each sink's outcome on the event it returns.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} durable-key
  "Sink metadata key that marks a sink as one that persists events."
  :event-stream/durable-sink?)

(def ^{:stratum 0} outcomes-key
  "Metadata key under which `publish!` records the sink outcomes on the
   event it returns: a vector of {:sink/durable? bool :sink/persisted? bool}."
  :event-stream/sink-outcomes)

(defn ^{:stratum 0} not-persisted-reason
  "nil when `outcomes` show the event persisted: at least one durable sink,
   and every durable sink returned. Otherwise :no-durable-sink or
   :sink-write-failed."
  [outcomes]
  (let [durable (filter :sink/durable? outcomes)]
    (cond
      (empty? durable) :no-durable-sink
      (not-every? :sink/persisted? durable) :sink-write-failed
      :else nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} durable-sink
  "Mark `sink` as persisting each event it returns from without throwing."
  [sink]
  (vary-meta sink assoc durable-key true))

(defn ^{:stratum 1} durable-sink?
  [sink]
  (true? (get (meta sink) durable-key)))

(comment
  (not-persisted-reason [{:sink/durable? true :sink/persisted? false}]))
