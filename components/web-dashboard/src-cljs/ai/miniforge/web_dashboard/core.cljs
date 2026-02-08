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

(ns ai.miniforge.web-dashboard.core
  "Main entry point for web dashboard ClojureScript application."
  (:require
   [reagent.dom :as rdom]
   [re-frame.core :as rf]
   [ai.miniforge.web-dashboard.events :as events]
   [ai.miniforge.web-dashboard.views :as views]
   [ai.miniforge.web-dashboard.websocket :as ws]))

(defn mount-root
  "Mount the root component."
  []
  (rf/clear-subscription-cache!)
  (let [root-el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [views/main-panel] root-el)))

(defn init
  "Initialize the application."
  []
  (rf/dispatch-sync [::events/initialize-db])
  (ws/connect!)
  (mount-root))
