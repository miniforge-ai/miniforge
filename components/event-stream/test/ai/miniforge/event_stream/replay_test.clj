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
(ns ai.miniforge.event-stream.replay-test
  "replay! delivers an event another process already stored without
   storing it again."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as events]
   [ai.miniforge.event-stream.publication-store :as store]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} stored-event
  [event-type wf-id]
  {:event/type event-type :event/id (str (random-uuid)) :workflow/id (str wf-id)})

(defn- ^{:stratum 0} collecting-stream
  "A legacy stream whose only sink collects into `stored`."
  [stored]
  (events/create-event-stream {:sinks [#(swap! stored conj %)]}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} replay-reaches-memory-and-subscribers-but-no-sink
  (let [stored   (atom [])
        notified (atom [])
        stream   (collecting-stream stored)
        event    (stored-event :workflow/started (random-uuid))]
    (events/subscribe! stream ::watch #(swap! notified conj %))
    (is (= event (events/replay! stream event)))
    (is (= [event] (events/get-events stream)))
    (is (= [event] @notified))
    (is (empty? @stored) "a replayed event is not stored again")))

(deftest ^{:stratum 1} publishes-derived-from-a-replayed-event-skip-the-sinks
  (let [stored (atom [])
        stream (collecting-stream stored)
        wf-id  (random-uuid)
        derive! (fn [event]
                  (when (= :workflow/started (:event/type event))
                    (events/publish! stream (stored-event :supervisory/workflow-upserted wf-id))))]
    (events/subscribe! stream ::derive derive!)
    (events/replay! stream (stored-event :workflow/started wf-id))
    (is (= [:workflow/started :supervisory/workflow-upserted]
           (mapv :event/type (events/get-events stream))))
    (is (empty? @stored) "the producing process stores its own derived event")
    (testing "a publish outside replay! still reaches the sinks"
      (events/publish! stream (stored-event :control-action/requested wf-id))
      (is (= [:control-action/requested] (mapv :event/type @stored))))))

(deftest ^{:stratum 1} a-current-stream-refuses-replay
  (let [stream (with-redefs [store/durable-store (constantly (store/volatile-store))]
                 (events/create-current-event-stream {:journal-directory "fixture"}))]
    (is (= :unsupported
           (:anomaly/type (events/replay! stream (stored-event :workflow/started (random-uuid))))))
    (is (anomaly/anomaly? (events/replay! stream (stored-event :workflow/started (random-uuid)))))))
