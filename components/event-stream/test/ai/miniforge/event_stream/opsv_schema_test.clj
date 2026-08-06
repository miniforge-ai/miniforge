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
(ns ai.miniforge.event-stream.opsv-schema-test
  (:require
   [ai.miniforge.event-stream.schema.opsv :as opsv-schema]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} evidence-id
  #uuid "00000000-0000-0000-0000-000000000001")

(def ^{:stratum 0} event-cases
  [[opsv-schema/ExperimentPlanned :opsv.experiment/planned
    {:opsv/experiment-pack-hash "pack-hash"
     :opsv/targets {:services ["catalog"] :environments ["staging"]}
     :opsv/risk-score
     {:score 0.2 :level :low
      :factors [{:factor :blast-radius :input 1 :contribution 0.2
                 :rationale "One service"}]}}]
   [opsv-schema/ExperimentStarted :opsv.experiment/started
    {:opsv/experiment-pack-hash "pack-hash"
     :opsv/environment-fingerprint
     {:cluster "test" :node-pools ["workers"]
      :image-digests {"catalog" "sha256:abc"} :config-hash "config"}}]
   [opsv-schema/LoadStep :opsv/load-step
    {:opsv/step-id "step-1" :opsv/intended-load {:rps 100}
     :opsv/observed-load {:rps 98}}]
   [opsv-schema/GuardrailAbort :opsv.guardrail/abort
    {:opsv/trigger :error-rate :opsv/threshold {:max 0.05}
     :opsv/observed {:value 0.08} :opsv/rollback-action :restore}]
   [opsv-schema/ConvergenceIteration :opsv.convergence/iteration
    {:opsv/iteration-id "iteration-1" :opsv/params {:replicas 4}
     :opsv/observed-metrics-summary {:latency-p95 180}}]
   [opsv-schema/PolicyProposed :opsv.policy/proposed
    {:opsv/policy-hash "policy-hash"
     :opsv/diff-artifact-refs [#uuid "00000000-0000-0000-0000-000000000004"]
     :opsv/confidence :high}]
   [opsv-schema/VerificationResult :opsv.verification/result
    {:opsv/passed? true
     :opsv/criteria-evaluation
     [{:criterion/id "latency" :criterion/passed? true
       :criterion/observed 180 :criterion/expected 200
       :criterion/reason-code :within-threshold}]
     :opsv/confidence :high :opsv/caveats []}]
   [opsv-schema/ActuationEmitted :opsv.actuation/emitted
    {:opsv/requested-actuation-mode :pr-only
     :opsv/effective-actuation-mode :pr-only
     :opsv/governed-effects
     [{:evidence/intent-id #uuid "00000000-0000-0000-0000-000000000005"
       :evidence/oir-id #uuid "00000000-0000-0000-0000-000000000006"
       :evidence/capability-id "grant-1"}]
     :opsv/pr-refs ["https://example.test/pr/1"] :opsv/apply-refs []}]
   [opsv-schema/DriftDetected :opsv.drift/detected
    {:opsv/signal :latency :opsv/deviation {:ratio 1.2}
     :opsv/suggested-rerun? true}]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} event-base
  {:event/id #uuid "00000000-0000-0000-0000-000000000002"
   :event/timestamp #inst "2026-08-05T00:00:00.000Z"
   :event/version "1.0"
   :event/sequence-number 0
   :workflow/id #uuid "00000000-0000-0000-0000-000000000003"
   :opsv/evidence-bundle-id evidence-id
   :message "OPSV event"})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-all-canonical-opsv-event-schemas
  (doseq [[schema event-type payload] event-cases]
    (let [event (merge event-base payload {:event/type event-type})]
      (is (m/validate schema event) (str event-type " validates"))
      (is (not (m/validate schema
                           (dissoc event :opsv/evidence-bundle-id)))
          (str event-type " requires preallocated evidence id")))))
