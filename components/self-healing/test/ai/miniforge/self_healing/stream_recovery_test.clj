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

;;------------------------------------------------------------------------------ Helpers

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

;;
;; Stub helpers – shared across multiple tests
;;

(defn- healthy-health-stub
  "Returns a health stub with empty backends (all eligible) and standard fallback-order."
  []
  {:backends         {}
   :fallback-order   [:anthropic :openai :codex :ollama :google]
   :switch-cooldowns {}})

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

(deftest build-resume-command-anthropic-uses-claude-binary
  (testing ":anthropic backend maps to the 'claude' binary, not 'anthropic'"
    (is (= ["claude" "--resume" "sess-abc"]
           (sut/build-resume-command :anthropic "sess-abc")))))

(deftest build-resume-command-claude-code-uses-claude-binary
  (testing ":claude-code backend also maps to the 'claude' binary"
    (is (= ["claude" "--resume" "s1"]
           (sut/build-resume-command :claude-code "s1")))))

(deftest build-resume-command-google-uses-gemini-binary
  (testing ":google backend maps to the 'gemini' binary"
    (is (= ["gemini" "--resume" "s2"]
           (sut/build-resume-command :google "s2")))))

(deftest build-resume-command-with-extra-args
  (testing "appends extra args after session-id"
    (is (= ["openai" "--resume" "s123" "--timeout" "90"]
           (sut/build-resume-command :openai "s123" ["--timeout" "90"])))))

(deftest build-resume-command-ollama-uses-continue
  (testing "ollama uses --continue flag and 'ollama' binary"
    (is (= ["ollama" "--continue" "s999"]
           (sut/build-resume-command :ollama "s999")))))

(deftest build-resume-command-string-backend-coerced
  (testing "string backend is coerced to keyword for lookup"
    (is (= ["codex" "--resume" "sid"]
           (sut/build-resume-command "codex" "sid")))))

;;------------------------------------------------------------------------------ evaluate-stall-recovery – :resume path

(deftest evaluate-stall-recovery-first-hang-healthy-backend-returns-resume
  (testing "hang-count=1, backend has no health data (eligible) → :resume"
    (with-redefs [health/get-backend-success-rate (fn [_b] nil) ; no data → healthy
                  health/record-backend-call!     (fn [_b _s] nil)
                  health/select-best-backend      (fn [& _] nil)
                  health/trigger-backend-switch!  (fn [& _] nil)]
      (let [ctx    (make-ctx :anthropic "sess-111" 1 [:openai :codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :resume    (:action result)))
        (is (= "sess-111" (:session-id result)))
        (is (= :anthropic (:backend result)))))))

(deftest evaluate-stall-recovery-first-hang-healthy-rate-returns-resume
  (testing "hang-count=1, backend success-rate >= threshold → :resume"
    (with-redefs [health/get-backend-success-rate (fn [_b] 0.95)] ; above threshold
      (let [ctx    (make-ctx :anthropic "sess-ok" 1 [:openai])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :resume (:action result)))))))

;;------------------------------------------------------------------------------ evaluate-stall-recovery – health gate on count=1

(deftest evaluate-stall-recovery-first-hang-unhealthy-backend-skips-resume
  (testing "hang-count=1 but backend already below threshold → :failover, not :resume"
    (with-redefs [health/get-backend-success-rate (fn [_b] 0.50) ; below 0.90 threshold
                  health/record-backend-call!     (fn [_b _s] nil)
                  health/select-best-backend      (fn [& _] :openai)
                  health/trigger-backend-switch!  (fn [& _] nil)]
      (let [ctx    (make-ctx :anthropic "sess-sick" 1 [:openai :codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :failover (:action result)))
        (is (= :openai   (:new-backend result)))))))

(deftest evaluate-stall-recovery-first-hang-unhealthy-no-candidates-aborts
  (testing "hang-count=1, backend unhealthy, no allowed failover candidates → :abort"
    (with-redefs [health/get-backend-success-rate (fn [_b] 0.30) ; unhealthy
                  health/record-backend-call!     (fn [_b _s] nil)
                  health/select-best-backend      (fn [& _] nil) ; no candidate
                  health/trigger-backend-switch!  (fn [& _] nil)]
      (let [ctx    (make-ctx :anthropic "sess-s" 1 [])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :abort  (:action result)))
        (is (string? (:reason result)))))))

(deftest evaluate-stall-recovery-first-hang-records-failure-before-early-failover
  (testing "health-gate path calls record-backend-call! with false"
    (let [recorded (atom nil)]
      (with-redefs [health/get-backend-success-rate (fn [_b] 0.50)
                    health/record-backend-call!     (fn [b s] (reset! recorded {:b b :s s}))
                    health/select-best-backend      (fn [& _] :openai)
                    health/trigger-backend-switch!  (fn [& _] nil)]
        (sut/evaluate-stall-recovery (make-ctx :anthropic "sess-r" 1 [:openai]))
        (is (= {:b :anthropic :s false} @recorded))))))

;;------------------------------------------------------------------------------ evaluate-stall-recovery – degenerate count

(deftest evaluate-stall-recovery-zero-hang-count-returns-resume-with-anomaly
  (testing "hang-count=0 (degenerate) → :resume with :anomaly? true"
    (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                  health/record-backend-call!     (fn [_b _s] nil)
                  health/select-best-backend      (fn [& _] nil)
                  health/trigger-backend-switch!  (fn [& _] nil)]
      (let [ctx    (make-ctx :openai "sess-000" 0 [:anthropic])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :resume (:action result)))
        (is (true? (:anomaly? result)))))))

;;------------------------------------------------------------------------------ evaluate-stall-recovery – :failover path

(deftest evaluate-stall-recovery-second-hang-returns-failover
  (testing "hang-count=2 with healthy allowed failover backend → :failover"
    (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                  health/record-backend-call!     (fn [_b _s] nil)
                  health/select-best-backend      (fn [& _] :openai)
                  health/trigger-backend-switch!  (fn [& _] nil)]
      (let [ctx    (make-ctx :anthropic "sess-222" 2 [:openai :codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :failover (:action result)))
        (is (= :openai   (:new-backend result)))))))

(deftest evaluate-stall-recovery-third-hang-also-failover
  (testing "hang-count=3 also triggers :failover"
    (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                  health/record-backend-call!     (fn [_b _s] nil)
                  health/select-best-backend      (fn [& _] :codex)
                  health/trigger-backend-switch!  (fn [& _] nil)]
      (let [ctx    (make-ctx :anthropic "sess-333" 3 [:codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :failover (:action result)))
        (is (= :codex    (:new-backend result)))))))

(deftest evaluate-stall-recovery-records-failure-on-second-hang
  (testing "hang-count>=2 calls record-backend-call! with success?=false"
    (let [recorded (atom nil)]
      (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                    health/record-backend-call!     (fn [b s] (reset! recorded {:b b :s s}))
                    health/select-best-backend      (fn [& _] :openai)
                    health/trigger-backend-switch!  (fn [& _] nil)]
        (sut/evaluate-stall-recovery (make-ctx :anthropic "sess-f" 2 [:openai]))
        (is (= {:b :anthropic :s false} @recorded))))))

(deftest evaluate-stall-recovery-calls-trigger-switch-on-failover
  (testing "failover path calls trigger-backend-switch! with correct args"
    (let [switch-calls (atom [])]
      (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                    health/record-backend-call!     (fn [_b _s] nil)
                    health/select-best-backend      (fn [& _] :openai)
                    health/trigger-backend-switch!  (fn [f t ms]
                                                      (swap! switch-calls conj {:from f :to t :ms ms}))]
        (sut/evaluate-stall-recovery (make-ctx :anthropic "sess-sw" 2 [:openai]))
        (is (= 1         (count @switch-calls)))
        (is (= :anthropic (get-in @switch-calls [0 :from])))
        (is (= :openai    (get-in @switch-calls [0 :to])))
        (is (= 1800000    (get-in @switch-calls [0 :ms])))))))

(deftest evaluate-stall-recovery-forwards-allowed-set-to-select-best-backend
  (testing "allowed-failover-backends is forwarded to select-best-backend as a set"
    (let [received-allowed (atom nil)]
      (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                    health/record-backend-call!     (fn [_b _s] nil)
                    health/select-best-backend      (fn [_cur _thr _cool allowed]
                                                      (reset! received-allowed allowed)
                                                      :codex)
                    health/trigger-backend-switch!  (fn [& _] nil)]
        (sut/evaluate-stall-recovery (make-ctx :anthropic "sess-a" 2 [:codex :ollama]))
        (is (= #{:codex :ollama} @received-allowed))))))

(deftest evaluate-stall-recovery-uses-cooldown-from-config
  (testing "custom :backend-switch-cooldown-ms from :config is forwarded"
    (let [received-cooldown (atom nil)]
      (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                    health/record-backend-call!     (fn [_b _s] nil)
                    health/select-best-backend      (fn [_cur _thr cool _allowed]
                                                      (reset! received-cooldown cool)
                                                      :openai)
                    health/trigger-backend-switch!  (fn [& _] nil)]
        (sut/evaluate-stall-recovery
         {:phase-id                  :plan
          :backend                   :anthropic
          :session-id                "s"
          :hang-count                (atom 2)
          :config                    {:backend-switch-cooldown-ms 300000
                                      :backend-health-threshold   0.80}
          :allowed-failover-backends [:openai]})
        (is (= 300000 @received-cooldown))))))

;;------------------------------------------------------------------------------ evaluate-stall-recovery – :abort path

(deftest evaluate-stall-recovery-no-allowed-backends-returns-abort
  (testing "hang-count>=2 with empty allowed-failover-backends → :abort"
    (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                  health/record-backend-call!     (fn [_b _s] nil)
                  health/select-best-backend      (fn [& _] nil) ; no candidate
                  health/trigger-backend-switch!  (fn [& _] nil)]
      (let [ctx    (make-ctx :anthropic "sess-abort" 2 [])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :abort  (:action result)))
        (is (string?   (:reason result)))))))

(deftest evaluate-stall-recovery-select-best-nil-returns-abort
  (testing "hang-count>=2 but select-best-backend returns nil → :abort"
    (with-redefs [health/get-backend-success-rate (fn [_b] nil)
                  health/record-backend-call!     (fn [_b _s] nil)
                  health/select-best-backend      (fn [& _] nil)
                  health/trigger-backend-switch!  (fn [& _] nil)]
      (let [ctx    (make-ctx :anthropic "sess-cd" 2 [:openai :codex])
            result (sut/evaluate-stall-recovery ctx)]
        (is (= :abort (:action result)))))))

;;------------------------------------------------------------------------------ execute-resume!

(deftest execute-resume-returns-expected-shape
  (testing "execute-resume! returns map with :process :backend :session-id :command"
    ;; Use a plain Object as a sentinel — reify does not support abstract classes.
    (let [fake-proc (Object.)]
      (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                    (fn [_cmd] fake-proc)]
        (let [result (sut/execute-resume! :anthropic "sess-xyz")]
          (is (identical? fake-proc  (:process result)))
          (is (= :anthropic           (:backend result)))
          (is (= "sess-xyz"           (:session-id result)))
          (is (= ["claude" "--resume" "sess-xyz"] (:command result))))))))

(deftest execute-resume-with-extra-args
  (testing "extra-args are appended to the command"
    (let [launched (atom nil)]
      (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                    (fn [cmd] (reset! launched cmd) nil)]
        (sut/execute-resume! :openai "s42" ["--timeout" "120"])
        (is (= ["openai" "--resume" "s42" "--timeout" "120"] @launched))))))

(deftest execute-resume-ollama-uses-continue-flag-and-binary
  (testing "ollama subprocess uses --continue and 'ollama' binary"
    (let [launched (atom nil)]
      (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                    (fn [cmd] (reset! launched cmd) nil)]
        (sut/execute-resume! :ollama "sess-o")
        (is (= "ollama"    (first @launched)))
        (is (= "--continue" (second @launched)))))))

(deftest execute-resume-google-uses-gemini-binary
  (testing "google subprocess uses 'gemini' binary"
    (let [launched (atom nil)]
      (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                    (fn [cmd] (reset! launched cmd) nil)]
        (sut/execute-resume! :google "sess-g")
        (is (= "gemini" (first @launched)))))))

(deftest execute-resume-coerces-string-backend
  (testing "string backend is coerced to keyword in result map"
    (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                  (fn [_cmd] nil)]
      (let [result (sut/execute-resume! "codex" "sess-s")]
        (is (= :codex (:backend result)))))))

(deftest execute-resume-session-id-coerced-to-string
  (testing "non-string session-id is coerced to string"
    (with-redefs [ai.miniforge.self-healing.stream-recovery/start-process!
                  (fn [_cmd] nil)]
      (let [result (sut/execute-resume! :anthropic :some-keyword-session)]
        (is (= ":some-keyword-session" (:session-id result)))))))
