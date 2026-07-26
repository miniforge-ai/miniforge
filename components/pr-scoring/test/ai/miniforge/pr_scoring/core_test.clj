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

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Helpers
(defn- ^{:stratum 0} stream []
  (es/create-event-stream {:sinks []}))

(defn- ^{:stratum 0} scored-events [events]
  (filter #(= :pr/scored (:event/type %)) events))

(defn- ^{:stratum 0} pr-created-event [_stream repo number]
  ;; pr/created isn't an event-stream constructor yet, so hand-build a
  ;; minimal envelope matching the producer shape other components use.
  ;; `_stream` kept unused for call-site symmetry with the real
  ;; event-stream producer signature (every call site passes `s`).
  {:event/type            :pr/created
   :event/id              (random-uuid)
   :event/timestamp       (java.util.Date.)
   :event/version         "1.0.0"
   :event/sequence-number 0
   :workflow/id           (random-uuid)
   :pr/repo               repo
   :pr/number             number
   :pr/title              "test PR"})

(def ^{:stratum 0} sample-scores
  {:readiness {:readiness/score 0.9
               :readiness/threshold 0.8
               :readiness/ready? true
               :readiness/factors []}
   :risk {:risk/score 0.2 :risk/level :low :risk/factors []}
   :policy {:policy/overall :pass
            :policy/packs-applied []
            :policy/summary {:critical 0 :high 0 :low 0 :info 0 :total 0}
            :policy/violations []}
   :recommendation :merge})

(deftest ^{:stratum 0} default-trigger-set-is-loaded-from-edn
  (testing "triggers come from the EDN resource, not a compiled-in literal"
    (let [triggers @scoring/default-trigger-event-types]
      (is (set? triggers))
      (is (contains? triggers :pr/created))
      (is (contains? triggers :pr/merged))
      (is (not (contains? triggers :workflow/started))
          "workflow events are not PR scoring triggers"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} captured-stream
  "Build a stream + attach a capture listener. Returns `[stream events-atom]`
   where `events-atom` accrues every published event."
  []
  (let [s (stream)
        captured (atom [])]
    (es/subscribe! s ::capture (fn [ev] (swap! captured conj ev)))
    [s captured]))

;------------------------------------------------------------------------------ Lifecycle
(deftest ^{:stratum 1} create-does-not-subscribe
  (let [s (stream)
        c (scoring/create s)]
    (is (false? (:subscribed? @c)))))

(deftest ^{:stratum 1} start-then-stop-roundtrips-subscription-flag
  (let [s (stream)
        c (scoring/attach! s)]
    (is (true? (:subscribed? @c)))
    (scoring/stop! c)
    (is (false? (:subscribed? @c)))))

(deftest ^{:stratum 1} start-is-idempotent
  (let [s (stream)
        c (scoring/attach! s)]
    (is (true? (:subscribed? @c)))
    (scoring/start! c)
    (is (true? (:subscribed? @c)) "repeat start! is a no-op")
    (scoring/stop! c)))

;------------------------------------------------------------------------------ Layer 2

;------------------------------------------------------------------------------ Emission
(deftest ^{:stratum 2} default-scorer-emits-nothing
  (let [[s captured] (captured-stream)
        _ (scoring/attach! s)]
    (es/publish! s (pr-created-event s "acme/widget" 42))
    (is (empty? (scored-events @captured))
        "default scorer-fn returns nil; no :pr/scored event emitted")))

(deftest ^{:stratum 2} scorer-returning-scores-emits-pr-scored
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

(deftest ^{:stratum 2} scorer-returning-nil-skips-emission
  (let [[s captured] (captured-stream)
        _ (scoring/attach! s {:scorer-fn (fn [_] nil)})]
    (es/publish! s (pr-created-event s "acme/widget" 42))
    (is (empty? (scored-events @captured))
        "nil return from scorer-fn suppresses :pr/scored emission")))

(deftest ^{:stratum 2} non-pr-events-do-not-trigger-scorer
  (let [calls (atom 0)
        [s captured] (captured-stream)
        _ (scoring/attach! s {:scorer-fn (fn [_] (swap! calls inc) sample-scores)})]
    (es/publish! s (es/workflow-started s (random-uuid)))
    (is (= 0 @calls) "workflow/started does not trigger scoring")
    (is (empty? (scored-events @captured)))))

(deftest ^{:stratum 2} trigger-override-narrows-dispatch
  (testing "A caller-supplied :trigger-event-types restricts scoring to just those events"
    (let [calls (atom [])
          [s captured] (captured-stream)
          _ (scoring/attach! s {:scorer-fn (fn [ev] (swap! calls conj (:event/type ev)) sample-scores)
                                :trigger-event-types #{:pr/merged}})]
      (es/publish! s (pr-created-event s "acme/widget" 42))  ; NOT in override set
      (es/publish! s (assoc (pr-created-event s "acme/widget" 43)
                            :event/type :pr/merged))
      (is (= [:pr/merged] @calls)
          "only :pr/merged events invoked the scorer")
      (is (= 1 (count (scored-events @captured)))))))

(deftest ^{:stratum 2} scorer-fn-exceptions-are-swallowed
  (let [[s captured] (captured-stream)
        _ (scoring/attach! s {:scorer-fn (fn [_] (throw (ex-info "boom" {})))})]
    (es/publish! s (pr-created-event s "acme/widget" 42))
    (is (empty? (scored-events @captured))
        "scorer throwing does not crash the component nor emit partial events")))

(deftest ^{:stratum 2} partial-score-maps-pass-through
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
