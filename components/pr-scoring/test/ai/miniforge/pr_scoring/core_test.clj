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

(ns ai.miniforge.pr-scoring.core-test
  "Plumbing tests for the pr-scoring component. Real scoring logic is
   supplied via an injected scorer-fn — these tests use a stub scorer
   and verify subscription, trigger dispatch, and `:pr/scored`
   emission."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.pr-scoring.interface :as scoring]))

;------------------------------------------------------------------------------ Helpers

(defn- stream []
  (es/create-event-stream {:sinks []}))

(defn- captured-stream
  "Build a stream + attach a capture listener. Returns `[stream events-atom]`
   where `events-atom` accrues every published event."
  []
  (let [s (stream)
        captured (atom [])]
    (es/subscribe! s ::capture (fn [ev] (swap! captured conj ev)))
    [s captured]))

(defn- scored-events [events]
  (filter #(= :pr/scored (:event/type %)) events))

(defn- pr-created-event [stream repo number]
  ;; pr/created isn't an event-stream constructor yet, so hand-build a
  ;; minimal envelope matching the producer shape other components use.
  {:event/type            :pr/created
   :event/id              (random-uuid)
   :event/timestamp       (java.util.Date.)
   :event/version         "1.0.0"
   :event/sequence-number 0
   :workflow/id           (random-uuid)
   :pr/repo               repo
   :pr/number             number
   :pr/title              "test PR"})

(def sample-scores
  {:readiness {:readiness/score 0.9
               :readiness/threshold 0.8
               :readiness/ready? true
               :readiness/factors []}
   :risk {:risk/score 0.2 :risk/level :low :risk/factors []}
   :policy {:policy/overall :pass
            :policy/packs-applied []
            :policy/summary {:critical 0 :major 0 :minor 0 :info 0 :total 0}
            :policy/violations []}
   :recommendation :merge})

;------------------------------------------------------------------------------ Lifecycle

(deftest create-does-not-subscribe
  (let [s (stream)
        c (scoring/create s)]
    (is (false? (:subscribed? @c)))))

(deftest start-then-stop-roundtrips-subscription-flag
  (let [s (stream)
        c (scoring/attach! s)]
    (is (true? (:subscribed? @c)))
    (scoring/stop! c)
    (is (false? (:subscribed? @c)))))

(deftest start-is-idempotent
  (let [s (stream)
        c (scoring/attach! s)]
    (is (true? (:subscribed? @c)))
    (scoring/start! c)
    (is (true? (:subscribed? @c)) "repeat start! is a no-op")
    (scoring/stop! c)))

;------------------------------------------------------------------------------ Emission

(deftest default-scorer-emits-nothing
  (let [[s captured] (captured-stream)
        _ (scoring/attach! s)]
    (es/publish! s (pr-created-event s "acme/widget" 42))
    (is (empty? (scored-events @captured))
        "default scorer-fn returns nil; no :pr/scored event emitted")))

(deftest scorer-returning-scores-emits-pr-scored
  (let [[s captured] (captured-stream)
        _ (scoring/attach! s {:scorer-fn (constantly sample-scores)})]
    (es/publish! s (pr-created-event s "acme/widget" 42))
    (let [scored (scored-events @captured)]
      (is (= 1 (count scored)))
      (let [ev (first scored)]
        (is (= "acme/widget" (:pr/repo ev)))
        (is (= 42 (:pr/number ev)))
        (is (= :low (get-in ev [:pr/risk :risk/level])))
        (is (= :merge (:pr/recommendation ev)))))))

(deftest scorer-returning-nil-skips-emission
  (let [[s captured] (captured-stream)
        _ (scoring/attach! s {:scorer-fn (fn [_] nil)})]
    (es/publish! s (pr-created-event s "acme/widget" 42))
    (is (empty? (scored-events @captured))
        "nil return from scorer-fn suppresses :pr/scored emission")))

(deftest non-pr-events-do-not-trigger-scorer
  (let [calls (atom 0)
        [s captured] (captured-stream)
        _ (scoring/attach! s {:scorer-fn (fn [_] (swap! calls inc) sample-scores)})]
    (es/publish! s (es/workflow-started s (random-uuid)))
    (is (= 0 @calls) "workflow/started does not trigger scoring")
    (is (empty? (scored-events @captured)))))

(deftest scorer-fn-exceptions-are-swallowed
  (let [[s captured] (captured-stream)
        _ (scoring/attach! s {:scorer-fn (fn [_] (throw (ex-info "boom" {})))})]
    (es/publish! s (pr-created-event s "acme/widget" 42))
    (is (empty? (scored-events @captured))
        "scorer throwing does not crash the component nor emit partial events")))

(deftest partial-score-maps-pass-through
  (testing "Scorer returns only readiness; emitted event carries only that field"
    (let [[s captured] (captured-stream)
          _ (scoring/attach! s {:scorer-fn (fn [_]
                                             {:readiness {:readiness/score 0.5
                                                          :readiness/threshold 0.8
                                                          :readiness/ready? false
                                                          :readiness/factors []}})})]
      (es/publish! s (pr-created-event s "acme/widget" 42))
      (let [ev (first (scored-events @captured))]
        (is (some? ev))
        (is (contains? ev :pr/readiness))
        (is (not (contains? ev :pr/risk)))
        (is (not (contains? ev :pr/policy)))
        (is (not (contains? ev :pr/recommendation)))))))
