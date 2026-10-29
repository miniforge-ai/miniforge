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
   a 407 neither counts toward a drop nor clears earlier failures."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.network-health :as nh]
   [ai.miniforge.llm.network-monitor :as nnm]))

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

(defn- ^{:stratum 0} stub-407-client
  "http-kit-shaped client whose every request gets a 407 from the proxy."
  [_request]
  (delay {:status 407}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} monitor-opts
  "start-network-monitor! options that deliver the on-drop diagnostic to
   the promise `drops`."
  [drops]
  {:probe-url "https://api.anthropic.com/"
   :backend-key :claude
   :probe-interval-ms probe-interval-ms
   :failure-threshold failure-threshold
   :on-drop (partial deliver drops)})

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

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.llm.network-proxy-auth-test)
  :leave-this-here)
