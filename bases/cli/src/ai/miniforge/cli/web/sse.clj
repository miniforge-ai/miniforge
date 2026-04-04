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

(ns ai.miniforge.cli.web.sse
  "Server-Sent Events for workflow streaming."
  (:require
   [cheshire.core :as json]
   [org.httpkit.server :as http]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.cli.web.response :as response]))

(def streams (atom {}))

(defn get-or-create-stream [workflow-id]
  (or (get @streams workflow-id)
      (let [stream (es/create-event-stream)]
        (swap! streams assoc workflow-id stream)
        stream)))

(defn on-open [workflow-id channel]
  (let [event-stream (get-or-create-stream workflow-id)
        sub-id (random-uuid)]
    (swap! streams assoc-in [workflow-id :subscribers channel] sub-id)
    (http/send! channel (response/sse-headers) false)
    (es/subscribe! event-stream sub-id
                   (fn [event]
                     (http/send! channel
                                 (str "event: " (name (:event/type event)) "\n"
                                      "data: " (json/generate-string event) "\n\n")
                                 false)))))

(defn on-close [workflow-id channel]
  (when-let [sub-id (get-in @streams [workflow-id :subscribers channel])]
    (when-let [event-stream (get @streams workflow-id)]
      (es/unsubscribe! event-stream sub-id))
    (swap! streams update-in [workflow-id :subscribers] dissoc channel)))

(defn handle-stream [workflow-id req]
  (http/as-channel req
    {:on-open (partial on-open workflow-id)
     :on-close (partial on-close workflow-id)}))

(defn register! [workflow-id event-stream]
  (swap! streams assoc workflow-id event-stream)
  workflow-id)

(defn unregister! [workflow-id]
  (swap! streams dissoc workflow-id)
  nil)

(defn get-stream [workflow-id]
  (get @streams workflow-id))
