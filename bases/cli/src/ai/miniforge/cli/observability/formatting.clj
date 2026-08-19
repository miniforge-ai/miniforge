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
(ns ai.miniforge.cli.observability.formatting
  "ANSI color codes and entry-to-display-string formatting for log and
   event records. Split out of `ai.miniforge.cli.observability` (rule
   210: the combined namespace measured 7 real layers, max 3) — same
   approach as the policy-pack loader split, miniforge#1772, and
   detection split, miniforge#1761/#1773.

   Layer 0: ANSI color codes, timestamp formatting — pure, no same-file
     dependents
   Layer 1: colorize (over Layer 0)
   Layer 2: format-log-entry, format-event (over Layer 0 and Layer 1)"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;;------------------------------------------------------------------------------ ANSI Colors
(def ^{:stratum 0} ansi-codes
  {:reset "\u001b[0m"
   :bold "\u001b[1m"
   :cyan "\u001b[36m"
   :green "\u001b[32m"
   :yellow "\u001b[33m"
   :red "\u001b[31m"
   :gray "\u001b[90m"
   :blue "\u001b[34m"
   :magenta "\u001b[35m"})

(defn ^{:stratum 0} format-timestamp [inst]
  (when inst
    (let [formatter (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS")]
      (.format (java.time.LocalDateTime/ofInstant inst (java.time.ZoneId/systemDefault))
               formatter))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} colorize [color text]
  (str (get ansi-codes color "") text (:reset ansi-codes)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} format-log-entry
  "Format a log entry for display.

   Arguments:
     entry - Parsed log map

   Returns: Formatted string"
  [entry]
  (let [timestamp (format-timestamp (:timestamp entry))
        level (:level entry)
        level-color (case level
                      :error :red
                      :warn :yellow
                      :info :cyan
                      :debug :gray
                      :gray)
        message (:message entry)
        context (:context entry)]
    (str (colorize :gray timestamp)
         " "
         (colorize level-color (str/upper-case (name level)))
         " "
         message
         (when (seq context)
           (str " " (colorize :gray (pr-str context)))))))

;; Event Parsing
(defn ^{:stratum 2} format-event
  "Format an event for display.

   Arguments:
     event - Event map

   Returns: Formatted string"
  [event]
  (let [timestamp (format-timestamp (:event/timestamp event))
        event-type (:event/type event)
        type-color (case event-type
                     :workflow/started :green
                     :workflow/completed :green
                     :workflow/failed :red
                     :phase/started :cyan
                     :phase/completed :cyan
                     :agent/chunk :gray
                     :error :red
                     :blue)
        workflow-id (:workflow/id event)
        message (:message event)]
    (str (colorize :gray timestamp)
         " "
         (colorize type-color (str "[" (name event-type) "]"))
         " "
         (when workflow-id
           (str (colorize :magenta (str "wf-" (subs (str workflow-id) 0 8))) " "))
         (or message (pr-str (dissoc event :event/timestamp :event/type :workflow/id))))))
