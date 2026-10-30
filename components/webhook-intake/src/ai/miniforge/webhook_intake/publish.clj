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
(ns ai.miniforge.webhook-intake.publish
  "Publishing one delivery's events to the event stream, resuming after
   the events a failed attempt already published."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.webhook-intake.deliveries :as deliveries]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} published?
  "True when a publish! result is the delivered event: not a structured
   rejection (e.g. a quiesced workflow) and not an anomaly."
  [result]
  (and (map? result)
       (not (:rejected? result))
       (not (anomaly/anomaly? result))))

(defn ^{:stratum 0} publish-one
  "publish! `event`, turning a thrown exception into an anomaly."
  [stream event]
  (try+
    (event-stream/publish! stream event)
    (catch Exception e
      (anomaly/anomaly :fault (str (ex-message e)) {}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-delivery!
  "Publish `events` of delivery `id` from index `start`, recording
   progress in `seen`. True once all are published (the delivery is
   :done); false at the first that is not (the delivery is :failed, and
   a redelivery resumes from that event)."
  [stream seen id events start]
  (loop [i start]
    (if (>= i (count events))
      (do (deliveries/record! seen id {:status :done :published i})
          true)
      (if (published? (publish-one stream (nth events i)))
        (do (deliveries/record! seen id {:published (inc i)})
            (recur (inc i)))
        (do (deliveries/record! seen id {:status :failed :published i})
            false)))))
