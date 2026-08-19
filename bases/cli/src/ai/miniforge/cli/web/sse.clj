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
  "Server-Sent Events for workflow streaming. Stream/subscription registry
   bookkeeping (the `streams`/`subscriptions` atoms and their CRUD) lives in
   the sibling `ai.miniforge.cli.web.sse.registry` namespace (rule 210: the
   combined namespace measured 4 real layers, max 3); this namespace covers
   the httpkit channel wiring."
  (:require
   [cheshire.core :as json]
   [org.httpkit.server :as http]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.cli.web.response :as response]
   [ai.miniforge.cli.web.sse.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} on-open [workflow-id channel]
  (let [event-stream (registry/get-or-create-stream workflow-id)
        sub-id (random-uuid)]
    (swap! registry/subscriptions assoc-in [workflow-id channel] sub-id)
    (http/send! channel (response/sse-headers) false)
    (es/subscribe! event-stream sub-id
                   (fn [event]
                     (http/send! channel
                                 (str "event: " (name (:event/type event)) "\n"
                                      "data: " (json/generate-string event) "\n\n")
                                 false)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} handle-stream [workflow-id req]
  (http/as-channel req
    {:on-open (partial on-open workflow-id)
     :on-close (partial registry/on-close workflow-id)}))
