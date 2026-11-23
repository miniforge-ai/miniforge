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
(ns ai.miniforge.phase-opsv.event-delivery
  "Validate, publish and correlate OPSV audit events without assuming success."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as events]
   [ai.miniforge.phase-opsv.event-admission :as admission]
   [ai.miniforge.phase-opsv.event-evidence :as event-evidence]
   [ai.miniforge.phase-opsv.event-replay :as replay]
   [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} evidence-id
  [ctx]
  (get-in ctx [:execution/input :opsv/evidence-bundle-id]))

(defn- ^{:stratum 0} publication-anomaly
  [stream event published]
  (let [rejected? (or (:rejected? published) (anomaly/any-anomaly? published))
        identity-keys [:event/id :event/type :workflow/id :opsv/evidence-bundle-id]]
    (cond
      (anomaly/anomaly? published) published
      (and (not rejected?) (= (:event/id event) (:event/id published))
           (or (not (events/current-event-stream? stream))
               (and (nat-int? (:event/sequence-number published))
                    (= (select-keys event identity-keys) (select-keys published identity-keys))))) nil
      :else (anomaly/anomaly
             (if rejected? :unavailable :conflict)
             (msg/ts :event/publication-failed)
             {:event/id (:event/id event)
              :event/type (:event/type event)
              :event-stream/result published}))))

(defn- ^{:stratum 0} evidence-anomaly
  [event assembly]
  (cond
    (anomaly/anomaly? assembly) assembly
    (or (anomaly/any-anomaly? assembly)
        (not (contains? (:opsv/event-refs assembly) (:event/id event))))
    (anomaly/anomaly :fault
                     (msg/ts :evidence/accumulation-failed)
                     {:event/id (:event/id event)
                      :event/type (:event/type event)
                      :evidence/result assembly})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-event!
  [ctx stream-value event]
  (let [published (replay/publish! ctx stream-value event)
        store (:opsv/evidence-assembly-store ctx)
        failure (publication-anomaly stream-value event published)]
    (cond
      failure failure
      store (evidence-anomaly published
                              (event-evidence/accumulate! store (evidence-id ctx) published)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit! [ctx stream-value event]
  (let [prepared (admission/prepare stream-value event)]
    (if (anomaly/any-anomaly? prepared) prepared
        (anomaly/let-ok [identified (replay/identify prepared)]
          (publish-event! ctx stream-value identified)))))
