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
(ns ai.miniforge.event-stream.opsv-event-test
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.event-stream.interface.opsv :as opsv]
   [ai.miniforge.event-stream.opsv-constructor-cases :as cases]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.interface :as effect]
   [ai.miniforge.event-stream.opsv-fixtures :refer [disposition-payload]]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow-id
  #uuid "00000000-0000-0000-0000-000000000010")

(def ^{:stratum 0} evidence-id
  #uuid "00000000-0000-0000-0000-000000000011")

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-opsv-constructor-propagates-identity-without-overrides
  (let [stream (event-stream/create-event-stream {:sinks []})
        org-id #uuid "00000000-0000-0000-0000-000000000012"
        event (event-stream/drift-detected
               stream workflow-id evidence-id
               {:opsv/signal :latency
                :opsv/deviation {}
                :opsv/suggested-rerun? false
                :org/id org-id
                :event/type :wrong
                :opsv/evidence-bundle-id (random-uuid)})]
    (is (= org-id (:org/id event)))
    (is (= :opsv.drift/detected (:event/type event)))
    (is (= evidence-id (:opsv/evidence-bundle-id event)))))

(deftest ^{:stratum 1} disposition-rejects-unknown-state-and-substituted-decision-test
  (let [stream (event-stream/create-event-stream {:sinks []})
        event (event-stream/actuation-disposition stream workflow-id evidence-id disposition-payload)]
    (is (not (m/validate opsv/ActuationDisposition (assoc event :opsv/effect-state :invented))))
    (is (not (m/validate opsv/ActuationDisposition
                        (assoc-in event [:opsv/governed-effect :evidence/envelope-id] (random-uuid)))))))

(deftest ^{:stratum 1} disposition-requires-a-derived-allow-at-both-public-boundaries-test
  (let [stream (event-stream/create-event-stream {:sinks []})
        envelope (:opsv/decision-envelope disposition-payload)]
    (doseq [invalid [nil 42 (assoc disposition-payload :unexpected true)]]
      (is (anomaly/anomaly? (event-stream/actuation-disposition stream workflow-id evidence-id invalid))))
    (doseq [invalid [(assoc envelope :envelope/decision :deny)
                     (assoc envelope :envelope/decision :allow-with-obligations)
                     (assoc envelope :envelope/reasons [{:reason/code :reason/gate-check-failed
                                                        :reason/detail "denied"}])
                     (assoc envelope :envelope/obligations [{:obligation/type :obligation/audit-recorded}])]]
      (doseq [constructor [event-stream/actuation-disposition opsv/actuation-disposition]]
        (is (anomaly/anomaly? (constructor stream workflow-id evidence-id
                                          (assoc disposition-payload :opsv/decision-envelope invalid))))))
    (doseq [state effect/states]
      (is (m/validate opsv/ActuationDisposition
                      (event-stream/actuation-disposition stream workflow-id evidence-id
                                                          (assoc disposition-payload :opsv/effect-state state)))))))

(deftest ^{:stratum 1} invalid-disposition-does-not-consume-event-sequence-test
  (let [stream (event-stream/create-event-stream {:sinks []})
        construct #(event-stream/actuation-disposition stream workflow-id evidence-id %)
        before (construct disposition-payload)
        invalid (construct (assoc disposition-payload :opsv/effect-state :invented))
        after (construct disposition-payload)]
    (is (anomaly/anomaly? invalid))
    (is (= (inc (:event/sequence-number before)) (:event/sequence-number after)))))

(deftest ^{:stratum 1} disposition-preserves-identity-and-refuses-premature-observations-test
  (let [stream (event-stream/create-event-stream {:sinks []})
        identity {:org/id (random-uuid) :workspace/id (random-uuid)
                  :repo/id "miniforge-ai/miniforge" :auth/context {:actor :test}}
        payload (merge disposition-payload identity)
        event (event-stream/actuation-disposition stream workflow-id evidence-id payload)]
    (is (= identity (select-keys event (keys identity))))
    (is (m/validate opsv/ActuationDisposition event))
    (doseq [state [:proposed :committing]]
      (let [invalid (assoc payload :opsv/effect-state state :opsv/effect-observed {:pr/number 1})]
        (is (anomaly/anomaly? (event-stream/actuation-disposition stream workflow-id evidence-id invalid)))
        (is (not (m/validate opsv/ActuationDisposition (merge event invalid))))))))

(deftest ^{:stratum 1} test-all-opsv-constructors-emit-canonical-events
  (let [stream (event-stream/create-event-stream {:sinks []})]
    (doseq [[constructor schema event-type payload] cases/constructors]
      (let [event (constructor stream workflow-id evidence-id payload)]
        (is (= event-type (:event/type event)))
        (is (= workflow-id (:workflow/id event)))
        (is (= evidence-id (:opsv/evidence-bundle-id event)))
        (is (not-empty (:message event)))
        (is (m/validate schema event) (str event-type " validates"))))))
