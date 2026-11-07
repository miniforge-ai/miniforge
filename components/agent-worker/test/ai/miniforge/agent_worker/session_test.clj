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
(ns ai.miniforge.agent-worker.session-test
  (:require
   [ai.miniforge.agent-worker.interface :as worker]
   [ai.miniforge.agent-worker.session :as session]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} acting
  {:acting/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
   :acting/principal-id #uuid "00000000-0000-0000-0000-0000000000a1"
   :acting/established-at "2026-11-06T00:00:00Z"})

(def ^{:stratum 0} lease
  {:lease/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
   :lease/run-id #uuid "00000000-0000-0000-0000-00000000000b"
   :lease/task-id #uuid "00000000-0000-0000-0000-00000000000c"
   :lease/environment-uid "uid-1"
   :lease/generation 1})

(def ^{:stratum 0} spend
  {:grant/id #uuid "00000000-0000-0000-0000-0000000000f1"
   :grant/principal "principal"
   :grant/effect-class :effect/spend
   :grant/scope {}
   :grant/constraints {}
   :grant/parent-id nil
   :grant/delegable? false
   :grant/issued-at (Instant/parse "2026-11-06T00:00:00Z")
   :grant/expires-at (Instant/parse "2026-11-06T01:00:00Z")
   :grant/revoked-at nil
   :grant/revocation-reason nil})

(def ^{:stratum 0} config {:config/worker :worker/fake :config/workdir "/work"})

(def ^{:stratum 0} reported-tokens 42)

(deftest ^{:stratum 0} test-resubmission-decisions
  (let [submission {:submission/prompt "p"}]
    (is (= :new (:decision/kind (session/submission-decision nil submission))))
    (is (= :duplicate (:decision/kind (session/submission-decision {:record/submission submission} submission))))
    (is (= :anomalies.agent-worker/conflicting-submission
           (:decision/reason (session/submission-decision {:record/submission {:submission/prompt "q"}} submission))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-bind-produces-a-valid-binding
  (let [bound (worker/bind acting lease spend config 1)]
    (is (m/validate worker/Binding bound))
    (is (= (session/config-digest config) (:worker/config-digest bound)))
    (is (not= (:worker/config-digest bound)
              (:worker/config-digest (worker/bind acting lease spend (assoc config :config/model "m") 1))))
    (is (= :anomalies.agent-worker/invalid-input (:anomaly/subtype (worker/bind acting lease spend config 0))))
    (is (not (m/validate worker/Binding (update bound :worker/config-digest str "\n"))))))

(deftest ^{:stratum 1} test-usage-never-defaults-to-zero
  (testing "given nothing reported → unknown, every quantity nil"
    (is (= {:usage/status :usage/unknown :usage/input-tokens nil :usage/output-tokens nil :usage/cost-usd nil}
           (session/normalize-usage nil))))
  (testing "given a zero that was reported → kept, not confused with unknown"
    (is (= :usage/partial (:usage/status (session/normalize-usage {:usage/input-tokens 0}))))
    (is (= 0 (:usage/input-tokens (session/normalize-usage {:usage/input-tokens 0})))))
  (testing "given a status that contradicts its quantities → invalid Usage"
    (is (not (m/validate worker/Usage {:usage/status :usage/reported :usage/input-tokens nil
                                       :usage/output-tokens nil :usage/cost-usd nil})))
    (is (m/validate worker/Usage (session/normalize-usage {:usage/input-tokens 0}))))
  (testing "given every quantity → reported"
    (is (= :usage/reported (:usage/status (session/normalize-usage {:usage/input-tokens reported-tokens
                                                                    :usage/output-tokens reported-tokens
                                                                    :usage/cost-usd 0.1}))))))
