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

(ns ai.miniforge.phase.telemetry
  "Soft-dependency telemetry helpers for phase implementations."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Streaming callback construction

(defn create-streaming-callback
  "Create an event-stream backed streaming callback for a phase agent.

   Returns nil when the execution context has no event stream or the
   event-stream component is not available."
  [ctx agent-id]
  (when-let [stream (:event-stream ctx)]
    (when-let [create-cb (try
                           (requiring-resolve
                            'ai.miniforge.event-stream.interface/create-streaming-callback)
                           (catch Exception _ nil))]
      (create-cb stream
                 (:execution/id ctx)
                 agent-id
                 {:print? (not (:quiet ctx))
                  :quiet? (boolean (:quiet ctx))}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (create-streaming-callback {:execution/id (random-uuid)} :plan)
  :leave-this-here)
