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
(ns ai.miniforge.evidence-bundle.opsv-test-fixtures)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow-id
  #uuid "00000000-0000-0000-0000-000000000101")

(def ^{:stratum 0} event-ids
  [#uuid "00000000-0000-0000-0000-000000000102"
   #uuid "00000000-0000-0000-0000-000000000103"])

(def ^{:stratum 0} pack-artifact-id
  #uuid "00000000-0000-0000-0000-000000000104")

(def ^{:stratum 0} policy-artifact-id
  #uuid "00000000-0000-0000-0000-000000000105")

(def ^{:stratum 0} metric-query-id
  #uuid "00000000-0000-0000-0000-000000000106")

(def ^{:stratum 0} metric-snapshot-id
  #uuid "00000000-0000-0000-0000-000000000107")

(def ^{:stratum 0} diff-artifact-id
  #uuid "00000000-0000-0000-0000-000000000108")

(def ^{:stratum 0} intent-id
  #uuid "00000000-0000-0000-0000-000000000109")

(def ^{:stratum 0} oir-id
  #uuid "00000000-0000-0000-0000-000000000110")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} artifact-ids
  [pack-artifact-id policy-artifact-id metric-query-id metric-snapshot-id
   diff-artifact-id])

(def ^{:stratum 1} governed-effect
  {:evidence/intent-id intent-id
   :evidence/oir-id oir-id
   :evidence/capability-id "grant-1"})

(def ^{:stratum 1} base-bundle
  {:evidence-bundle/workflow-id workflow-id
   :evidence-bundle/created-at #inst "2026-08-05T00:00:00.000Z"
   :evidence-bundle/version "1.0.0"
   :evidence/intent {:intent/type :update
                     :intent/description "Tune service policy"
                     :intent/business-reason "Meet the latency objective"
                     :intent/constraints []
                     :intent/declared-at #inst "2026-08-05T00:00:00.000Z"}
   :evidence/policy-checks []
   :evidence/outcome {:outcome/success true}})

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} opsv-evidence
  {:opsv/experiment-pack-hash "sha256:pack"
   :opsv/experiment-pack-id "catalog-latency"
   :opsv/experiment-pack-artifact-id pack-artifact-id
   :opsv/environment-fingerprint
   {:cluster "staging" :node-pools ["workers"]
    :image-digests {"catalog" "sha256:image"} :config-hash "sha256:cfg"}
   :opsv/event-refs event-ids
   :opsv/artifact-refs artifact-ids
   :opsv/capability-refs ["grant-1"]
   :opsv/risk-score {:score 0.2 :level :low :factors []}
   :opsv/convergence-iterations 2
   :opsv/policy-proposals
   [{:policy-hash "sha256:policy" :confidence :high
     :scaling {:replicas 3} :resources {:cpu "500m"}
     :artifact-id policy-artifact-id}]
   :opsv/verification
   {:passed? true :criteria-evaluation [] :confidence :high :caveats []}
   :opsv/actuation
   {:requested-actuation-mode :apply-allowed
    :effective-actuation-mode :apply-allowed
    :governed-effects [governed-effect]
    :pr-refs [] :apply-refs ["deployment/catalog"]
    :postcondition-artifact-refs []
    :rollback {:status :not-triggered :artifact-refs []}}
   :opsv/metric-query-artifact-refs [metric-query-id]
   :opsv/metric-snapshot-artifact-refs [metric-snapshot-id]
   :opsv/diff-artifact-refs [diff-artifact-id]})
