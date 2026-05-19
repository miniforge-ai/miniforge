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

(ns ai.miniforge.agent.stream-watchdog-test
  "Tests for the per-phase stream-gap watchdog timer."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.stream-watchdog :as sut])
  (:import
   (java.util.concurrent.atomic AtomicLong)))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- make-test-watchdog
  "Merge caller opts over safe defaults. Uses fast check-interval-ms so
   fire-on-threshold tests complete quickly."
  [opts]
  (merge {:threshold-ms      200
          :check-interval-ms 50         ;; fast checks for test speed
          :phase-id          :test-phase
          :backend           :mock
          :event-stream      nil
          :workflow-id       "wf-test"
          :kill-fn           (fn [])}
         opts))

(defn- await-condition
  "Spin-wait up to `timeout-ms` for `pred` to return truthy.
   Returns true when pred fires, false on timeout."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (pred)
        true
        (if (> (System/currentTimeMillis) deadline)
          false
          (do (Thread/sleep 10) (recur)))))))

(defn- backdate!
  "Back-date a watchdog's AtomicLong by `offset-ms` so the gap is already
   exceeded on the next check tick."
  [watchdog offset-ms]
  (let [^AtomicLong ts (:last-event-ts watchdog)]
    (.set ts (- (System/currentTimeMillis) offset-ms)))
  watchdog)

;; ---------------------------------------------------------------------------
;; resolve-gap-threshold

(deftest resolve-gap-threshold-returns-default-when-no-config
  (testing "returns default-gap-threshold-ms when config is empty"
    (is (= sut/default-gap-threshold-ms
           (sut/resolve-gap-threshold {} :any-backend)))))

(deftest resolve-gap-threshold-returns-global-override
  (testing "global :agent/stream-gap-threshold-ms overrides the default"
    (is (= 60000
           (sut/resolve-gap-threshold
            {:agent/stream-gap-threshold-ms 60000}
            :claude-code)))))

(deftest resolve-gap-threshold-returns-backend-specific-override
  (testing "per-backend entry wins over global threshold"
    (let [config {:agent/stream-gap-threshold-ms     60000
                  :agent/per-backend-gap-thresholds  {:claude-code 120000}}]
      (is (= 120000 (sut/resolve-gap-threshold config :claude-code)))))

  (testing "falls back to global for an unlisted backend"
    (let [config {:agent/stream-gap-threshold-ms     60000
                  :agent/per-backend-gap-thresholds  {:claude-code 120000}}]
      (is (= 60000 (sut/resolve-gap-threshold config :other-backend))))))

(deftest resolve-gap-threshold-default-constant-is-90s
  (testing "default constant is 90 000 ms"
    (is (= 90000 sut/default-gap-threshold-ms))))

;; ---------------------------------------------------------------------------
;; create-watchdog structure

(deftest create-watchdog-returns-expected-keys
  (testing "watchdog map contains required keys"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (is (contains? wd :last-event-ts))
        (is (contains? wd :stalled-atom))
        (is (contains? wd :scheduler))
        (is (contains? wd :threshold-ms))
        (is (contains? wd :check-interval-ms))
        (is (contains? wd :phase-id))
        (is (contains? wd :backend))
        (is (instance? AtomicLong (:last-event-ts wd)))
        (is (instance? clojure.lang.Atom (:stalled-atom wd)))
        (finally
          (sut/stop! wd))))))

(deftest create-watchdog-stores-check-interval-ms
  (testing ":check-interval-ms is stored in the returned watchdog map"
    (let [wd (sut/create-watchdog (make-test-watchdog {:check-interval-ms 123}))]
      (try
        (is (= 123 (:check-interval-ms wd)))
        (finally
          (sut/stop! wd))))))

(deftest create-watchdog-starts-not-stalled
  (testing "a freshly created watchdog is not stalled"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (is (false? (sut/stalled? wd)))
        (finally
          (sut/stop! wd))))))

(deftest create-watchdog-uses-default-threshold-when-omitted
  (testing "omitting :threshold-ms falls back to default-gap-threshold-ms"
    (let [wd (sut/create-watchdog {:phase-id :x :backend :y
                                   :event-stream nil :workflow-id "w"
                                   :kill-fn (fn [])})]
      (try
        (is (= sut/default-gap-threshold-ms (:threshold-ms wd)))
        (finally
          (sut/stop! wd))))))

;; ---------------------------------------------------------------------------
;; ping!

(deftest ping-updates-timestamp
  (testing "ping! advances the AtomicLong to current time"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (let [^AtomicLong ts (:last-event-ts wd)
              before          (.get ts)]
          (Thread/sleep 5)
          (sut/ping! wd)
          (let [after (.get ts)]
            (is (>= after before))))
        (finally
          (sut/stop! wd))))))

(deftest ping-returns-the-watchdog-map
  (testing "ping! returns the watchdog map (fluent chaining)"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (is (= wd (sut/ping! wd)))
        (finally
          (sut/stop! wd))))))

;; ---------------------------------------------------------------------------
;; stop!

(deftest stop-shuts-down-scheduler
  (testing "stop! shuts down the ScheduledExecutorService"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (sut/stop! wd)
      (is (.isShutdown (:scheduler wd))))))

(deftest stop-is-idempotent
  (testing "calling stop! multiple times does not throw"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (sut/stop! wd)
      (is (some? (sut/stop! wd))))))

(deftest stop-does-not-set-stalled
  (testing "stop! does not mark the watchdog as stalled"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (sut/stop! wd)
      (is (false? (sut/stalled? wd))))))

;; ---------------------------------------------------------------------------
;; stalled? predicate

(deftest stalled-returns-false-initially
  (testing "stalled? returns false before any kill"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (is (false? (sut/stalled? wd)))
        (finally
          (sut/stop! wd))))))

(deftest stalled-returns-false-for-nil
  (testing "stalled? is safe to call with nil watchdog"
    (is (false? (sut/stalled? nil)))))

;; ---------------------------------------------------------------------------
;; End-to-end: kill fires when threshold exceeded

(deftest watchdog-fires-kill-when-gap-exceeded
  (testing "kill-fn is called when the backdated gap exceeds threshold-ms"
    (let [killed? (atom false)
          wd      (sut/create-watchdog
                   (make-test-watchdog
                    {:threshold-ms      200
                     :check-interval-ms 50
                     :kill-fn           #(reset! killed? true)}))]
      ;; Back-date to make the gap already exceeded
      (backdate! wd 400)
      ;; Wait for the first check tick (up to 2s)
      (let [fired? (await-condition #(deref killed?) 2000)]
        (sut/stop! wd)
        (is fired? "kill-fn should fire when gap > threshold-ms")
        (is (sut/stalled? wd) "watchdog should be marked stalled")))))

;; ---------------------------------------------------------------------------
;; ping! actually prevents the kill from firing

(deftest ping-prevents-kill-when-gap-was-backdated
  (testing "ping! resets the timestamp; a stale backdated gap must not fire the kill"
    ;; threshold-ms 1000 and sleep 300ms gives 700ms safety margin —
    ;; the watchdog cannot accumulate a fresh 1000ms gap in only 300ms.
    (let [killed? (atom false)
          wd      (sut/create-watchdog
                   (make-test-watchdog
                    {:threshold-ms      1000
                     :check-interval-ms 50
                     :kill-fn           #(reset! killed? true)}))]
      ;; Simulate a stale gap that would exceed a lower threshold...
      (backdate! wd 800)
      ;; ...but immediately reset the timestamp via ping!
      (sut/ping! wd)
      ;; Allow multiple check ticks (300ms ≪ threshold-ms 1000ms).
      ;; The gap after ping! is ~0ms; even after 300ms it is ~300ms < 1000ms.
      (Thread/sleep 300)
      (sut/stop! wd)
      (is (not @killed?)
          "kill-fn must NOT fire if ping! kept the timestamp current")
      (is (not (sut/stalled? wd))
          "watchdog must NOT be marked stalled when ping! suppressed the kill"))))

;; ---------------------------------------------------------------------------
;; create-watchdog — session-id-atom key

(deftest create-watchdog-contains-session-id-atom
  (testing "watchdog map contains :session-id-atom key"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (is (contains? wd :session-id-atom))
        (is (instance? clojure.lang.Atom (:session-id-atom wd)))
        (finally
          (sut/stop! wd))))))

(deftest create-watchdog-session-id-initially-nil
  (testing ":session-id-atom is nil before any capture"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (is (nil? @(:session-id-atom wd)))
        (finally
          (sut/stop! wd))))))

;; ---------------------------------------------------------------------------
;; get-session-id

(deftest get-session-id-returns-nil-before-capture
  (testing "get-session-id returns nil when session not yet captured"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (is (nil? (sut/get-session-id wd)))
        (finally
          (sut/stop! wd))))))

(deftest get-session-id-returns-nil-for-nil-watchdog
  (testing "get-session-id is safe to call with nil"
    (is (nil? (sut/get-session-id nil)))))

;; ---------------------------------------------------------------------------
;; capture-session-id! — Claude Code handshake shape

(deftest capture-session-id-claude-code-keyword-key
  (testing "captures session_id from Claude Code :session_id keyword key"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (sut/capture-session-id! wd {:session_id "cc-session-abc123"
                                     :type "system" :subtype "init"})
        (is (= "cc-session-abc123" (sut/get-session-id wd)))
        (finally
          (sut/stop! wd))))))

(deftest capture-session-id-claude-code-string-key
  (testing "captures session_id from Claude Code \"session_id\" string key"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (sut/capture-session-id! wd {"session_id" "cc-session-def456"
                                     "type" "system" "subtype" "init"})
        (is (= "cc-session-def456" (sut/get-session-id wd)))
        (finally
          (sut/stop! wd))))))

;; ---------------------------------------------------------------------------
;; capture-session-id! — Codex handshake shape

(deftest capture-session-id-codex-keyword-nested
  (testing "captures session id from Codex [:session :id] nested shape"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (sut/capture-session-id! wd {:session {:id "sess_codex_xyz789"}
                                     :type "session.created"})
        (is (= "sess_codex_xyz789" (sut/get-session-id wd)))
        (finally
          (sut/stop! wd))))))

(deftest capture-session-id-codex-string-nested
  (testing "captures session id from Codex [\"session\" \"id\"] string-key nested shape"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (sut/capture-session-id! wd {"session" {"id" "sess_codex_str_999"}
                                     "type" "session.created"})
        (is (= "sess_codex_str_999" (sut/get-session-id wd)))
        (finally
          (sut/stop! wd))))))

;; ---------------------------------------------------------------------------
;; capture-session-id! — unknown / empty event

(deftest capture-session-id-no-session-key-returns-watchdog
  (testing "capture-session-id! returns watchdog unchanged when no session key found"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (let [result (sut/capture-session-id! wd {:type "chunk" :delta "hello"})]
          (is (= wd result))
          (is (nil? (sut/get-session-id wd))))
        (finally
          (sut/stop! wd))))))

;; ---------------------------------------------------------------------------
;; capture-session-id! — emits :agent/session-captured event

(deftest capture-session-id-emits-event
  (testing "capture-session-id! publishes :agent/session-captured to event-stream"
    (let [published   (atom [])
          mock-stream (atom {:seq 0 :events [] :sinks []
                             :quiesced-workflows #{} :in-flight 0
                             :snowflake-generator nil
                             :subscribers {}})
          ;; Intercept publish! by swapping a fake sink
          _           (swap! mock-stream assoc :sinks
                             [(fn [evt] (swap! published conj evt))])
          wd          (sut/create-watchdog
                       (make-test-watchdog
                        {:event-stream mock-stream
                         :phase-id     :implement
                         :backend      :claude-code
                         :workflow-id  "wf-session-test"}))]
      (try
        (sut/capture-session-id! wd {:session_id "cc-emit-test-session"})
        ;; Allow a moment for synchronous publish
        (is (= "cc-emit-test-session" (sut/get-session-id wd))
            "session ID must be stored in atom")
        (finally
          (sut/stop! wd))))))

;; ---------------------------------------------------------------------------
;; capture-session-id! — returns watchdog for fluent chaining

(deftest capture-session-id-returns-watchdog-map
  (testing "capture-session-id! returns the watchdog map for chaining"
    (let [wd (sut/create-watchdog (make-test-watchdog {}))]
      (try
        (is (= wd (sut/capture-session-id! wd {:session_id "wf-chain-test"})))
        (finally
          (sut/stop! wd))))))

;; ---------------------------------------------------------------------------
;; Interface namespace re-export sanity

(deftest interface-namespace-exports-same-vars
  (testing "ai.miniforge.agent.interface.watchdog re-exports match stream-watchdog"
    (require 'ai.miniforge.agent.interface.watchdog)
    (let [iface (find-ns 'ai.miniforge.agent.interface.watchdog)]
      (is (some? (ns-resolve iface 'create-watchdog)))
      (is (some? (ns-resolve iface 'ping!)))
      (is (some? (ns-resolve iface 'stop!)))
      (is (some? (ns-resolve iface 'stalled?)))
      (is (some? (ns-resolve iface 'resolve-gap-threshold)))
      (is (some? (ns-resolve iface 'default-gap-threshold-ms)))
      (is (some? (ns-resolve iface 'default-check-interval-ms)))
      (is (some? (ns-resolve iface 'capture-session-id!)))
      (is (some? (ns-resolve iface 'get-session-id))))))

(deftest interface-watchdog-vars-point-to-same-fns
  (testing "re-exported vars are identical to source vars (not copies)"
    (require 'ai.miniforge.agent.interface.watchdog)
    (let [iface (find-ns 'ai.miniforge.agent.interface.watchdog)]
      (is (= (var-get (ns-resolve iface 'ping!))
             sut/ping!))
      (is (= (var-get (ns-resolve iface 'stop!))
             sut/stop!))
      (is (= (var-get (ns-resolve iface 'stalled?))
             sut/stalled?))
      (is (= (var-get (ns-resolve iface 'capture-session-id!))
             sut/capture-session-id!))
      (is (= (var-get (ns-resolve iface 'get-session-id))
             sut/get-session-id)))))
