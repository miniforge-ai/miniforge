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

(ns ai.miniforge.phase-software-factory.phase-terminal
  "Termination-reason derivation for phase-completed events.

   Inspects the phase result map and optional watchdog / hint state to produce
   the :phase/termination-reason keyword merged into the
   :workflow/phase-completed event payload.

   Layer 0 — pure function, no side effects, no external dependencies.")

(defn derive-termination-reason
  "Derive the :phase/termination-reason for a phase-completed event.

   Checks in priority order:
     1. Watchdog stall     → :agent-stalled  (+ :stall/gap-duration-ms when present)
     2. Stream-idle        → :stream-idle-timeout  (+ :phase/stream-idle-detected true)
     3. Curator rejection  → :curator-rejected
     4. Tool / infra error → :tool-error
     5. Default            → :normal

   Arguments:
     result         — phase result map (from [:phase :result] in context).
     watchdog-state — optional map of hints derived from the watchdog or
                      phase-local detection; may contain:
                        :stalled?              truthy when supervisor detected a hang
                        :stall/gap-duration-ms gap in ms since the last agent event
                        :stream-idle?          truthy when the LLM output stream went
                                               quiet before the phase wall-clock cap
                        :rate-limited?         truthy to force :tool-error classification

   Returns a map suitable for merging into a phase-completed event payload:

     {:phase/termination-reason :normal}
     {:phase/termination-reason :agent-stalled
      :stall/gap-duration-ms    <ms>}
     {:phase/termination-reason :stream-idle-timeout
      :phase/stream-idle-detected true}
     {:phase/termination-reason :curator-rejected}
     {:phase/termination-reason :tool-error}"
  ([result]
   (derive-termination-reason result nil))
  ([result watchdog-state]
   (let [error-code    (get-in result [:error :data :code])
         error-type    (get-in result [:error :data :type])
         ;; The standard adaptive-timeout taxonomy rides the timeout
         ;; envelope: [:error :data :timeout :type] (result-boundary
         ;; error-response shape) or [:timeout :type] (unwrapped). Read
         ;; both keyword paths directly — the full string-tolerant cascade
         ;; lives in agent result-boundary, but this ns is Layer-0
         ;; dependency-free and the in-process result maps here carry
         ;; keywords. :error-code stays as a compat arm.
         timeout-type  (or (get-in result [:error :data :timeout :type])
                           (get-in result [:timeout :type]))
         gap-ms        (get watchdog-state :stall/gap-duration-ms)
         stalled?      (or (boolean (:stalled? watchdog-state))
                           (and (number? gap-ms) (pos? gap-ms)))
         stream-idle?  (or (boolean (:stream-idle? watchdog-state))
                           (= :stream-idle timeout-type)
                           (= :stream-idle error-code))
         rate-limited? (boolean (:rate-limited? watchdog-state))]
     (cond
       ;; Watchdog stall takes precedence — explicit signal from the supervisor
       stalled?
       (cond-> {:phase/termination-reason :agent-stalled}
         gap-ms (assoc :stall/gap-duration-ms gap-ms))

       ;; Stream-idle: the LLM output stream went quiet before the phase cap.
       ;; Separate from :agent-stalled (no response at all) — here the stream
       ;; started but then went idle. Emits :phase/stream-idle-detected true as
       ;; a telemetry tag so consumers can route / alert on idle events.
       stream-idle?
       {:phase/termination-reason   :stream-idle-timeout
        :phase/stream-idle-detected true}

       ;; Curator or release executor found nothing to commit/ship
       (or (= :curator/no-files-written error-code)
           (= :release/zero-files error-type)
           (= :release/zero-files error-code))
       {:phase/termination-reason :curator-rejected}

       ;; Infrastructure / tool failure (rate-limit hint or explicit :tool-error code)
       (or rate-limited? (= :tool-error error-code))
       {:phase/termination-reason :tool-error}

       ;; Default — phase ran to completion (success or ordinary failure)
       :else
       {:phase/termination-reason :normal}))))
