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
   [ai.miniforge.decision-envelope.interface :as decision]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow-id
  #uuid "00000000-0000-0000-0000-000000000010")

(def ^{:stratum 0} evidence-id
  #uuid "00000000-0000-0000-0000-000000000011")

(def ^{:stratum 0} disposition-payload
  (let [envelope (decision/envelope [] [] {:pins/pack-revision "test" :pins/rule-ids []
                                         :pins/event-watermark 0})]
    {:opsv/governed-effect {:evidence/effect-id (random-uuid)
                            :evidence/grant-id (random-uuid) :evidence/envelope-id (:envelope/id envelope)}
     :opsv/effect-state :unknown-outcome :opsv/effect-observed {} :opsv/effect-failure nil
     :opsv/decision-envelope envelope}))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} constructor-cases
  [[event-stream/experiment-planned opsv/ExperimentPlanned
    :opsv.experiment/planned
    {:opsv/experiment-pack-hash "pack"
     :opsv/targets {:services ["catalog"] :environments ["staging"]}
     :opsv/risk-score {:score 0.1 :level :low :factors []}}]
   [event-stream/experiment-started opsv/ExperimentStarted
    :opsv.experiment/started
    {:opsv/experiment-pack-hash "pack"
     :opsv/environment-fingerprint
     {:cluster "test" :node-pools [] :image-digests {} :config-hash "cfg"}}]
   [event-stream/load-step opsv/LoadStep :opsv/load-step
    {:opsv/step-id "1" :opsv/intended-load {:rps 5}
     :opsv/observed-load {:rps 5}}]
   [event-stream/guardrail-abort opsv/GuardrailAbort :opsv.guardrail/abort
    {:opsv/trigger :errors :opsv/threshold {:max 1}
     :opsv/observed {:value 2} :opsv/rollback-action :restore}]
   [event-stream/convergence-iteration opsv/ConvergenceIteration
    :opsv.convergence/iteration
    {:opsv/iteration-id "1" :opsv/params {:replicas 2}
     :opsv/observed-metrics-summary {:latency 100}}]
   [event-stream/policy-proposed opsv/PolicyProposed :opsv.policy/proposed
    {:opsv/policy-hash "policy" :opsv/diff-artifact-refs []
     :opsv/confidence :high}]
   [event-stream/verification-result opsv/VerificationResult
    :opsv.verification/result
    {:opsv/passed? true :opsv/criteria-evaluation []
     :opsv/confidence :high :opsv/caveats []}]
   [event-stream/actuation-emitted opsv/ActuationEmitted
    :opsv.actuation/emitted
    {:opsv/requested-actuation-mode :recommend-only
     :opsv/effective-actuation-mode :recommend-only
     :opsv/governed-effects [] :opsv/pr-refs [] :opsv/apply-refs []}]
   [event-stream/actuation-disposition opsv/ActuationDisposition :opsv.actuation/disposition
    disposition-payload]
   [event-stream/drift-detected opsv/DriftDetected :opsv.drift/detected
    {:opsv/signal :latency :opsv/deviation {:ratio 1.1}
     :opsv/suggested-rerun? true}]])

(deftest ^{:stratum 1} test-opsv-constructor-propagates-identity-without-overrides
  (let [stream (event-stream/create-event-stream)
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
  (let [stream (event-stream/create-event-stream)
        event (event-stream/actuation-disposition stream workflow-id evidence-id disposition-payload)]
    (is (not (m/validate opsv/ActuationDisposition (assoc event :opsv/effect-state :invented))))
    (is (not (m/validate opsv/ActuationDisposition
                        (assoc-in event [:opsv/governed-effect :evidence/envelope-id] (random-uuid)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-all-opsv-constructors-emit-canonical-events
  (let [stream (event-stream/create-event-stream)]
    (doseq [[constructor schema event-type payload] constructor-cases]
      (let [event (constructor stream workflow-id evidence-id payload)]
        (is (= event-type (:event/type event)))
        (is (= workflow-id (:workflow/id event)))
        (is (= evidence-id (:opsv/evidence-bundle-id event)))
        (is (not-empty (:message event)))
        (is (m/validate schema event) (str event-type " validates"))))))
