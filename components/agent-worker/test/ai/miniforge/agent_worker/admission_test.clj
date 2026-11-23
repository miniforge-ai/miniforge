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
(ns ai.miniforge.agent-worker.admission-test
  (:require
   [ai.miniforge.agent-worker.admission :as admission]
   [ai.miniforge.agent-worker.session :as session]
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-11-06T00:00:00Z"))

(def ^{:stratum 0} ttl-seconds 600)

(def ^{:stratum 0} ceiling-usd 5)

(def ^{:stratum 0} acting
  {:acting/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
   :acting/principal-id #uuid "00000000-0000-0000-0000-0000000000a1"
   :acting/established-at "2026-11-06T00:00:00Z"})

(def ^{:stratum 0} config {:config/worker :worker/fake :config/workdir "/work"})

(def ^{:stratum 0} capabilities {:capabilities/budget :budget/observed})

(def ^{:stratum 0} lease
  {:lease/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
   :lease/run-id #uuid "00000000-0000-0000-0000-00000000000b"
   :lease/task-id #uuid "00000000-0000-0000-0000-00000000000c"
   :lease/environment-uid "uid-1"
   :lease/generation 2})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} spend
  [& {:keys [effect-class constraints] :or {effect-class :effect/spend constraints {}}}]
  (grant/issue {:principal (str (:acting/principal-id acting))
                :effect-class effect-class
                :scope {:worker/tenant-id (:acting/tenant-id acting) :worker/run-id (:lease/run-id lease)}
                :constraints constraints
                :expires-at (.plusSeconds now ttl-seconds)}
               now))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-binding-must-name-the-current-lease
  (let [bound (session/bind acting lease (spend) config 1)]
    (is (nil? (admission/binding-refusal acting lease bound)))
    (is (= :anomalies.agent-worker/stale-lease
           (admission/binding-refusal acting (update lease :lease/generation inc) bound)))
    (is (= :anomalies.agent-worker/binding-mismatch
           (admission/binding-refusal acting (assoc lease :lease/environment-uid "uid-2") bound)))
    (is (= :anomalies.agent-worker/forged-tenant
           (admission/binding-refusal (assoc acting :acting/tenant-id (random-uuid)) lease bound)))
    (is (= :anomalies.agent-worker/forged-principal
           (admission/binding-refusal acting lease (assoc bound :worker/principal-id (random-uuid)))))
    (is (nil? (admission/config-refusal bound config)))
    (is (= :anomalies.agent-worker/config-digest-mismatch
           (admission/config-refusal bound (assoc config :config/model "other-model"))))))

(deftest ^{:stratum 2} test-spend-grant-must-be-the-bound-grant
  (let [granted (spend)
        bound (session/bind acting lease granted config 1)]
    (is (nil? (admission/grant-refusal granted acting bound config now)))
    (is (= :anomalies.agent-worker/grant-not-bound (admission/grant-refusal (spend) acting bound config now)))
    (is (= :anomalies.agent-worker/grant-wrong-effect
           (admission/grant-refusal (spend :effect-class :effect/provision) acting bound config now)))
    (is (= :anomalies.agent-worker/grant-exceeded
           (let [zero-count (spend :constraints {:constraint/max-count 0})]
             (admission/grant-refusal zero-count acting (session/bind acting lease zero-count config 1) config now))))
    (is (= :anomalies.agent-worker/grant-inactive
           (admission/grant-refusal granted acting bound config (.plusSeconds now (inc ttl-seconds)))))))

(deftest ^{:stratum 2} test-a-budget-must-be-bounded-and-observable-and-a-token-ceiling-is-refused
  (let [ceilinged (spend :constraints {:constraint/max-cost-usd ceiling-usd})]
    (is (nil? (admission/budget-refusal (spend) config capabilities)))
    (is (= :anomalies.agent-worker/budget-unbounded (admission/budget-refusal ceilinged config capabilities)))
    (is (= :anomalies.agent-worker/budget-unenforceable
           (admission/budget-refusal ceilinged (assoc config :config/max-budget-usd 1) {:capabilities/budget :budget/none})))
    (is (= :anomalies.agent-worker/budget-unenforceable
           (admission/budget-refusal (spend) (assoc config :config/max-budget-usd 1) {:capabilities/budget :budget/none})))
    (is (= :anomalies.agent-worker/budget-unenforceable
           (admission/budget-refusal (spend :constraints {:constraint/max-tokens 0}) config capabilities)))))
