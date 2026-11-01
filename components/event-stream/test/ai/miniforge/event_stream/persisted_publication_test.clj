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
(ns ai.miniforge.event-stream.persisted-publication-test
  "publish-persisted! returns a legacy publish only when a durable sink wrote
   it; plain publish! still returns the event either way."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as events]
   [ai.miniforge.file-durability.interface :as disk]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} writing-sink [written]
  (events/durable-sink (fn [event] (swap! written conj event))))

(def ^{:stratum 0} failing-sink
  (events/durable-sink (fn [_event] (throw (ex-info "disk full" {})))))

(defn- ^{:stratum 0} event-for [workflow-id]
  {:event/type :gate/decision :event/id (random-uuid) :workflow/id workflow-id})

(defn- ^{:stratum 0} temp-path [create]
  (str (create (make-array FileAttribute 0))))

(defn- ^{:stratum 0} file-sink-stream
  "A legacy stream whose only sink is the configured file sink at `base-dir`."
  [base-dir]
  (events/create-event-stream
   {:config {:observability {:event-sinks [{:type :file :base-dir base-dir}]}}}))

(defn- ^{:stratum 0} event-files [dir]
  (filter #(.isFile ^java.io.File %) (file-seq (io/file dir))))

(defn- ^{:stratum 0} not-persisted? [result reason]
  (and (anomaly/any-anomaly? result)
       (= reason (get-in result [:anomaly/data :publication/reason]))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} durable-write-returns-the-event
  (let [written (atom [])
        stream (events/create-event-stream {:sinks [(writing-sink written)]})
        event (event-for (random-uuid))
        result (events/publish-persisted! stream event)]
    (is (= (:event/id event) (:event/id result)))
    (is (not (anomaly/any-anomaly? result)))
    (is (= [(:event/id event)] (mapv :event/id @written)))))

(deftest ^{:stratum 1} unpersisted-publishes-become-anomalies
  (testing "no sinks"
    (is (not-persisted? (events/publish-persisted! (events/create-event-stream {:sinks []})
                                                   (event-for (random-uuid)))
                        :no-durable-sink)))
  (testing "only sinks that do not persist"
    (is (not-persisted? (events/publish-persisted!
                         (events/create-event-stream {:sinks [(fn [_event] nil)]})
                         (event-for (random-uuid)))
                        :no-durable-sink)))
  (testing "the durable sink throws"
    (is (not-persisted? (events/publish-persisted!
                         (events/create-event-stream {:sinks [failing-sink]})
                         (event-for (random-uuid)))
                        :sink-write-failed)))
  (testing "one of two durable sinks throws"
    (is (not-persisted? (events/publish-persisted!
                         (events/create-event-stream {:sinks [(writing-sink (atom [])) failing-sink]})
                         (event-for (random-uuid)))
                        :sink-write-failed))))

(deftest ^{:stratum 1} file-sink-write-is-the-acknowledgment
  (testing "the file sink wrote the event"
    (let [dir (temp-path #(Files/createTempDirectory "persisted-" %))
          event (event-for (random-uuid))
          result (events/publish-persisted! (file-sink-stream dir) event)]
      (is (= (:event/id event) (:event/id result)))
      (is (= 1 (count (event-files dir))))))
  (testing "the file sink could not write: its base directory is a regular file"
    (let [not-a-dir (temp-path #(Files/createTempFile "persisted-" ".file" %))]
      (is (not-persisted? (events/publish-persisted! (file-sink-stream not-a-dir)
                                                     (event-for (random-uuid)))
                          :sink-write-failed)))))

(deftest ^{:stratum 1} file-sink-forces-the-write-before-acknowledging
  (testing "the write goes through file-durability, which forces bytes and directory entries"
    (let [dir (temp-path #(Files/createTempDirectory "persisted-" %))
          forced (atom 0)
          publish-bytes! disk/publish-bytes!
          result (with-redefs [disk/publish-bytes! (fn [& args]
                                                     (swap! forced inc)
                                                     (apply publish-bytes! args))]
                   (events/publish-persisted! (file-sink-stream dir) (event-for (random-uuid))))]
      (is (not (anomaly/any-anomaly? result)))
      (is (= 1 @forced))))
  (testing "a durability failure is a failed write, and leaves no temp file"
    (let [dir (temp-path #(Files/createTempDirectory "persisted-" %))
          result (with-redefs [disk/publish-bytes! (constantly
                                                    (anomaly/anomaly :fault "force failed" {}))]
                   (events/publish-persisted! (file-sink-stream dir) (event-for (random-uuid))))]
      (is (not-persisted? result :sink-write-failed))
      (is (empty? (event-files dir))))))

(deftest ^{:stratum 1} plain-publish-still-returns-the-event-on-a-failed-write
  (let [stream (events/create-event-stream {:sinks [failing-sink]})
        event (event-for (random-uuid))
        result (events/publish! stream event)]
    (is (= (:event/id event) (:event/id result)) "publish! keeps its contract")
    (is (= [(:event/id event)] (mapv :event/id (events/get-events stream)))
        "the event reached the in-memory log")))

(deftest ^{:stratum 1} rejection-passes-through
  (let [workflow-id (random-uuid)
        stream (events/create-event-stream {:sinks [(writing-sink (atom []))]})]
    (events/quiesce! stream {:workflow-id workflow-id})
    (is (:rejected? (events/publish-persisted! stream (event-for workflow-id))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.persisted-publication-test))
