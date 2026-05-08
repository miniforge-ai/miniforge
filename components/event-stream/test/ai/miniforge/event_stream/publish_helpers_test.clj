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

(ns ai.miniforge.event-stream.publish-helpers-test
  "Stratum-by-stratum tests for the helpers `publish!` composes. The
   helpers are private (`defn-`) so tests reach them via `#'`-vars.
   Each helper is single-purpose; the existing `publish!` integration
   coverage in `core_test.clj` and `quiesce_drain_test.clj` exercises
   them composed."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.core :as core]))

(def ^:private workflow-quiesced?     #'core/workflow-quiesced?)
(def ^:private rejection-result       #'core/rejection-result)
(def ^:private rejection-if-quiesced  #'core/rejection-if-quiesced)
(def ^:private with-in-flight         #'core/with-in-flight)
(def ^:private record-event!          #'core/record-event!)
(def ^:private deliver-to-sink!       #'core/deliver-to-sink!)
(def ^:private deliver-to-sinks!      #'core/deliver-to-sinks!)
(def ^:private deliver-to-subscriber! #'core/deliver-to-subscriber!)
(def ^:private deliver-to-subscribers! #'core/deliver-to-subscribers!)

(defn- evt
  ([] (evt :test/event (random-uuid)))
  ([event-type wid]
   {:event/type event-type
    :event/id (random-uuid)
    :workflow/id wid
    :event/sequence-number 0
    :message "test"}))

;------------------------------------------------------------------------------ workflow-quiesced?

(deftest workflow-quiesced?-tracks-quiesced-set
  (let [wid-a (random-uuid)
        wid-b (random-uuid)
        stream (atom {:quiesced-workflows #{wid-a}})]
    (is (true?  (workflow-quiesced? stream (evt :x wid-a))))
    (is (false? (boolean (workflow-quiesced? stream (evt :x wid-b)))))
    (is (false? (boolean (workflow-quiesced? stream {:event/type :x}))))
        "missing :workflow/id is treated as not-quiesced — there's nothing to fence"))

;------------------------------------------------------------------------------ rejection-result

(deftest rejection-result-shape-is-stable
  (let [wid (random-uuid)
        result (rejection-result {:event/type :test/event :workflow/id wid} :workflow-quiesced)]
    (is (true? (:rejected? result)))
    (is (= :workflow-quiesced (:reason result)))
    (is (= wid (:workflow-id result)))
    (is (= :test/event (:event-type result)))))

;------------------------------------------------------------------------------ rejection-if-quiesced

(deftest rejection-if-quiesced-returns-nil-when-not-fenced
  (let [stream (atom {:quiesced-workflows #{} :logger nil})]
    (is (nil? (rejection-if-quiesced stream (evt))))))

(deftest rejection-if-quiesced-returns-result-when-fenced
  (let [wid (random-uuid)
        stream (atom {:quiesced-workflows #{wid} :logger nil})
        result (rejection-if-quiesced stream (evt :test/event wid))]
    (is (true? (:rejected? result)))
    (is (= :workflow-quiesced (:reason result)))))

;------------------------------------------------------------------------------ with-in-flight

(deftest with-in-flight-increments-and-decrements-around-body
  (let [stream (atom {:in-flight 0})
        observed (atom nil)]
    (with-in-flight stream
      (fn []
        (reset! observed (:in-flight @stream))
        :body-result))
    (is (= 1 @observed) "body sees the incremented counter")
    (is (= 0 (:in-flight @stream)) "post-call counter is decremented back to zero")))

(deftest with-in-flight-decrements-on-body-exception
  (let [stream (atom {:in-flight 0})]
    (is (thrown? Exception
                 (with-in-flight stream (fn [] (throw (RuntimeException. "boom"))))))
    (is (= 0 (:in-flight @stream))
        "decrement runs in finally so a body exception doesn't leak the slot")))

(deftest with-in-flight-returns-body-value
  (let [stream (atom {:in-flight 0})]
    (is (= :result (with-in-flight stream (constantly :result))))))

;------------------------------------------------------------------------------ record-event!

(deftest record-event!-appends-to-events-vector
  (let [stream (atom {:events []})
        e1 (evt) e2 (evt)]
    (record-event! stream e1)
    (record-event! stream e2)
    (is (= [e1 e2] (:events @stream)))))

;------------------------------------------------------------------------------ deliver-to-sink!

(deftest deliver-to-sink!-calls-the-sink
  (let [calls (atom [])
        sink (fn [e] (swap! calls conj e))
        e (evt)]
    (deliver-to-sink! sink e nil)
    (is (= [e] @calls))))

(deftest deliver-to-sink!-swallows-and-logs-exceptions
  (let [throwing-sink (fn [_] (throw (RuntimeException. "sink failed")))]
    ;; Logger nil — must not throw on the swallow path.
    (is (nil? (deliver-to-sink! throwing-sink (evt) nil))
        "exception from sink must not propagate; nil return is fine")))

;------------------------------------------------------------------------------ deliver-to-sinks!

(deftest deliver-to-sinks!-runs-all-sinks-even-when-one-throws
  (let [calls (atom [])
        good-sink (fn [e] (swap! calls conj [:good e]))
        bad-sink (fn [_] (throw (RuntimeException. "boom")))
        another-good-sink (fn [e] (swap! calls conj [:other e]))
        e (evt)]
    (deliver-to-sinks! [good-sink bad-sink another-good-sink] e nil)
    (is (= 2 (count @calls))
        "the bad sink does not stop the others")
    (is (= [:good e] (first @calls)))
    (is (= [:other e] (second @calls)))))

;------------------------------------------------------------------------------ deliver-to-subscriber!

(deftest deliver-to-subscriber!-respects-filter
  (let [calls (atom [])
        callback (fn [e] (swap! calls conj e))
        accept-fn (constantly true)
        reject-fn (constantly false)
        e (evt)]
    (deliver-to-subscriber! :sub-1 callback reject-fn e nil)
    (is (empty? @calls) "filter rejected — callback must not run")
    (deliver-to-subscriber! :sub-1 callback accept-fn e nil)
    (is (= [e] @calls))))

(deftest deliver-to-subscriber!-swallows-callback-exceptions
  (let [throwing-cb (fn [_] (throw (RuntimeException. "cb failed")))]
    (is (nil? (deliver-to-subscriber! :sub-1 throwing-cb (constantly true) (evt) nil))
        "callback exception must not propagate")))

;------------------------------------------------------------------------------ deliver-to-subscribers!

(deftest deliver-to-subscribers!-applies-filters-and-isolates-failures
  (let [calls (atom [])
        cb-a (fn [e] (swap! calls conj [:a e]))
        cb-b (fn [_] (throw (RuntimeException. "b boom")))
        cb-c (fn [e] (swap! calls conj [:c e]))
        subscribers {:sub-a cb-a :sub-b cb-b :sub-c cb-c}
        ;; sub-c gets a filter that rejects every event.
        filters {:sub-c (constantly false)}
        e (evt)]
    (deliver-to-subscribers! subscribers filters e nil)
    (is (= [[:a e]] @calls)
        "a runs (no filter, accept by default), b throws (swallowed), c is filtered out")))
