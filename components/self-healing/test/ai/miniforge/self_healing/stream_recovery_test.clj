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

(ns ai.miniforge.self-healing.stream-recovery-test
  "Tests for resume-on-kill / stall-recovery logic."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.self-healing.stream-recovery :as sut]
   [ai.miniforge.self-healing.backend-health :as health]))

;;------------------------------------------------------------------------------ helpers

(def ^:private base-config
  {:backend-switch-cooldown-ms 1800000
   :backend-health-threshold   0.90})

(defn- make-ctx
  "Build a minimal recovery context map."
  [backend session-id hang-count-val allowed-failover]
  {:phase-id                  :implement
   :backend                   backend
   :session-id                session-id
   :hang-count                (atom hang-count-val)
   :config                    base-config
   :allowed-failover-backends allowed-failover})

;;------------------------------------------------------------------------------ resume-flag-for

(deftest resume-flag-for-known-backends
  (testing "well-known backends return their documented flag"
    (is (= "--resume"   (sut/resume-flag-for :anthropic)))
    (is (= "--resume"   (sut/resume-flag-for :openai)))
    (is (= "--resume"   (sut/resume-flag-for :codex)))
    (is (= "--continue" (sut/resume-flag-for :ollama)))
    (is (= "--resume"   (sut/resume-flag-for :google)))))

(deftest resume-flag-for-unknown-backend
  (testing "unknown backend defaults to --resume"
    (is (= "--resume" (sut/resume-flag-for :unknown-backend-xyz)))))

;;------------------------------------------------------------------------------ build-resume-command

(deftest build-resume-command-basic
  (testing "produces [binary flag session-id]"
    (is (= ["anthropic" "--resume" "sess-abc"]
           (sut/build-resume-command :anthropic "sess-abc")))))

(deftest build-resume-command-with-extra-args
  (testing "appends extra args after session-id"
    (is (= ["openai" "--resume" "s123" "--timeout" "90"]
           (sut/build-resume-command :openai "s123" ["--timeout" "90"])))))

(deftest build-resume-command-ollama-uses-continue
  (testing "ollama uses --continue flag"
    (is (= ["ollama" "--continue" "s999"]
           (sut/build-resume-command :ollama "s999")))))

(deftest build-resume-command-string-backend
  (testing "string backend coerced to keyword correctly"
    (is (= ["codex" "--resume" "sid"]
           (sut/build-resume-command "codex" "sid")))))

;;------------------------------------------------------------------------------ evaluate-stall-recovery – :resume path

(deftest evaluate-stall-recovery-first-hang-returns-resume
  (testing "hang-count = 1 → :resume action with same session and backend"
    (let [ctx    (make-ctx :anthropic "sess-111" 1 [:openai :codex])
          result (sut/evaluate-stall-recovery ctx)]
      (is (= :resume      (:action result)))
      (is (= "sess-111"   (:session-id result)))
      (is (= :anthropic   (:backend result))))))

(deftest evaluate-stall-recovery-zero-hang-count-returns-resume
  (testing "hang-count = 0 (degenerate) → :resume action (safe fallback)"
    (let [ctx    (make-ctx :openai "sess-000" 0 [:anthropic])
          result (sut/evaluate-stall-recovery ctx)]
      (is (= :resume (:action result)))
      (is (= :openai (:backend result))))))

;;------------------------------------------------------------------------------ evaluate-stall-recovery – :failover path

(deftest evaluate-stall-recovery-second-hang-failover-happy-path
  (testing "hang-count = 2 with healthy failover backends → :failover"
    (with-redefs [health/load-health         (constantly
                                              {:backends      {}
                                               :fallback-order [:anthropic :openai :codex]
                                               :switch-cooldowns {}})
                  health/record-backend-call! (fn [_b _s] nil)
                  health/in-cooldown?         (fn [_b _ms] false)
                  health/get-backend-success-rate (fn [_b] nil) ; no data → eligible
                  health/trigger-backend-switch!  (fn [_f _t _ms] nil)]
      (let [ctx    (make-ctx :anthropic "sess-222" 2 [:openai :codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :failover (:action result)))
        ;; :openai is first eligible from fallback-order that is in allowed-set
        (is (= :openai (:new-backend result)))))))

(deftest evaluate-stall-recovery-third-hang-also-failover
  (testing "hang-count = 3 also triggers :failover path"
    (with-redefs [health/load-health         (constantly
                                              {:backends       {}
                                               :fallback-order [:anthropic :openai :codex]
                                               :switch-cooldowns {}})
                  health/record-backend-call! (fn [_b _s] nil)
                  health/in-cooldown?         (fn [_b _ms] false)
                  health/get-backend-success-rate (fn [_b] nil)
                  health/trigger-backend-switch!  (fn [_f _t _ms] nil)]
      (let [ctx    (make-ctx :anthropic "sess-333" 3 [:codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :failover (:action result)))
        (is (= :codex (:new-backend result)))))))

(deftest evaluate-stall-recovery-records-failure-on-second-hang
  (testing "hang-count >= 2 calls record-backend-call! with success?=false"
    (let [recorded (atom nil)]
      (with-redefs [health/load-health         (constantly
                                                {:backends       {}
                                                 :fallback-order [:anthropic :openai]
                                                 :switch-cooldowns {}})
                    health/record-backend-call! (fn [b s]
                                                  (reset! recorded {:backend b :success? s}))
                    health/in-cooldown?         (fn [_b _ms] false)
                    health/get-backend-success-rate (fn [_b] nil)
                    health/trigger-backend-switch!  (fn [_f _t _ms] nil)]
        (sut/evaluate-stall-recovery (make-ctx :anthropic "sess-fail" 2 [:openai]))
        (is (= {:backend :anthropic :success? false} @recorded))))))

(deftest evaluate-stall-recovery-calls-trigger-switch-on-failover
  (testing "hang-count >= 2 with candidate calls trigger-backend-switch!"
    (let [switch-calls (atom [])]
      (with-redefs [health/load-health         (constantly
                                                {:backends       {}
                                                 :fallback-order [:anthropic :openai :codex]
                                                 :switch-cooldowns {}})
                    health/record-backend-call! (fn [_b _s] nil)
                    health/in-cooldown?         (fn [_b _ms] false)
                    health/get-backend-success-rate (fn [_b] nil)
                    health/trigger-backend-switch!  (fn [f t ms]
                                                      (swap! switch-calls conj {:from f :to t :ms ms}))]
        (sut/evaluate-stall-recovery (make-ctx :anthropic "sess-sw" 2 [:openai]))
        (is (= 1 (count @switch-calls)))
        (is (= :anthropic (get-in @switch-calls [0 :from])))
        (is (= :openai    (get-in @switch-calls [0 :to])))))))

;;------------------------------------------------------------------------------ evaluate-stall-recovery – :abort path

(deftest evaluate-stall-recovery-no-backends-returns-abort
  (testing "hang-count >= 2 with no allowed failover → :abort"
    (with-redefs [health/load-health         (constantly
                                              {:backends       {}
                                               :fallback-order [:anthropic :openai]
                                               :switch-cooldowns {}})
                  health/record-backend-call! (fn [_b _s] nil)
                  health/in-cooldown?         (fn [_b _ms] false)
                  health/get-backend-success-rate (fn [_b] nil)
                  health/trigger-backend-switch!  (fn [_f _t _ms] nil)]
      (let [ctx    (make-ctx :anthropic "sess-abort" 2 []) ; empty allowed set
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :abort (:action result)))
        (is (string? (:reason result)))))))

(deftest evaluate-stall-recovery-all-backends-in-cooldown-returns-abort
  (testing "hang-count >= 2 but all allowed backends in cooldown → :abort"
    (with-redefs [health/load-health         (constantly
                                              {:backends       {}
                                               :fallback-order [:anthropic :openai :codex]
                                               :switch-cooldowns {}})
                  health/record-backend-call! (fn [_b _s] nil)
                  health/in-cooldown?         (fn [_b _ms] true) ; everything in cooldown
                  health/get-backend-success-rate (fn [_b] nil)
                  health/trigger-backend-switch!  (fn [_f _t _ms] nil)]
      (let [ctx    (make-ctx :anthropic "sess-cd" 2 [:openai :codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :abort (:action result)))))))

(deftest evaluate-stall-recovery-unhealthy-backends-skipped
  (testing "backends below threshold are skipped; abort if none survive"
    (with-redefs [health/load-health         (constantly
                                              {:backends       {}
                                               :fallback-order [:anthropic :openai :codex]
                                               :switch-cooldowns {}})
                  health/record-backend-call! (fn [_b _s] nil)
                  health/in-cooldown?         (fn [_b _ms] false)
                  ;; Both candidates have low success rate
                  health/get-backend-success-rate (fn [_b] 0.50)
                  health/trigger-backend-switch!  (fn [_f _t _ms] nil)]
      (let [ctx    (make-ctx :anthropic "sess-uh" 2 [:openai :codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :abort (:action result)))))))

(deftest evaluate-stall-recovery-respects-allowed-set-not-global-order
  (testing "only backends in allowed-failover-backends are considered, even if fallback-order has more"
    (with-redefs [health/load-health         (constantly
                                              {:backends       {}
                                               :fallback-order [:anthropic :openai :codex :ollama]
                                               :switch-cooldowns {}})
                  health/record-backend-call! (fn [_b _s] nil)
                  health/in-cooldown?         (fn [_b _ms] false)
                  health/get-backend-success-rate (fn [_b] nil)
                  health/trigger-backend-switch!  (fn [_f _t _ms] nil)]
      ;; Only :ollama allowed; :openai and :codex are in fallback-order but not allowed
      (let [ctx    (make-ctx :anthropic "sess-a" 2 [:ollama])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :failover (:action result)))
        (is (= :ollama (:new-backend result)))))))

(deftest evaluate-stall-recovery-uses-cooldown-from-config
  (testing "custom cooldown-ms from :config is forwarded to health checks"
    (let [cooldown-seen (atom nil)]
      (with-redefs [health/load-health         (constantly
                                                {:backends       {}
                                                 :fallback-order [:anthropic :openai]
                                                 :switch-cooldowns {}})
                    health/record-backend-call! (fn [_b _s] nil)
                    health/in-cooldown?         (fn [_b ms]
                                                  (reset! cooldown-seen ms)
                                                  false)
                    health/get-backend-success-rate (fn [_b] nil)
                    health/trigger-backend-switch!  (fn [_f _t _ms] nil)]
        (sut/evaluate-stall-recovery
         {:phase-id                  :plan
          :backend                   :anthropic
          :session-id                "s"
          :hang-count                (atom 2)
          :config                    {:backend-switch-cooldown-ms 300000
                                      :backend-health-threshold   0.80}
          :allowed-failover-backends [:openai]})
        (is (= 300000 @cooldown-seen))))))

;;------------------------------------------------------------------------------ execute-resume!

(deftest execute-resume-returns-expected-shape
  (testing "execute-resume! returns map with :process, :backend, :session-id, :command"
    (let [fake-proc (reify java.lang.Process)]
      (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                    (fn [_cmd] fake-proc)]
        (let [result (sut/execute-resume! :anthropic "sess-xyz")]
          (is (= fake-proc    (:process result)))
          (is (= :anthropic   (:backend result)))
          (is (= "sess-xyz"   (:session-id result)))
          (is (= ["anthropic" "--resume" "sess-xyz"] (:command result))))))))

(deftest execute-resume-with-extra-args
  (testing "extra-args are appended to the command"
    (let [launched (atom nil)]
      (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                    (fn [cmd] (reset! launched cmd) nil)]
        (sut/execute-resume! :openai "s42" ["--timeout" "120"])
        (is (= ["openai" "--resume" "s42" "--timeout" "120"] @launched))))))

(deftest execute-resume-ollama-uses-continue-flag
  (testing "ollama subprocess uses --continue, not --resume"
    (let [launched (atom nil)]
      (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                    (fn [cmd] (reset! launched cmd) nil)]
        (sut/execute-resume! :ollama "sess-o")
        (is (= "ollama"    (first @launched)))
        (is (= "--continue" (second @launched)))))))

(deftest execute-resume-coerces-string-backend
  (testing "string backend is coerced to keyword in result"
    (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                  (fn [_cmd] nil)]
      (let [result (sut/execute-resume! "codex" "sess-s")]
        (is (= :codex (:backend result)))))))
