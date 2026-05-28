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

(deftest failure-emits-canonical-fault-anomaly
  (testing "failure/2 attaches an anomaly with :anomaly/type :fault"
    (let [resp (schema/failure :pack "File not found")
          a    (:anomaly resp)]
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a))
          "generic-standard fault has no domain subtype")))

  (testing "failure carries the diagnostic message"
    (let [resp (schema/failure :pack "boom")]
      (is (= "boom" (:anomaly/message (:anomaly resp)))))))

(deftest exception-failure-emits-canonical-fault-anomaly
  (testing "exception-failure wraps the exception as a :fault anomaly"
    (let [ex   (ex-info "kaboom" {:phase :extraction})
          resp (schema/exception-failure :pack ex)
          a    (:anomaly resp)]
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a)))))

  (testing "exception provenance is preserved under :anomaly/data"
    (let [ex   (ex-info "kaboom" {:phase :extraction})
          resp (schema/exception-failure :pack ex)
          data (:anomaly/data (:anomaly resp))]
      (is (= "kaboom" (:anomaly/ex-message data)))
      (is (= "clojure.lang.ExceptionInfo" (:anomaly/ex-class data)))
      (is (= {:phase :extraction} (:anomaly/ex-data data))))))
