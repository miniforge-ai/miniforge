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

(ns ai.miniforge.event-stream.interface.callbacks
  "Streaming callback helpers for the event-stream public API."
  (:require
   [ai.miniforge.event-stream.interface.events :as events]
   [ai.miniforge.event-stream.interface.stream :as stream]))

;------------------------------------------------------------------------------ Layer 0
;; Convenience callbacks

(defn create-streaming-callback
  [stream-atom workflow-id agent-id & [opts]]
  (let [{:keys [print? quiet?]} opts]
    (fn [{:keys [delta done? tool-use heartbeat]}]
      (cond
        tool-use
        (when stream-atom
          (stream/publish! stream-atom
                           (events/agent-status stream-atom workflow-id agent-id
                                                :tool-calling "Agent calling tool")))

        heartbeat
        nil

        :else
        (do
          (when (and print? (not quiet?) delta (not (empty? delta)))
            (print delta)
            (flush))
          (when stream-atom
            (stream/publish! stream-atom
                             (events/agent-chunk stream-atom workflow-id agent-id
                                                 (or delta "") done?))))))))
