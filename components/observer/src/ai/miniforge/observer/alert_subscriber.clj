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

(ns ai.miniforge.observer.alert-subscriber
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.observer.alerts :as alerts]))

(def ^:private watched-types
  #{:workflow/phase-heartbeat :agent/tool-call-started :tool/call-completed})

(defn rules-from-config [config]
  (vec (get-in config [:observability :alerts] [])))

(defn- alert-event [workflow-id alert]
  (assoc (alerts/alert-fired nil workflow-id alert) :workflow/id workflow-id))

(defn- watched-workflow-event? [workflow-id event]
  (and (contains? watched-types (:event/type event))
       (= workflow-id (:workflow/id event))))

(defn start-alert-subscriber!
  ([event-stream workflow-id rules]
   (start-alert-subscriber! event-stream workflow-id rules {}))
  ([event-stream workflow-id rules {:keys [subscriber-id alert-state]}]
   (if (empty? rules)
     {:noop? true}
     (let [id (or subscriber-id (keyword "observer.alerts" (str workflow-id)))
           state (or alert-state (alerts/create-alert-state))]
       (es/subscribe!
        event-stream
        id
        (fn [event]
          (doseq [alert (alerts/evaluate-rules rules event state)]
            (es/publish! event-stream (alert-event workflow-id alert))))
        (partial watched-workflow-event? workflow-id))
       {:subscriber-id id :alert-state state}))))

(defn stop-alert-subscriber! [event-stream handle]
  (when-let [id (:subscriber-id handle)]
    (es/unsubscribe! event-stream id))
  nil)
