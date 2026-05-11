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
  "Coverage for `sinks/fleet-sink` and `sinks/create-sink` boundary
   escalation via `response/throw-anomaly!`.

   Configuration validation surfaces as anomalies in `ex-data` rather
   than ad-hoc throws. Fleet-sink without `:url` → `:anomalies/incorrect`;
   unknown sink-type in `create-sink` → `:anomalies/unsupported`;
   non-map non-vector sink-config → `:anomalies/incorrect`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.sinks :as sinks])
  (:import
   (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ fleet-sink missing :url

(deftest fleet-sink-missing-url-throws-anomaly
  (testing "fleet-sink without :url raises :anomalies/incorrect"
    (is (thrown-with-msg? ExceptionInfo
                          #"Fleet sink requires :url"
                          (sinks/fleet-sink {:api-key "k"})))))

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
      (is (= :bogus (:type (ex-data thrown)))))))

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
      (is (= "not-a-sink" (:config (ex-data thrown)))))))
