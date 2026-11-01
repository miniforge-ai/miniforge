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
(ns ai.miniforge.workflow.durable-stream-support
  "A legacy event stream that persists, for project tests that run gated
   workflows. A gate decision must be persisted before its phase may advance,
   so a stream with no durable sink refuses every gated phase. This stream's
   file sink writes to a fresh temporary directory, never ~/.miniforge."
  (:require [ai.miniforge.event-stream.interface :as events])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} durable-event-stream
  "A legacy stream whose only sink is a file sink under a new temp directory."
  []
  (let [dir (str (Files/createTempDirectory "mf-durable-events-" (make-array FileAttribute 0)))]
    (events/create-event-stream
     {:config {:observability {:event-sinks [{:type :file :base-dir dir}]}}})))

(comment
  (durable-event-stream))
