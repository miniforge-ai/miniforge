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

(ns ai.miniforge.schema.interface.failure-anomaly-shape-test
  "Lock the canonical anomaly shape produced by `schema/failure` and
   `schema/exception-failure` after the W2 anomaly convergence flip.

   Pre-flip these helpers emitted `:anomaly/category :anomalies/fault`
   via `response/make-anomaly` / `response/from-exception`. Post-flip
   they emit `:anomaly/type :fault` via `ai.miniforge.anomaly.interface`
   with no `:anomaly/subtype` (`:anomalies/fault` is one of the eight
   cognitect-standard categories that map 1:1 to a generic type)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures and factories — the canonical-shape post-flip contract.

(def ^:private data-key
  "Arbitrary domain data-key used by `schema/failure` and
   `schema/exception-failure`; the shape contract is independent of
   which data-key the caller passes."
  :pack)

(def ^:private fault-message
  "Plain string message exercised by the `failure/2` path."
  "File not found")

(def ^:private exception-message
  "Message attached to the test `ex-info` instance; exercised on the
   `exception-failure` path and asserted on `:anomaly/ex-message`."
  "kaboom")

(def ^:private exception-ex-data
  "ex-data carried by the test exception; locked on
   `:anomaly/ex-data` to prove exception provenance is preserved."
  {:phase :extraction})

(def ^:private exception-info-class-name
  "Fully-qualified class name of `clojure.lang.ExceptionInfo`,
   locked on `:anomaly/ex-class` to prove the class is carried
   through to the anomaly's data."
  "clojure.lang.ExceptionInfo")

(defn- make-ex
  "Build the canonical `ex-info` fixture used by every
   `exception-failure` assertion in this file."
  []
  (ex-info exception-message exception-ex-data))

;------------------------------------------------------------------------------ Layer 1
;; Shape locks — `:anomaly/type :fault` with no `:anomaly/subtype`.

(deftest failure-emits-canonical-fault-anomaly
  (testing "failure/2 attaches an anomaly with :anomaly/type :fault"
    (let [resp (schema/failure data-key fault-message)
          a    (:anomaly resp)]
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a))
          "generic-standard fault has no domain subtype")))

  (testing "failure carries the diagnostic message"
    (let [resp (schema/failure data-key fault-message)]
      (is (= fault-message (:anomaly/message (:anomaly resp)))))))

(deftest exception-failure-emits-canonical-fault-anomaly
  (testing "exception-failure wraps the exception as a :fault anomaly"
    (let [resp (schema/exception-failure data-key (make-ex))
          a    (:anomaly resp)]
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a)))))

  (testing "exception provenance is preserved under :anomaly/data"
    (let [resp (schema/exception-failure data-key (make-ex))
          data (:anomaly/data (:anomaly resp))]
      (is (= exception-message (:anomaly/ex-message data)))
      (is (= exception-info-class-name (:anomaly/ex-class data)))
      (is (= exception-ex-data (:anomaly/ex-data data))))))
