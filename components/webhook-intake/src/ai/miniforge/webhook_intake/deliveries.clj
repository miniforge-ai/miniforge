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
  "Progress of each GitHub delivery the intake has taken, so a
   redelivery neither publishes an event twice nor is acknowledged before
   its events are out.

   `seen` is an atom of {:deliveries {id {:status :published}} :order [id]}
   holding the newest `recent-delivery-capacity` ids, oldest first.
   :status is :in-flight while a request publishes the delivery's events,
   :done once all are published, :failed after a publish was refused
   (nothing delivered, so a redelivery may retry), and :unknown after a
   publish threw (a subscriber may already have the event, so it is not
   retried);
   :published counts the events already published, in the order
   delivery->events returns them.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} recent-delivery-capacity
  "How many delivery ids are remembered to drop GitHub redeliveries."
  1024)

(defn ^{:stratum 0} new-seen
  "An empty record of deliveries."
  []
  (atom {:deliveries {} :order []}))

(defn ^{:stratum 0} record!
  "Merge `progress` ({:status :published}) into delivery `id`'s entry, if
   it is still remembered."
  [seen id progress]
  (swap! seen (fn [s]
                (if (contains? (:deliveries s) id)
                  (update-in s [:deliveries id] merge progress)
                  s))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} claim
  "[seen-state' claim] for claiming delivery `id` in `seen-state`. A new
   id or a :failed one is claimed, set :in-flight, and the claim carries
   how many of its events are already :published. An :in-flight or :done
   id is not claimed; the claim carries its :status. A new id evicts the
   oldest beyond `recent-delivery-capacity` that are not :in-flight, so a
   delivery still publishing is never claimed a second time."
  [{:keys [deliveries order] :as s} id]
  (let [{:keys [status published]} (get deliveries id)]
    (case status
      (:in-flight :done :unknown)
      [s {:claimed? false :status status}]

      :failed
      [(assoc-in s [:deliveries id :status] :in-flight)
       {:claimed? true :published published}]

      (let [evicted (->> order
                         (remove #(= :in-flight (get-in deliveries [% :status])))
                         (take (max 0 (- (inc (count order)) recent-delivery-capacity)))
                         set)]
        [{:deliveries (assoc (apply dissoc deliveries evicted)
                             id {:status :in-flight :published 0})
          :order (conj (vec (remove evicted order)) id)}
         {:claimed? true :published 0}]))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} claim!
  "Claim delivery `id` in `seen` atomically; returns the claim (see `claim`)."
  [seen id]
  (let [[before _] (swap-vals! seen #(first (claim % id)))]
    (second (claim before id))))
