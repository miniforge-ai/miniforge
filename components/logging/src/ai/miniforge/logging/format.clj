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

(def ^:private level-order
  {:trace 0 :debug 1 :info 2 :warn 3 :error 4 :fatal 5})

(defn level-enabled?
  "Check if a log level should be emitted given the configured minimum level."
  [configured-level entry-level]
  (let [configured-ord (get level-order configured-level 0)
        entry-ord (get level-order entry-level 0)]
    (>= entry-ord configured-ord)))

(defn format-edn
  "Format a log entry as an EDN string for output."
  [entry]
  (pr-str entry))

(defn format-human
  "Format a log entry as a human-readable string."
  [entry]
  (let [{:log/keys [timestamp level category event message]} entry]
    (str (when timestamp (.toInstant timestamp))
         " [" (name level) "] "
         (name category) "/" (name event)
         (when message (str " - " message)))))
