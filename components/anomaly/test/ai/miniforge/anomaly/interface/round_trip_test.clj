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

(ns ai.miniforge.anomaly.interface.round-trip-test
  "Anomalies are designed to be data — they have to survive an EDN
   serialize/deserialize trip without losing fidelity. This is what
   lets the evidence-bundle and event-stream components persist them
   to disk and replay later.

   Instants tunnel through EDN as `#inst` literals via java.util.Date.
   Date carries millisecond precision, so the test fixture truncates
   the original Instant to milliseconds before round-tripping —
   anything finer is not representable in standard EDN and is not
   part of the contract."
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest testing is]]
   [clojure.walk :as walk]
   [ai.miniforge.anomaly.interface :as anomaly])
  (:import
   (java.time.temporal ChronoUnit)))

(def ^:private edn-readers
  "Reader map that materializes `#inst` tags back into
   java.time.Instant so timestamps round-trip with the same JVM type
   they were built with."
  {'inst (fn [s] (java.time.Instant/parse s))})

(defn- instant->date
  "Swap `java.time.Instant` for `java.util.Date` so the built-in EDN
   printer emits a `#inst` tagged literal."
  [x]
  (if (instance? java.time.Instant x)
    (java.util.Date/from x)
    x))

(defn- instants->dates
  "Walk `value`, replacing every Instant with the equivalent Date.
   Date round-trips natively through `pr-str` + `edn/read-string`."
  [value]
  (walk/postwalk instant->date value))

(defn- truncate-instant-to-millis
  "EDN `#inst` carries only millisecond precision. Truncate so the
   pre- and post-serialization values are comparable."
  [x]
  (if (instance? java.time.Instant x)
    (.truncatedTo ^java.time.Instant x ChronoUnit/MILLIS)
    x))

(defn- normalize
  "Drop sub-millisecond precision on every Instant under `value`."
  [value]
  (walk/postwalk truncate-instant-to-millis value))

(defn- round-trip
  "Serialize `value` to EDN and read it back. Instants tunnel through
   as `#inst` tagged literals via `java.util.Date` and are restored
   to `java.time.Instant` by `edn-readers` on the read side."
  [value]
  (edn/read-string {:readers edn-readers}
                   (pr-str (instants->dates value))))

(deftest anomaly-survives-edn-round-trip
  (testing "constructor output matches itself after pr-str / edn/read-string"
    (let [a (normalize (anomaly/anomaly :not-found "missing user"
                                        {:id 7 :nested {:k :v}}))
          b (round-trip a)]
      (is (= a b)))))

(deftest round-tripped-anomaly-still-validates
  (testing "the deserialized map still satisfies the anomaly? predicate"
    (let [a (normalize (anomaly/anomaly :unavailable "downstream down"
                                        {:host "db-1"}))
          b (round-trip a)]
      (is (anomaly/anomaly? b)))))

(deftest round-trip-preserves-every-canonical-key
  (testing "type, message, data, and at survive serialization"
    (let [a (normalize (anomaly/anomaly :conflict "duplicate" {:key "abc"}))
          b (round-trip a)]
      (is (= (:anomaly/type a)    (:anomaly/type b)))
      (is (= (:anomaly/message a) (:anomaly/message b)))
      (is (= (:anomaly/data a)    (:anomaly/data b)))
      (is (= (:anomaly/at a)      (:anomaly/at b))))))
