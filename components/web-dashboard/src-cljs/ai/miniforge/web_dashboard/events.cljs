;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.events
  "re-frame event handlers for web dashboard."
  (:require
   [re-frame.core :as rf]
   [ai.miniforge.web-dashboard.db :as db]))

;; Layer 0 - Database initialization

(rf/reg-event-db
 ::initialize-db
 (fn [_ _]
   db/default-db))

;; Layer 1 - WebSocket events

(rf/reg-event-db
 ::websocket-connected
 (fn [db [_ _url]]
   (assoc-in db [:websocket :status] :connected)))

(rf/reg-event-db
 ::websocket-disconnected
 (fn [db _]
   (assoc-in db [:websocket :status] :disconnected)))

(rf/reg-event-db
 ::websocket-error
 (fn [db [_ error]]
   (-> db
       (assoc-in [:websocket :status] :error)
       (assoc-in [:websocket :error] error))))

;; Layer 2 - Event stream events

(rf/reg-event-db
 ::event-received
 (fn [db [_ event]]
   (let [events (:events db)
         ;; Keep last 100 events
         updated-events (take 100 (cons event events))]
     (assoc db :events updated-events))))

(rf/reg-event-db
 ::workflow-event
 (fn [db [_ event]]
   ;; Update workflow state based on event
   (let [workflow-id (or (:workflow-id event)
                         (:workflow/id event))
         event-type (or (:event/type event)
                        (:type event))]
     (if workflow-id
       (update-in db [:workflows workflow-id]
                  (fn [workflow]
                    (merge workflow
                           {:id workflow-id
                            :last-event event
                            :last-event-type event-type
                            :updated-at (js/Date.)})))
       db))))

;; Layer 3 - UI events

(rf/reg-event-db
 ::set-current-view
 (fn [db [_ view-name]]
   (assoc db :current-view view-name)))

(rf/reg-event-db
 ::select-workflow
 (fn [db [_ workflow-id]]
   (assoc db :selected-workflow workflow-id)))
