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
(ns ai.miniforge.logging.format
  "Pure log level and formatting helpers shared by logger core and sinks.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private level-order
  {:trace 0 :debug 1 :info 2 :warn 3 :error 4 :fatal 5})

(defn ^{:stratum 0} format-edn
  "Format a log entry as an EDN string for output."
  [entry]
  (pr-str entry))

(defn ^{:stratum 0} format-human
  "Format a log entry as a human-readable string. A :data map rides on
   the same line as EDN: an event whose whole point is its payload
   (:implementer/prompt-sections, :policy/budget-exceeded) is otherwise
   just a name in the console and file sinks, and the trap bench read
   two series of logs that recorded the name and nothing else."
  [entry]
  (let [{:log/keys [timestamp level category event message]} entry
        data (get entry :data)]
    (str (when timestamp (.toInstant timestamp))
         " [" (name level) "] "
         (name category) "/" (name event)
         (when message (str " - " message))
         (when (seq data) (str " " (pr-str data))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} level-enabled?
  "Check if a log level should be emitted given the configured minimum level."
  [configured-level entry-level]
  (let [configured-ord (get level-order configured-level 0)
        entry-ord (get level-order entry-level 0)]
    (>= entry-ord configured-ord)))
