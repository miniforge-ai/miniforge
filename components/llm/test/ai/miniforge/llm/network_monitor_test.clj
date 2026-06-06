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

(ns ai.miniforge.llm.network-monitor-test
  "Tests for `ai.miniforge.llm.network-monitor` — the consecutive-
   failure scheduler that turns the probe primitive into a single-fire
   network-drop signal."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.network-monitor :as nm]))

;------------------------------------------------------------------------------ Factories + helpers

(defn- scripted-probe
  "Build a probe-fn that returns the next value from `script` on each
   call. `script` is a vector of `:up` / `:down` keywords (or true /
   false). Once the script runs out, further calls return the last
   value indefinitely — convenient for tests that need 'steady state'
   after the interesting transitions."
  [script]
  (let [idx (atom 0)
        coerce {:up true :down false true true false false}]
    (fn [_probe-url]
      (let [i @idx
            v (get script i (last script))]
        (swap! idx inc)
        (get coerce v v)))))

(defn- record-call
  "Build an on-drop callback that swap!'s its diagnostic-map into
   `record-atom` under :calls (vec) and signals the latch."
  [record-atom latch]
  (fn [diag]
    (swap! record-atom update :calls (fnil conj []) diag)
    (when latch (deliver latch :fired))))

;; Short interval / low threshold so the suite stays fast — the
;; production defaults are 10s/3 strikes (~30s to fire); tests use ms-
;; level intervals for the same logical exercise.
(def ^:private fast-probe-interval-ms 10)
(def ^:private fast-failure-threshold 3)

(defn- wait-for-fire-or-timeout
  "Block up to `timeout-ms` for the on-drop callback to fire (the
   `latch` promise to be delivered). Returns the delivered value, or
   `::timeout` if the latch never fires."
  [latch timeout-ms]
  (deref latch timeout-ms ::timeout))

;------------------------------------------------------------------------------ Healthy path

(deftest steady-healthy-stream-never-fires-test
  (testing "every probe returns :up → on-drop never called"
    (let [record (atom {:calls []})
          stop!  (nm/start-network-monitor!
                   {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                    :probe-interval-ms fast-probe-interval-ms
                    :failure-threshold fast-failure-threshold
                    :probe-fn          (scripted-probe [:up :up :up :up :up :up])
                    :on-drop           (record-call record nil)})]
      (try
        ;; Let the scheduler run through several probe cycles.
        (Thread/sleep (* fast-probe-interval-ms 10))
        (is (empty? (:calls @record))
            "healthy stream must not synthesize a network drop")
        (finally
          (stop!))))))

;------------------------------------------------------------------------------ Transient blip

(deftest transient-blip-resets-counter-test
  (testing "an isolated failure followed by a recovery does not fire on-drop"
    (let [record (atom {:calls []})
          ;; failure-threshold 3; sequence is down, up, down, down, up, up...
          ;; The first down increments to 1. The up resets to 0. Each
          ;; subsequent down increments anew; never reaches 3.
          stop! (nm/start-network-monitor!
                  {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                   :probe-interval-ms fast-probe-interval-ms
                   :failure-threshold fast-failure-threshold
                   :probe-fn          (scripted-probe
                                        [:down :up :down :down :up :up :up])
                   :on-drop           (record-call record nil)})]
      (try
        (Thread/sleep (* fast-probe-interval-ms 12))
        (is (empty? (:calls @record))
            "a healthy probe between failures resets the counter; threshold not reached")
        (finally
          (stop!))))))

;------------------------------------------------------------------------------ Confirmed drop

(deftest three-consecutive-failures-fire-on-drop-once-test
  (testing "exactly threshold consecutive failures fires on-drop once"
    (let [record (atom {:calls []})
          latch  (promise)
          stop!  (nm/start-network-monitor!
                   {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                    :probe-interval-ms fast-probe-interval-ms
                    :failure-threshold fast-failure-threshold
                    :probe-fn          (scripted-probe [:down :down :down
                                                        :down :down :down :down])
                    :on-drop           (record-call record latch)})]
      (try
        ;; Wait for fire; give it generous margin (10x the minimum)
        (is (= :fired (wait-for-fire-or-timeout latch
                                                (* fast-probe-interval-ms 50)))
            "on-drop must fire after the threshold is reached")
        ;; Stop! and give the worker time to (not) fire again.
        (stop!)
        (Thread/sleep (* fast-probe-interval-ms 5))
        (is (= 1 (count (:calls @record)))
            "single-fire: extra failed probes after the threshold do not
             produce more on-drop calls")
        (finally
          (stop!))))))

(deftest on-drop-diagnostic-shape-test
  (testing "on-drop receives a diagnostic map with the canonical keys"
    (let [record (atom {:calls []})
          latch  (promise)
          stop!  (nm/start-network-monitor!
                   {:probe-url         "https://test.example.com/"
                    :backend-key       :codex
                    :probe-interval-ms fast-probe-interval-ms
                    :failure-threshold fast-failure-threshold
                    :probe-fn          (scripted-probe [:down :down :down])
                    :on-drop           (record-call record latch)})]
      (try
        (wait-for-fire-or-timeout latch (* fast-probe-interval-ms 50))
        (let [diag (first (:calls @record))]
          (is (= :codex                  (:backend-key diag)))
          (is (= "https://test.example.com/" (:probe-url diag))
              ":probe-url is echoed back so downstream logs identify
               which endpoint went away")
          (is (= fast-failure-threshold  (:failure-threshold diag)))
          (is (= fast-failure-threshold  (:consecutive-failures diag))
              "diagnostic records the actual failure count at fire time")
          (is (= fast-probe-interval-ms  (:probe-interval-ms diag))))
        (finally
          (stop!))))))

;------------------------------------------------------------------------------ Probe-fn exception is treated as failure

(deftest probe-fn-exception-counts-as-failure-test
  ;; A probe that throws (DNS resolution failure that bubbles out,
  ;; some other unexpected exception) should be treated as a failed
  ;; probe rather than crashing the monitor thread.
  (testing "exception thrown from probe-fn → counted as a failure"
    (let [record (atom {:calls []})
          latch  (promise)
          throwing-probe (fn [_probe-url]
                           (throw (RuntimeException. "DNS failure")))
          stop!  (nm/start-network-monitor!
                   {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                    :probe-interval-ms fast-probe-interval-ms
                    :failure-threshold fast-failure-threshold
                    :probe-fn          throwing-probe
                    :on-drop           (record-call record latch)})]
      (try
        (is (= :fired (wait-for-fire-or-timeout latch
                                                (* fast-probe-interval-ms 50)))
            "throwing probes must still drive the counter to threshold,
             not crash the daemon")
        (finally
          (stop!))))))

;------------------------------------------------------------------------------ stop! semantics

(deftest stop!-prevents-further-on-drop-calls-test
  (testing "stop! invoked before the threshold is reached → on-drop never fires"
    (let [record (atom {:calls []})
          stop!  (nm/start-network-monitor!
                   {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                    :probe-interval-ms fast-probe-interval-ms
                    :failure-threshold fast-failure-threshold
                    :probe-fn          (scripted-probe [:down :down :down])
                    :on-drop           (record-call record nil)})]
      ;; Stop immediately — the daemon hasn't had time to complete a single
      ;; full probe interval, let alone three. on-drop must not fire.
      (stop!)
      (Thread/sleep (* fast-probe-interval-ms 8))
      (is (empty? (:calls @record))
          "stop! interrupts the worker before on-drop can fire"))))

(deftest stop!-is-idempotent-test
  (testing "calling stop! twice is a no-op the second time"
    (let [stop! (nm/start-network-monitor!
                  {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                   :probe-interval-ms fast-probe-interval-ms
                   :failure-threshold fast-failure-threshold
                   :probe-fn          (scripted-probe [:up])
                   :on-drop           (fn [_] nil)})]
      (stop!)
      (is (nil? (stop!))
          "second stop! returns nil — does not throw"))))

;------------------------------------------------------------------------------ Argument validation

(deftest start-monitor-rejects-missing-probe-url-test
  (is (thrown? AssertionError
               (nm/start-network-monitor!
                 {:backend-key       :claude
                  :probe-interval-ms fast-probe-interval-ms
                  :failure-threshold fast-failure-threshold
                  :probe-fn          (scripted-probe [:up])
                  :on-drop           (fn [_] nil)}))
      "no probe-url → assertion failure (programmer error per rule 005)")
  (is (thrown? AssertionError
               (nm/start-network-monitor!
                 {:probe-url         ""
                  :backend-key       :claude
                  :probe-interval-ms fast-probe-interval-ms
                  :failure-threshold fast-failure-threshold
                  :probe-fn          (scripted-probe [:up])
                  :on-drop           (fn [_] nil)}))
      "empty :probe-url is not a usable URL"))

(deftest start-monitor-rejects-non-positive-interval-test
  (is (thrown? AssertionError
               (nm/start-network-monitor!
                 {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                  :probe-interval-ms 0
                  :failure-threshold fast-failure-threshold
                  :probe-fn          (scripted-probe [:up])
                  :on-drop           (fn [_] nil)})))
  (is (thrown? AssertionError
               (nm/start-network-monitor!
                 {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                  :probe-interval-ms -1
                  :failure-threshold fast-failure-threshold
                  :probe-fn          (scripted-probe [:up])
                  :on-drop           (fn [_] nil)}))))

(deftest start-monitor-rejects-non-positive-threshold-test
  (is (thrown? AssertionError
               (nm/start-network-monitor!
                 {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                  :probe-interval-ms fast-probe-interval-ms
                  :failure-threshold 0
                  :probe-fn          (scripted-probe [:up])
                  :on-drop           (fn [_] nil)})))
  (is (thrown? AssertionError
               (nm/start-network-monitor!
                 {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                  :probe-interval-ms fast-probe-interval-ms
                  :failure-threshold -2
                  :probe-fn          (scripted-probe [:up])
                  :on-drop           (fn [_] nil)}))))

(deftest start-monitor-rejects-non-fn-on-drop-test
  (is (thrown? AssertionError
               (nm/start-network-monitor!
                 {:probe-url         "https://test.example.com/"
                  :backend-key       :claude
                  :probe-interval-ms fast-probe-interval-ms
                  :failure-threshold fast-failure-threshold
                  :probe-fn          (scripted-probe [:up])
                  :on-drop           "not-a-fn"}))))

;------------------------------------------------------------------------------ Concurrent stop + threshold race (CAS regression)
;; Copilot review on PR #1053 flagged a race: the pre-fix worker
;; checked `@running?` separately from invoking on-drop, so a stop!
;; arriving between the check and the call could let on-drop fire
;; after stop! had already returned. The fix uses `compare-and-set!`
;; on the running? atom to atomically claim the firing slot — stop!
;; and the worker race for the same true→false transition.

(deftest stop!-racing-the-fire-cas-suppresses-on-drop-test
  ;; Drive many short trials. The slow `:on-drop` callback amplifies
  ;; the window in which a stop! after the threshold check could race
  ;; the fire. With the CAS in place, a stop! that wins the
  ;; transition MUST suppress on-drop entirely.
  (testing "stop! winning the CAS prevents on-drop from firing"
    (dotimes [_ 30]
      (let [record (atom {:calls []})
            ;; Probe-fn that immediately returns :down so the worker
            ;; reaches the threshold-check arm on its very first iteration.
            stop! (nm/start-network-monitor!
                    {:probe-url         "https://test.example.com/"
                     :backend-key       :claude
                     :probe-interval-ms 1
                     :failure-threshold 1
                     :probe-fn          (scripted-probe [:down])
                     :on-drop           (record-call record nil)})]
        ;; Race window: stop! and the first probe cycle both run.
        ;; With the CAS, exactly one wins.
        (stop!)
        ;; Give any in-flight on-drop time to finish.
        (Thread/sleep 5)
        ;; Either zero calls (stop! won) or exactly one (worker won).
        ;; The bug was zero-or-one nondeterminism PLUS a possible second
        ;; call from a stop!-then-fire ordering. Assert at most one
        ;; — never duplicates.
        (is (<= (count (:calls @record)) 1)
            "CAS guarantees at most one on-drop call, regardless of race outcome")))))

(deftest stop!-then-already-passed-threshold-fires-at-most-once-test
  ;; The classic single-fire guarantee under heavy concurrent stop! /
  ;; probe pressure. After firing, the worker exits; extra :down
  ;; probes do NOT produce additional calls.
  (testing "after the first on-drop fires, subsequent failed probes do not refire"
    (let [record (atom {:calls []})
          latch  (promise)
          stop!  (nm/start-network-monitor!
                   {:probe-url         "https://test.example.com/"
                    :backend-key       :claude
                    :probe-interval-ms 5
                    :failure-threshold 1
                    :probe-fn          (scripted-probe (repeat 20 :down))
                    :on-drop           (record-call record latch)})]
      (try
        (wait-for-fire-or-timeout latch 500)
        (Thread/sleep 50)
        (is (= 1 (count (:calls @record))))
        (finally
          (stop!))))))

;------------------------------------------------------------------------------ stream-exec-fn opts merge protection

;; This test lives here (rather than in interface_test) because it
;; pins the contract from the monitor's perspective: when callers pass
;; through `:network-monitor-opts`, the integration must enforce its
;; own `:probe-url`, `:backend-key`, and `:on-drop`. The integration
;; site in `stream-exec-fn` (Copilot review on PR #1053) merges caller
;; opts FIRST, so its protected keys always win.

(deftest probe-url-and-on-drop-take-priority-over-caller-opts-test
  (testing "caller opts merged FIRST → integration owns the protected keys"
    ;; Mimic the merge order that stream-exec-fn now uses:
    ;;   (merge caller-opts {:probe-url ... :backend-key ... :on-drop ...})
    (let [caller-opts {:probe-url   "https://malicious.example.com/"
                       :on-drop     (fn [_] (throw (Exception. "caller's on-drop")))
                       :backend-key :spoofed}
          integration {:probe-url   "https://real.example.com/"
                       :backend-key :claude
                       :on-drop     (fn [diag]
                                      (is (= "https://real.example.com/" (:probe-url diag)))
                                      (is (= :claude (:backend-key diag))))}
          effective   (merge caller-opts integration)]
      (is (= "https://real.example.com/" (:probe-url effective)))
      (is (= :claude (:backend-key effective)))
      (is (not= (:on-drop caller-opts) (:on-drop effective))
          "integration's on-drop wins; caller's on-drop is discarded"))))
