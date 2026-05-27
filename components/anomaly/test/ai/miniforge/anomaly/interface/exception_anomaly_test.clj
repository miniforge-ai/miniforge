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

(ns ai.miniforge.anomaly.interface.exception-anomaly-test
  "exception-anomaly wraps a thrown exception as a canonical anomaly of
   the given type, preserving the exception's message, class, and
   ex-data under :anomaly/data."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest exception-anomaly-is-canonical-with-given-type
  (testing "result satisfies anomaly? and uses the supplied type + message"
    (let [a (anomaly/exception-anomaly :fault "wrapped" (Exception. "boom"))]
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a)))
      (is (= "wrapped" (:anomaly/message a))))))

(deftest exception-anomaly-preserves-provenance
  (testing "exception message and class survive under :anomaly/data"
    (let [a (anomaly/exception-anomaly :fault "wrapped" (Exception. "boom"))]
      (is (= "boom" (get-in a [:anomaly/data :anomaly/ex-message])))
      (is (= "java.lang.Exception" (get-in a [:anomaly/data :anomaly/ex-class]))))))

(deftest exception-anomaly-captures-ex-data-when-present
  (testing "ex-data is carried only when the exception has it"
    (let [with-data (anomaly/exception-anomaly :fault "x" (ex-info "boom" {:k 1}))
          without   (anomaly/exception-anomaly :fault "x" (Exception. "boom"))]
      (is (= {:k 1} (get-in with-data [:anomaly/data :anomaly/ex-data])))
      (is (not (contains? (:anomaly/data without) :anomaly/ex-data))))))

(deftest exception-anomaly-merges-caller-data
  (testing "the 4-arity merges caller data alongside provenance"
    (let [a (anomaly/exception-anomaly :fault "x" {:stage :verify} (Exception. "boom"))]
      (is (= :verify (get-in a [:anomaly/data :stage])))
      (is (= "boom" (get-in a [:anomaly/data :anomaly/ex-message]))))))
