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
(ns ai.miniforge.evidence-bundle.protocols.impl.semantic-validator
  "Compose resource analysis and shared semantic policy into protocol reports."
  (:require [ai.miniforge.evidence-bundle.semantic-analysis :as analysis]
            [ai.miniforge.evidence-bundle.semantic-report :as report]
            [ai.miniforge.evidence-bundle.semantic-rules :as rules]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} parse-terraform-change-line analysis/parse-terraform-change-line)

(def ^{:stratum 0} analyze-kubernetes-manifest-impl analysis/analyze-kubernetes-manifest-impl)

(def ^{:stratum 0} analyze-terraform-plan-impl analysis/analyze-terraform-plan-impl)

(def ^{:stratum 0} check-rule rules/check-count-rule)

(defn- ^{:stratum 0} total-changes [artifacts]
  (reduce (partial merge-with +) rules/empty-changes (map analysis/analyze-artifact artifacts)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} validate-intent-impl
  "Validate declared intent against analyzed resources without accumulating mutable state."
  [intent implementation-artifacts]
  (report/build (:intent/type intent) (total-changes implementation-artifacts) (java.time.Instant/now)))

(comment
  (validate-intent-impl {:intent/type :import} []))
