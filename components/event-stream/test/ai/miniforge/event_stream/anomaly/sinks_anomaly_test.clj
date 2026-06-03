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

(ns ai.miniforge.event-stream.anomaly.sinks-anomaly-test
  "Coverage for `sinks/fleet-sink` and `sinks/create-sink` configuration
   validation.

   `fleet-sink` constructor validation surfaces as IllegalArgumentException
   per standards rule 005 §programmer-error-guards (a missing required
   :url / :api-key is a programmer error, not a runtime anomaly to be
   recovered from). `create-sink`'s unknown-type / invalid-config paths
   still throw via `response/throw-anomaly!` because they predate this
   PR; flagging for opportunistic cleanup per rule 211."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.sinks :as sinks])
  (:import
   (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ fleet-sink missing :url

(deftest fleet-sink-missing-url-rejects-config
  (testing "fleet-sink without :url throws IllegalArgumentException
            (programmer-error guard per rule 005)"
    (is (thrown-with-msg? IllegalArgumentException
                          #"Fleet sink requires :url"
                          (sinks/fleet-sink {:api-key "k"})))))

(deftest fleet-sink-missing-api-key-rejects-config
  (testing "fleet-sink without :api-key throws IllegalArgumentException
            (programmer-error guard per rule 005)"
    (is (thrown-with-msg? IllegalArgumentException
                          #"Fleet sink requires :api-key"
                          (sinks/fleet-sink {:url "https://fleet.example.com"})))))

;------------------------------------------------------------------------------ create-sink unknown type

(deftest create-sink-unknown-type-throws-anomaly
  (testing "unknown map sink type raises :anomalies/unsupported"
    (is (thrown-with-msg? ExceptionInfo
                          #"Unknown sink type"
                          (sinks/create-sink {:type :bogus})))))

(deftest create-sink-unknown-type-carries-data
  (testing "anomaly ex-data carries :type"
    (let [thrown (try
                   (sinks/create-sink {:type :bogus})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :bogus (:type (ex-data thrown))))
      (is (= :anomalies/unsupported (:anomaly/category (ex-data thrown)))))))

;------------------------------------------------------------------------------ create-sink invalid configuration

(deftest create-sink-invalid-config-throws-anomaly
  (testing "non-map non-vector sink-config raises :anomalies/incorrect"
    (is (thrown-with-msg? ExceptionInfo
                          #"Invalid sink configuration"
                          (sinks/create-sink 42)))))

(deftest create-sink-invalid-config-carries-data
  (testing "anomaly ex-data carries :config"
    (let [thrown (try
                   (sinks/create-sink "not-a-sink")
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (= "not-a-sink" (:config (ex-data thrown))))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))
