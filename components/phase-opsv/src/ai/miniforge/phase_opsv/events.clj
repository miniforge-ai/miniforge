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
(ns ai.miniforge.phase-opsv.events
  "Publish projected N3 events at successful OPSV phase boundaries."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.phase-opsv.event-projection :as projection]
   [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} stream
  [ctx]
  (or (:event-stream ctx)
      (:execution/event-stream ctx)
      (get-in ctx [:execution/opts :event-stream])))

(defn- ^{:stratum 0} workflow-id
  [ctx]
  (or (:execution/id ctx) (:workflow/id ctx) (:workflow-id ctx)))

(defn- ^{:stratum 0} evidence-id
  [ctx]
  (get-in ctx [:execution/input :opsv/evidence-bundle-id]))

(defn- ^{:stratum 0} publication-anomaly
  [event published]
  (cond
    (anomaly/anomaly? published) published
    (anomaly/any-anomaly? published)
    (anomaly/anomaly :unavailable
                     (msg/ts :event/publication-failed)
                     {:event/id (:event/id event)
                      :event/type (:event/type event)
                      :event-stream/result published})
    (= (:event/id event) (:event/id published)) nil
    :else (anomaly/anomaly
           :conflict
           (msg/ts :event/publication-failed)
           {:event/id (:event/id event)
            :event/type (:event/type event)
            :event-stream/result published})))

(defn- ^{:stratum 0} evidence-anomaly
  [event assembly]
  (cond
    (anomaly/anomaly? assembly) assembly
    (anomaly/any-anomaly? assembly)
    (anomaly/anomaly :fault
                     (msg/ts :evidence/accumulation-failed)
                     {:event/id (:event/id event)
                      :event/type (:event/type event)
                      :evidence/result assembly})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-event!
  [ctx stream-value event]
  (let [published (event-stream/publish! stream-value event)
        store (:opsv/evidence-assembly-store ctx)
        failure (publication-anomaly event published)]
    (cond
      failure failure
      store (let [assembly (evidence/accumulate-opsv-evidence!
                            store (evidence-id ctx)
                            {:opsv/event-refs [(:event/id event)]})]
              (evidence-anomaly event assembly)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit-phase-events!
  [ctx phase-key output]
  (when-let [stream-value (stream ctx)]
    (let [events (projection/phase-events
                  stream-value (workflow-id ctx) (evidence-id ctx)
                  ctx phase-key output)]
      (reduce (fn [result event]
                (if (anomaly/anomaly? result)
                  (reduced result)
                  (publish-event! ctx stream-value event)))
              nil
              events))))
