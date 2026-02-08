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

(ns ai.miniforge.web-dashboard.subscriptions
  "re-frame subscriptions for web dashboard."
  (:require
   [re-frame.core :as rf]))

;; Layer 0 - Root subscriptions

(rf/reg-sub
 ::db
 (fn [db _]
   db))

;; Layer 1 - WebSocket subscriptions

(rf/reg-sub
 ::websocket-status
 :<- [::db]
 (fn [db _]
   (get-in db [:websocket :status])))

(rf/reg-sub
 ::websocket-connected?
 :<- [::websocket-status]
 (fn [status _]
   (= status :connected)))

;; Layer 2 - Event stream subscriptions

(rf/reg-sub
 ::events
 :<- [::db]
 (fn [db _]
   (:events db)))

(rf/reg-sub
 ::workflows
 :<- [::db]
 (fn [db _]
   (:workflows db)))

(rf/reg-sub
 ::workflow-list
 :<- [::workflows]
 (fn [workflows _]
   (vals workflows)))

(rf/reg-sub
 ::selected-workflow
 :<- [::db]
 (fn [db _]
   (:selected-workflow db)))

(rf/reg-sub
 ::selected-workflow-data
 :<- [::workflows]
 :<- [::selected-workflow]
 (fn [[workflows selected-id] _]
   (get workflows selected-id)))

;; Layer 3 - UI subscriptions

(rf/reg-sub
 ::current-view
 :<- [::db]
 (fn [db _]
   (:current-view db)))
