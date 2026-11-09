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
(ns ai.miniforge.agent-environment.schema-test
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} sample-hash (apply str (repeat 64 "0")))

(deftest ^{:stratum 0} test-command-names-its-executable-and-keeps-empty-arguments
  (is (m/validate env/Command {:exec/argv ["printf" "%s" "" "  "] :exec/timeout-ms 1}))
  (is (not (m/validate env/Command {:exec/argv ["" "x"] :exec/timeout-ms 1})))
  (is (not (m/validate env/Command {:exec/argv [] :exec/timeout-ms 1})))
  (is (not (m/validate env/Command {:exec/argv '("ls") :exec/timeout-ms 1}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} request
  [& {:as overrides}]
  (merge {:environment/contract-version env/contract-version
          :environment/tenant-id (random-uuid)
          :environment/principal-id (random-uuid)
          :environment/run-id (random-uuid)
          :environment/task-id (random-uuid)
          :environment/template-hash sample-hash
          :environment/policy-hash sample-hash
          :environment/isolation :isolation/dedicated-kernel
          :environment/limits {:limits/cpu-millicores 500 :limits/memory-mib 512 :limits/ephemeral-storage-mib 1024}
          :environment/expires-at (Instant/parse "2026-11-07T00:00:00Z")}
         overrides))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-request-is-a-closed-versioned-value
  (testing "given a complete request → valid"
    (is (m/validate env/Request (request))))
  (testing "given another contract revision, an extra key or an unknown isolation → invalid"
    (is (not (m/validate env/Request (request :environment/contract-version "agent-environment/v0"))))
    (is (not (m/validate env/Request (request :environment/extra true))))
    (is (not (m/validate env/Request (request :environment/isolation :isolation/none))))))
