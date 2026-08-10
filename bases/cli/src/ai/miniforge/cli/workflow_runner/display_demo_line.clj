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
(ns ai.miniforge.cli.workflow-runner.display-demo-line
  "Plain-text (ANSI-stripped) event lines for demo and test output."
  (:require
   [ai.miniforge.cli.workflow-runner.display-ansi :as ansi]
   [ai.miniforge.cli.workflow-runner.display-event-line :as event-line]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} demo-defaults
  "Fill in '?' defaults for nil event params so format-event-line produces
   visible placeholders instead of empty strings."
  [event]
  (let [evt (:event/type event)]
    (cond-> event
      (and (#{:workflow/phase-started :workflow/phase-completed} evt)
           (nil? (or (:workflow/phase event) (:phase event))))
      (assoc :workflow/phase "?")

      (and (#{:agent/started :agent/completed :agent/failed :agent/status} evt)
           (nil? (or (:agent/id event) (:agent event))))
      (assoc :agent/id "?")

      (and (#{:gate/started :gate/passed :gate/failed} evt)
           (nil? (or (:gate/id event) (:gate event))))
      (assoc :gate/id "?")

      (and (#{:tool/invoked :tool/completed} evt)
           (nil? (:tool/id event)))
      (assoc :tool/id "?")

      (and (= :workflow/milestone-reached evt)
           (nil? (:message event)))
      (assoc :message "?")

      (and (= :workflow/failed evt)
           (nil? (:workflow/failure-reason event)))
      (assoc :workflow/failure-reason "unknown"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} format-demo-line
  "Format a plain-text (no ANSI) progress line for demo/test output.
   Delegates to format-event-line with nil-defaulted params, then strips ANSI.
   Uses '?' for nil values and 'unknown' for missing failure reasons."
  [event]
  (let [evt (:event/type event)
        patched (demo-defaults event)
        base (event-line/format-event-line patched)]
    (when base
      (let [stripped (ansi/strip-ansi base)]
        (case evt
          ;; Append (?) when duration is missing
          :workflow/completed (if (nil? (:workflow/duration-ms event))
                                (str stripped " (?)")
                                stripped)
          :workflow/phase-completed (if (nil? (:phase/duration-ms event))
                                      (str stripped " (?)")
                                      stripped)
          stripped)))))
