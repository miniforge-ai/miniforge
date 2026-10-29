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
(ns ai.miniforge.llm.network-proxy-auth-test
  "A proxy that answers 407 wants credentials the probe does not send.
   The probe must report that as `:proxy-auth-required`, never as
   healthy, and the network-drop monitor must not stop a run over it:
   a 407 neither counts toward a drop nor clears earlier failures. The
   pause in outage detection is logged once when it starts and once
   when it ends, not on every probe."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.network-health :as nh]
   [ai.miniforge.llm.network-monitor :as nnm]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private probe-interval-ms
  "Fast probing so the monitor tests run in well under a second."
  10)

(def ^{:stratum 0} ^:private failure-threshold
  "Consecutive failures that declare a drop."
  3)

(def ^{:stratum 0} ^:private quiet-period-ms
  "How long a monitor that must not fire is watched: about 30 probes."
  300)

(def ^{:stratum 0} ^:private drop-timeout-ms
  "How long to wait for a monitor that must fire."
  2000)

(def ^{:stratum 0} ^:private interleaved-statuses
  "Three failures with 407 answers between them; the last status
   repeats once the script runs out."
  [:unreachable :proxy-auth-required :unreachable :proxy-auth-required
   :unreachable :proxy-auth-required])

(defn- ^{:stratum 0} scripted-probe
  "A probe-fn returning `statuses` in order, then the last one forever."
  [statuses]
  (let [remaining (atom statuses)]
    (fn [_probe-url]
      (let [[status & more] @remaining]
        (when more
          (reset! remaining more))
        status))))

(def ^{:stratum 0} ^:private auth-required-event
  "Log event for the start of a pause in outage detection."
  :llm/network-probe-proxy-auth-required)

(def ^{:stratum 0} ^:private auth-cleared-event
  "Log event for the end of the pause."
  :llm/network-probe-proxy-auth-cleared)

(def ^{:stratum 0} ^:private auth-then-healthy-statuses
  "A proxy that asks for credentials three times, then lets the probe
   through (the last status repeats)."
  [:proxy-auth-required :proxy-auth-required :proxy-auth-required :healthy])

(def ^{:stratum 0} ^:private auth-then-unreachable-statuses
  "A proxy that asks for credentials once, then stops answering (the
   last status repeats, so the drop fires)."
  [:proxy-auth-required :unreachable])

(defn- ^{:stratum 0} logged
  "Entries in the collecting logger's `entries` with event `event`."
  [entries event]
  (filterv #(= event (:log/event %)) @entries))

(defn- ^{:stratum 0} counting-407-probe
  "A probe-fn answering :proxy-auth-required every time, counting calls
   in `calls`."
  [calls]
  (fn [_probe-url]
    (swap! calls inc)
    :proxy-auth-required))

(defn- ^{:stratum 0} stub-407-client
  "http-kit-shaped client whose every request gets a 407 from the proxy."
  [_request]
  (delay {:status 407}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} monitor-opts
  "start-network-monitor! options that deliver the on-drop diagnostic to
   the promise `drops`. Log entries go to a collecting logger, so tests
   that do not read them print nothing."
  [drops]
  {:probe-url "https://api.anthropic.com/"
   :backend-key :claude
   :probe-interval-ms probe-interval-ms
   :failure-threshold failure-threshold
   :on-drop (partial deliver drops)
   :logger (first (log/collecting-logger))})

(deftest ^{:stratum 1} relayed-probe-answered-407-is-proxy-auth-required-test
  (testing "an http probe relayed by a proxy that answers 407 is not healthy"
    (is (= :proxy-auth-required
           (nh/probe-network "http://localhost:11434/api/version"
                             {:env {"HTTP_PROXY" "http://proxy.corp.example:3128"}
                              :http-client stub-407-client})))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} monitor-does-not-stop-a-run-over-407-test
  (testing "the default probe answering 407 on every probe never fires the drop"
    (let [drops (promise)]
      (with-redefs [nh/probe-network (constantly :proxy-auth-required)]
        (let [stop! (nnm/start-network-monitor! (monitor-opts drops))]
          (try
            (is (= ::no-drop (deref drops quiet-period-ms ::no-drop)))
            (finally
              (stop!))))))))

(deftest ^{:stratum 2} monitor-407-does-not-clear-failures-test
  (testing "three failures with 407s between them still fire the drop"
    (let [drops (promise)
          stop! (nnm/start-network-monitor!
                 (assoc (monitor-opts drops)
                        :probe-fn (scripted-probe interleaved-statuses)))]
      (try
        (is (= failure-threshold
               (:consecutive-failures (deref drops drop-timeout-ms nil))))
        (finally
          (stop!))))))

(deftest ^{:stratum 2} monitor-warns-once-over-repeated-407s-test
  (testing "repeated 407s log one warning that detection is paused, not one per probe"
    (let [[logger entries] (log/collecting-logger)
          calls (atom 0)
          stop! (nnm/start-network-monitor!
                 (assoc (monitor-opts (promise))
                        :logger logger
                        :probe-fn (counting-407-probe calls)))]
      (Thread/sleep quiet-period-ms)
      (stop!)
      (is (< 1 @calls) "the proxy answered 407 more than once")
      (let [warnings (logged entries auth-required-event)]
        (is (= 1 (count warnings)))
        (is (= :warn (:log/level (first warnings))))
        (is (= "https://api.anthropic.com/" (get-in (first warnings) [:data :probe-url]))))
      (is (empty? (logged entries auth-cleared-event))))))

(deftest ^{:stratum 2} monitor-logs-once-when-the-proxy-accepts-again-test
  (testing "leaving the 407 state logs once more, then nothing"
    (let [[logger entries] (log/collecting-logger)
          stop! (nnm/start-network-monitor!
                 (assoc (monitor-opts (promise))
                        :logger logger
                        :probe-fn (scripted-probe auth-then-healthy-statuses)))]
      (Thread/sleep quiet-period-ms)
      (stop!)
      (is (= 1 (count (logged entries auth-required-event))))
      (is (= 1 (count (logged entries auth-cleared-event)))))))

(deftest ^{:stratum 2} pause-ending-in-a-failure-is-not-reported-as-access-test
  (testing "407 then :unreachable: the resumed entry names the status, not proxy access"
    (let [[logger entries] (log/collecting-logger)
          drops (promise)
          stop! (nnm/start-network-monitor!
                 (assoc (monitor-opts drops)
                        :logger logger
                        :probe-fn (scripted-probe auth-then-unreachable-statuses)))]
      (try
        (is (some? (deref drops drop-timeout-ms nil)) "the failures still declare the drop")
        (let [cleared (logged entries auth-cleared-event)
              entry (first cleared)]
          (is (= 1 (count cleared)))
          (is (= :unreachable (get-in entry [:data :status])))
          (is (not (str/includes? (:log/message entry) "accepted"))
              "the message does not say the proxy let the probe through"))
        (finally
          (stop!))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.llm.network-proxy-auth-test)
  :leave-this-here)
