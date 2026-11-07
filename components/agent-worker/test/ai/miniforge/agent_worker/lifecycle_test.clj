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
(ns ai.miniforge.agent-worker.lifecycle-test
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-worker.controller :as controller]
   [ai.miniforge.agent-worker.fake :as fake]
   [ai.miniforge.agent-worker.fixtures :as fx]
   [ai.miniforge.agent-worker.interface :as worker]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} prompt "Make the failing test pass.")

(def ^{:stratum 0} budget-usd "A budget under the ceiling below." 2)

(def ^{:stratum 0} ceiling-usd "A spend grant's cost ceiling." 5)

(def ^{:stratum 0} reported-tokens 1200)

(defn- ^{:stratum 0} fresh
  ([] (fresh {} {}))
  ([fake-opts constraints]
   (let [w (worker/fake-worker fake-opts)]
     (assoc (fx/world {:worker/fake w} constraints {}) :worker w))))

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(def ^{:stratum 0} fake-config (fx/config :worker/fake))

(defn- ^{:stratum 0} steer [w wc acting session]
  (worker/send-input! wc acting (:spend w) session "More."))

(defn- ^{:stratum 0} after-expiry [w] (.plusSeconds ^Instant (:now w) (* 2 fx/ttl-seconds)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} over-ceiling-usd (inc ceiling-usd))

(deftest ^{:stratum 1} test-identical-submission-reconciles-without-relaunching
  (let [w (fresh)
        session (fx/start! w fake-config 1 prompt)]
    (is (= session (fx/start! w fake-config 1 prompt)))
    (is (= 1 (fake/starts (:worker w))))
    (is (= (:lease/generation (:lease w)) (get-in session [:session/binding :worker/lease-generation])))
    (is (= :anomalies.agent-worker/conflicting-submission (reason (fx/start! w fake-config 1 "Something else."))))))

(deftest ^{:stratum 1} test-selected-worker-is-never-substituted
  (let [w (fresh {:fake/preflight-failures [:cli-missing]} {})]
    (is (= :anomalies.agent-worker/worker-unavailable (reason (fx/start! w (fx/config :worker/claude) 1 prompt))))
    (is (= :anomalies.agent-worker/worker-preflight-failed (reason (fx/start! w fake-config 1 prompt))))
    (is (zero? (fake/starts (:worker w))))))

(deftest ^{:stratum 1} test-stale-lease-stops-new-attempts-and-old-sessions
  (let [w (fresh)
        session (fx/start! w fake-config 1 prompt)
        _ (env/release! (:ec w) (:acting w) (:lease w))
        renewed (env/acquire! (:ec w) (:acting w)
                              (fx/issue (:acting w) :effect/provision (:provision-scope w) {} (:now w))
                              (:request w))
        stale-start (worker/start! (:wc w) (:acting w) (:spend w) (:environment/lease renewed)
                                   (fx/submission w fake-config 2 prompt))]
    (is (= :anomalies.agent-worker/stale-lease (reason stale-start)))
    (is (= :anomalies.agent-environment/stale-lease (reason (worker/inspect (:wc w) (:acting w) session))))))

(deftest ^{:stratum 1} test-unknown-usage-is-nil-never-zero
  (let [w (fresh)
        session (fx/start! w fake-config 1 prompt)]
    (is (= {:usage/status :usage/unknown :usage/input-tokens nil :usage/output-tokens nil :usage/cost-usd nil}
           (worker/usage (:wc w) (:acting w) session))))
  (let [w (fresh {:fake/usage {:usage/input-tokens reported-tokens}} {})
        session (fx/start! w fake-config 1 prompt)]
    (is (= {:usage/status :usage/partial :usage/input-tokens reported-tokens :usage/output-tokens nil
            :usage/cost-usd nil}
           (worker/usage (:wc w) (:acting w) session))))
  (let [w (fresh {:fake/usage (anomaly/anomaly :unavailable "usage unreadable" {})} {})]
    (is (= :unavailable (:anomaly/type (worker/usage (:wc w) (:acting w) (fx/start! w fake-config 1 prompt)))))))

(deftest ^{:stratum 1} test-steering-cancel-and-artifacts
  (let [w (fresh)
        session (fx/start! w fake-config 1 prompt)
        steered (worker/send-input! (:wc w) (:acting w) (:spend w) session "Also update the docs.")]
    (testing "given a worker that steers between turns → the next turn keeps the native session"
      (is (= 2 (:session/turn steered)))
      (is (= (:session/native-id session) (:session/native-id steered)))
      (is (= :anomalies.agent-worker/unknown-session (reason (worker/inspect (:wc w) (:acting w) session)))))
    (testing "given a cancel request → requested, and inspection reports it"
      (is (= {:cancel/requested? true} (worker/request-cancel! (:wc w) (:acting w) steered)))
      (is (= :state/cancelled (:status/state (worker/inspect (:wc w) (:acting w) steered)))))
    (testing "given the session's files → persisted through the environment store"
      (is (= 1 (count (worker/persist-artifacts! (:wc w) (:acting w) steered)))))))

(deftest ^{:stratum 1} test-worker-without-steering-or-cancellation-refuses-input-and-requests-nothing
  (let [w (fresh {:fake/capabilities (assoc fake/default-capabilities :capabilities/steering :steering/none
                                            :capabilities/cancellation :cancellation/none)} {})
        session (fx/start! w fake-config 1 prompt)]
    (is (= :anomalies.agent-worker/steering-unsupported (reason (steer w (:wc w) (:acting w) session))))
    (is (= {:cancel/requested? false} (worker/request-cancel! (:wc w) (:acting w) session)))
    (is (= :state/completed (:status/state (worker/inspect (:wc w) (:acting w) session))))))

(deftest ^{:stratum 1} test-a-retry-reconciles-without-preflight-and-says-when-still-starting
  (let [w (fresh)
        session (fx/start! w fake-config 1 prompt)
        failing (assoc-in (:wc w) [:worker/workers :worker/fake] (fake/create {:fake/preflight-failures [:cli-missing]}))]
    (is (= session (worker/start! failing (:acting w) (:spend w) (:lease w) (fx/submission w fake-config 1 prompt))))
    (controller/claim! (:wc w) (fx/submission w fake-config 2 prompt))
    (is (= :anomalies.agent-worker/start-in-flight (reason (fx/start! w fake-config 2 prompt))))))

(deftest ^{:stratum 1} test-a-next-turn-needs-the-principal-a-finished-turn-a-live-grant-and-the-reservation
  (let [w (fresh)
        session (fx/start! w fake-config 1 prompt)
        colleague (assoc (:acting w) :acting/principal-id (random-uuid))
        expired-grant (assoc (:wc w) :worker/clock (constantly (after-expiry w)))]
    (is (= :anomalies.agent-worker/forged-principal (reason (steer w (:wc w) colleague session))))
    (is (= :anomalies.agent-worker/grant-inactive (reason (steer w expired-grant (:acting w) session))))
    (is (true? (controller/reserve-turn! (:wc w) session)))
    (is (= :anomalies.agent-worker/turn-in-progress (reason (steer w (:wc w) (:acting w) session))))
    (is (= 1 (fake/starts (:worker w))))
    (swap! (:state (:provider w)) assoc-in [(:namespace w) :ready?] false)
    (is (= :anomalies.agent-worker/environment-not-ready (reason (steer w (:wc w) (:acting w) session))))
    (is (= :state/completed (:status/state (worker/inspect (:wc w) (:acting w) session)))))
  (let [w (fresh {:fake/final-state :state/unknown} {})
        session (fx/start! w fake-config 1 prompt)]
    (is (= :anomalies.agent-worker/turn-unsettled (reason (steer w (:wc w) (:acting w) session))))))

(deftest ^{:stratum 1} test-artifacts-are-collected-after-the-environment-expires
  (let [w (fresh)
        session (fx/start! w fake-config 1 prompt)
        expired (assoc (:wc w) :worker/environment (assoc (:ec w) :controller/clock (constantly (after-expiry w))))]
    (is (= :anomalies.agent-environment/environment-expired (reason (worker/inspect expired (:acting w) session))))
    (is (= 1 (count (worker/persist-artifacts! expired (:acting w) session))))))

(deftest ^{:stratum 1} test-another-tenant-cannot-use-the-session
  (let [w (fresh)
        session (fx/start! w fake-config 1 prompt)
        stranger (fx/acting-for "another-tenant" (:now w))]
    (is (= :anomalies.agent-worker/forged-tenant (reason (worker/inspect (:wc w) stranger session))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-spend-grant-bounds-the-attempt
  (testing "given a cost ceiling and no configured budget → refused as unbounded"
    (is (= :anomalies.agent-worker/budget-unbounded
           (reason (fx/start! (fresh {} {:constraint/max-cost-usd ceiling-usd}) fake-config 1 prompt)))))
  (testing "given a budget over the ceiling → refused"
    (is (= :anomalies.agent-worker/grant-exceeded
           (reason (fx/start! (fresh {} {:constraint/max-cost-usd ceiling-usd})
                              (fx/config :worker/fake :config/max-budget-usd over-ceiling-usd) 1 prompt)))))
  (testing "given a budget under the ceiling → started"
    (is (:session/binding (fx/start! (fresh {} {:constraint/max-cost-usd ceiling-usd})
                                     (fx/config :worker/fake :config/max-budget-usd budget-usd) 1 prompt))))
  (testing "given a worker that cannot observe spend → refused"
    (is (= :anomalies.agent-worker/budget-unenforceable
           (reason (fx/start! (fresh {:fake/capabilities (assoc fake/default-capabilities :capabilities/budget :budget/none)}
                                     {:constraint/max-cost-usd ceiling-usd})
                              (fx/config :worker/fake :config/max-budget-usd budget-usd) 1 prompt))))))
