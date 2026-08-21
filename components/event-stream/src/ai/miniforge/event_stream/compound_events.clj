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
(ns ai.miniforge.event-stream.compound-events
  "Event constructors that compose other constructors' vocabulary.

   These sit one stratum above the bus and the plain constructors in
   `core`: each derives its payload from helpers there rather than
   from the envelope alone. Keeping them here holds `core` inside the
   three-layer budget (SL003)."
  (:require
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} dependency-id-string
  [dependency-id]
  (if (keyword? dependency-id)
    (name dependency-id)
    (str dependency-id)))

(defn ^{:stratum 0} chain-started [stream chain-id step-count]
  (-> (core/chain-envelope stream :chain/started)
      (assoc :chain/id chain-id
             :chain/step-count step-count)))

(defn ^{:stratum 0} chain-step-started [stream chain-id step-id step-index workflow-id]
  (-> (core/chain-envelope stream :chain/step-started)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index
             :step/workflow-id workflow-id)))

(defn ^{:stratum 0} chain-step-completed [stream chain-id step-id step-index]
  (-> (core/chain-envelope stream :chain/step-completed)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index)))

(defn ^{:stratum 0} chain-step-failed [stream chain-id step-id step-index error & [{:keys [failure/class]}]]
  (-> (core/chain-envelope stream :chain/step-failed)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index
             :chain/error error)
      (cond-> class (assoc :failure/class class))))

(defn ^{:stratum 0} chain-completed [stream chain-id duration-ms step-count]
  (-> (core/chain-envelope stream :chain/completed)
      (assoc :chain/id chain-id
             :chain/duration-ms duration-ms
             :chain/step-count step-count)))

(defn ^{:stratum 0} chain-failed [stream chain-id step-id error & [{:keys [failure/class]}]]
  (-> (core/chain-envelope stream :chain/failed)
      (assoc :chain/id chain-id
             :chain/failed-step step-id
             :chain/error error)
      (cond-> class (assoc :failure/class class))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} dependency-event
  [stream event-type dependency previous-status message-key]
  (let [dependency-id (:dependency/id dependency)
        status (:dependency/status dependency)
        message (messages/t message-key
                            {:dependency-id (dependency-id-string dependency-id)
                             :status (name status)})]
    (-> (core/create-envelope stream event-type nil message)
        (merge dependency)
        (cond-> previous-status
          (assoc :dependency/previous-status previous-status)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} dependency-health-updated
  "Emit when a dependency health projection changes."
  [stream dependency & [previous-status]]
  (dependency-event stream
                    :dependency/health-updated
                    dependency
                    previous-status
                    :dependency/health-updated))

(defn ^{:stratum 2} dependency-recovered
  "Emit when a dependency returns to healthy status."
  [stream dependency & [previous-status]]
  (dependency-event stream
                    :dependency/recovered
                    dependency
                    previous-status
                    :dependency/recovered))
