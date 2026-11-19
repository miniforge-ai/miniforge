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
(ns ai.miniforge.web-dashboard.archive-summary
  "Pure derivation of an archived workflow's lightweight summary from its
   first event and its newest events. Split out of
   `ai.miniforge.web-dashboard.archive` (rule 210).")

;------------------------------------------------------------------------------ Layer 0

;; Pure summary derivation — data in, data out
(defn ^{:stratum 0} extract-wf-id
  [event]
  (or (:workflow/id event) (:workflow-id event)))

(defn ^{:stratum 0} extract-wf-name
  [event wf-id]
  (or (get-in event [:workflow/spec :spec/title])
      (get-in event [:workflow/spec :title])
      (get-in event [:workflow/spec :name])
      (get-in event [:workflow-spec :title])
      (get-in event [:workflow-spec :name])
      (get-in event [:spec :title])
      (get-in event [:spec :name])
      (str "Workflow " (subs (str wf-id) 0 (min 8 (count (str wf-id)))))))

(defn ^{:stratum 0} extract-timestamp
  [event]
  (or (:event/timestamp event) (:timestamp event)))

(defn ^{:stratum 0} find-by-type
  "First event from coll whose :event/type is in type-set."
  [type-set events]
  (first (filter #(contains? type-set (:event/type %)) events)))

(defn ^{:stratum 0} derive-status
  [terminal-event tail-line-count]
  (cond
    (= :workflow/failed (:event/type terminal-event))
    :failed

    (= :workflow/cancelled (:event/type terminal-event))
    :cancelled

    (= :workflow/completed (:event/type terminal-event))
    (case (or (:workflow/status terminal-event) (:status terminal-event))
      (:failure :failed) :failed
      :cancelled :cancelled
      :completed)

    (<= tail-line-count 2)
    :stale

    :else :stale))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} derive-summary
  "Pure: first-event + tail-events + file metadata → summary map or nil."
  [first-event tail-events file-path file-size]
  (let [wf-id (extract-wf-id first-event)]
    (when wf-id
      (let [terminal    (find-by-type #{:workflow/completed :workflow/failed :workflow/cancelled}
                                     tail-events)
            phase-event (find-by-type #{:workflow/phase-started :workflow/phase-completed} tail-events)]
        {:id           wf-id
         :name         (extract-wf-name first-event wf-id)
         :status       (derive-status terminal (count tail-events))
         :phase        (or (:workflow/phase phase-event) (:phase phase-event)
                           (:workflow/phase first-event) (:phase first-event)
                           "unknown")
         :started-at   (extract-timestamp first-event)
         :completed-at (when terminal (extract-timestamp terminal))
         :file-path    file-path
         :file-size    file-size}))))

(comment ::archive-summary)
