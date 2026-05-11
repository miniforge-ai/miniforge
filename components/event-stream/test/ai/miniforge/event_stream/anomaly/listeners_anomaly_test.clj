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

(ns ai.miniforge.event-stream.anomaly.listeners-anomaly-test
  "Coverage for `listeners/register-listener!`, `submit-annotation!`,
   and `submit-control-action!` boundary escalation via
   `response/throw-anomaly!`.

   Pre-cleanup, these sites constructed an anomaly map via
   `response/make-anomaly` and wrapped it inside `(throw (ex-info
   ... {:anomaly <anomaly-map>}))` — the explicit antipattern this
   migration kills. Post-cleanup, the throws route through the
   canonical `response/throw-anomaly!` so the anomaly is the data."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.listeners :as listeners])
  (:import
   (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ register-listener! — invalid capability

(deftest register-listener-invalid-capability-throws-anomaly
  (testing "invalid capability raises :anomalies/incorrect via throw-anomaly!"
    (let [stream (es/create-event-stream {:sinks []})]
      (is (thrown-with-msg?
           ExceptionInfo
           #"Invalid capability level"
           (listeners/register-listener!
            stream
            {:listener/type :dashboard
             :listener/capability :bogus
             :listener/identity {:principal "test"}
             :listener/callback (fn [_])}))))))

(deftest register-listener-invalid-capability-carries-data
  (testing "anomaly ex-data carries :capability and :valid set"
    (let [stream (es/create-event-stream {:sinks []})
          thrown (try
                   (listeners/register-listener!
                    stream
                    {:listener/type :dashboard
                     :listener/capability :wrong
                     :listener/identity {:principal "test"}
                     :listener/callback (fn [_])})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :wrong (:capability (ex-data thrown))))
      (is (contains? (set (:valid (ex-data thrown))) :observe)))))

;------------------------------------------------------------------------------ submit-annotation! — listener not found

(deftest submit-annotation-listener-not-found-throws-anomaly
  (testing "missing listener raises :anomalies/not-found via throw-anomaly!"
    (let [stream (es/create-event-stream {:sinks []})]
      (is (thrown-with-msg?
           ExceptionInfo
           #"Listener not found"
           (listeners/submit-annotation!
            stream
            (random-uuid)
            {:annotation/type :warning
             :annotation/content "test"}))))))

;------------------------------------------------------------------------------ submit-annotation! — insufficient capability

(deftest submit-annotation-insufficient-capability-throws-anomaly
  (testing "observe-only listener cannot submit annotation"
    (let [stream (es/create-event-stream {:sinks []})
          listener-id (es/register-listener!
                       stream
                       {:listener/type :dashboard
                        :listener/capability :observe
                        :listener/identity {:principal "test"}
                        :listener/callback (fn [_])})]
      (is (thrown-with-msg?
           ExceptionInfo
           #"Insufficient capability for annotation"
           (listeners/submit-annotation!
            stream
            listener-id
            {:annotation/type :warning
             :annotation/content "x"}))))))

(deftest submit-annotation-insufficient-capability-carries-data
  (testing "anomaly ex-data carries listener-id + capability + required"
    (let [stream (es/create-event-stream {:sinks []})
          listener-id (es/register-listener!
                       stream
                       {:listener/type :dashboard
                        :listener/capability :observe
                        :listener/identity {:principal "test"}
                        :listener/callback (fn [_])})
          thrown (try
                   (listeners/submit-annotation!
                    stream
                    listener-id
                    {:annotation/type :warning
                     :annotation/content "x"})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (= listener-id (:listener-id (ex-data thrown))))
      (is (= :observe (:capability (ex-data thrown))))
      (is (= :advise (:required (ex-data thrown)))))))

;------------------------------------------------------------------------------ submit-control-action! — listener not found

(deftest submit-control-action-listener-not-found-throws-anomaly
  (testing "missing listener raises :anomalies/not-found via throw-anomaly!"
    (let [stream (es/create-event-stream {:sinks []})]
      (is (thrown-with-msg?
           ExceptionInfo
           #"Listener not found"
           (listeners/submit-control-action!
            stream
            (random-uuid)
            {:control/type :pause}
            (fn [_] :ok)))))))

;------------------------------------------------------------------------------ submit-control-action! — insufficient capability

(deftest submit-control-action-insufficient-capability-throws-anomaly
  (testing "advise-only listener cannot submit control action"
    (let [stream (es/create-event-stream {:sinks []})
          listener-id (es/register-listener!
                       stream
                       {:listener/type :dashboard
                        :listener/capability :advise
                        :listener/identity {:principal "test"}
                        :listener/callback (fn [_])})]
      (is (thrown-with-msg?
           ExceptionInfo
           #"Insufficient capability for control action"
           (listeners/submit-control-action!
            stream
            listener-id
            {:control/type :pause}
            (fn [_] :ok)))))))
