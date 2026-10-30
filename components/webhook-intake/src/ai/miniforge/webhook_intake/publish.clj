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
   [ai.miniforge.webhook-intake.github :as github]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} publish-one
  "publish! `event`. :published when it returned the event; :refused when
   it returned a rejection (e.g. a quiesced workflow) or an anomaly, which
   it does before any sink or subscriber sees the event; :unknown when it
   threw, which can happen after subscribers already received it (a
   subscriber filter throwing, or logging after delivery)."
  [stream event]
  (try+
    (let [result (event-stream/publish! stream event)]
      (if (and (map? result)
               (not (:rejected? result))
               (not (anomaly/anomaly? result)))
        :published
        :refused))
    (catch Exception _
      :unknown)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-delivery!
  "Publish `events` of delivery `id` from index `start`, recording
   progress in `seen`. Returns :done once all are published; :failed at
   the first refused event (a redelivery resumes from it); :unknown at
   the first that threw (not retried: a subscriber may already have it)."
  [stream seen id events start]
  (loop [i start]
    (if (>= i (count events))
      (do (deliveries/record! seen id {:status :done :published i})
          :done)
      (let [outcome (publish-one stream (nth events i))]
        (if (= :published outcome)
          (do (deliveries/record! seen id {:published (inc i)})
              (recur (inc i)))
          (let [status (if (= :refused outcome) :failed :unknown)]
            (deliveries/record! seen id {:status status :published i})
            status))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} delivery-response
  "[status body] for a verified, parsed `delivery` (see
   github/delivery->events). Deliveries are told apart by
   `body-signature` (the X-Hub-Signature-256 value, an HMAC of the body),
   not by X-GitHub-Delivery, which is not signed: a captured delivery
   replayed under a fresh id is still a redelivery. A redelivery of a published delivery is 200;
   one still being published, or whose publish threw (its event may
   already have started a workflow), is 409; one after a refused publish
   resumes from the first unpublished event."
  [{:keys [stream seen]} delivery body-signature]
  (let [{:keys [claimed? status published]} (deliveries/claim! seen body-signature)
        events (github/delivery->events delivery)]
    (cond
      (= :done status) [200 {:duplicate true}]
      (= :unknown status) [409 {:error "publish-outcome-unknown"}]
      (not claimed?) [409 {:error "delivery-in-progress"}]
      :else (case (publish-delivery! stream seen body-signature events published)
              :done [202 {:events (mapv :event/type events)}]
              :failed [500 {:error "publish-failed"}]
              [500 {:error "publish-outcome-unknown"}]))))
