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
(ns ai.miniforge.webhook-intake.deliveries
  "Delivery ids the intake has published, to drop GitHub redeliveries.
   `seen` is an atom of {:ids #{} :order []} holding the newest
   `recent-delivery-capacity` ids.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} recent-delivery-capacity
  "How many delivery ids are remembered to drop GitHub redeliveries."
  1024)

(defn ^{:stratum 0} new-seen
  "An empty record of seen delivery ids."
  []
  (atom {:ids #{} :order []}))

(defn ^{:stratum 0} forget-delivery!
  "Drop `delivery-id` from `seen`, so a redelivery after a failed publish
   is processed again."
  [seen delivery-id]
  (swap! seen update :ids disj delivery-id))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} remember-delivery!
  "Record `delivery-id` in `seen`. True when it was not there yet."
  [seen delivery-id]
  (let [[before _] (swap-vals! seen
                               (fn [{:keys [ids order] :as s}]
                                 (if (contains? ids delivery-id)
                                   s
                                   (let [order (conj order delivery-id)
                                         drop-n (max 0 (- (count order) recent-delivery-capacity))]
                                     {:ids (reduce disj (conj ids delivery-id) (take drop-n order))
                                      :order (vec (drop drop-n order))}))))]
    (not (contains? (:ids before) delivery-id))))
