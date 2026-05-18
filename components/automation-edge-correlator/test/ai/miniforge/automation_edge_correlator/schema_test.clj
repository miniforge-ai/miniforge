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

(ns ai.miniforge.automation-edge-correlator.schema-test
  "Unit tests for AutomationEdge schema validation.

   Fixtures are obviously synthetic (random UUIDs, synthetic PR numbers).
   One fixture per representative status: :observed and :handled."
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [ai.miniforge.automation-edge-correlator.schema :as schema]))

;------------------------------------------------------------------------------ Helpers

(defn- validate
  "Returns true if `data` satisfies `AutomationEdge` schema."
  [data]
  (m/validate schema/AutomationEdge data))

(defn- explain
  "Returns malli explain map for debugging failures."
  [data]
  (m/explain schema/AutomationEdge data))

(defn- now [] (java.util.Date.))

;------------------------------------------------------------------------------ Fixtures

(def ^:private observed-edge
  "Synthetic :observed edge — trigger seen, no handler correlated yet."
  {:edge/id              (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
   :edge/trigger-event-id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000002")
   :edge/trigger-kind    :pr-merged
   :edge/status          :observed
   :edge/idempotency-key "00000000-0000-0000-0000-000000000002"
   :edge/occurred-at     (now)
   :edge/updated-at      (now)
   ;; Optional payload fields present on :observed
   :edge/affected-pr-ids [["miniforge-ai/miniforge" 999]]
   :edge/affected-agent-session-ids []})

(def ^:private handled-edge
  "Synthetic :handled edge — handler workflow correlated and completed."
  {:edge/id              (java.util.UUID/fromString "00000000-0000-0000-0000-000000000010")
   :edge/trigger-event-id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000011")
   :edge/trigger-kind    :workflow-completed
   :edge/status          :handled
   :edge/idempotency-key "00000000-0000-0000-0000-000000000011"
   :edge/occurred-at     (now)
   :edge/updated-at      (now)
   ;; Handler correlation fields populated on :handled
   :edge/handled-by-workflow-run-id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000012")
   :edge/affected-pr-ids []
   :edge/affected-agent-session-ids [(java.util.UUID/fromString "00000000-0000-0000-0000-000000000013")]})

;------------------------------------------------------------------------------ Tests

(deftest observed-edge-validates
  (testing ":observed edge fixture satisfies AutomationEdge schema"
    (is (validate observed-edge)
        (str "explain: " (explain observed-edge)))))

(deftest handled-edge-validates
  (testing ":handled edge fixture satisfies AutomationEdge schema"
    (is (validate handled-edge)
        (str "explain: " (explain handled-edge)))))

(deftest missing-required-field-fails
  (testing "edge without :edge/id fails validation"
    (is (false? (validate (dissoc observed-edge :edge/id))))))

(deftest invalid-status-fails
  (testing "edge with unknown :edge/status fails validation"
    (is (false? (validate (assoc observed-edge :edge/status :flying))))))

(deftest invalid-trigger-kind-fails
  (testing "edge with unknown :edge/trigger-kind fails validation"
    (is (false? (validate (assoc observed-edge :edge/trigger-kind :made-up))))))

(deftest open-map-allows-extra-keys
  (testing "extra keys pass through (open map per N5-delta-1 §12.4)"
    (is (validate (assoc observed-edge :some/future-key "value")))))
