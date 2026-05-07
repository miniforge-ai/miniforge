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

(ns ai.miniforge.operator.anomaly.create-intervention-test
  "Coverage for the anomaly-returning `create-intervention` and its
   boundary-throwing sibling `create-intervention!`. Every validation
   step in the cascade returns a separate `:invalid-input` anomaly:

   - unknown intervention type
   - target type cannot be resolved (no caller value, no vocabulary default)
   - resolved target type is not in the recognized set
   - missing :intervention/target-id
   - missing :intervention/requested-by or :intervention/request-source

   Steps are checked in order; the first rejection short-circuits."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.operator.interface :as op]
            [ai.miniforge.operator.intervention :as intervention]
            [ai.miniforge.operator.messages :as messages]))

;------------------------------------------------------------------------------ Helpers

(defn- intervention-request
  [intervention-type target-id & {:as extra}]
  (merge {:intervention/type intervention-type
          :intervention/target-id target-id
          :intervention/requested-by "operator@example.com"
          :intervention/request-source :tui}
         extra))

;------------------------------------------------------------------------------ Happy path

(deftest create-intervention-returns-request-on-success
  (testing "valid request returns the constructed intervention, not an anomaly"
    (let [target-id (random-uuid)
          result (op/create-intervention
                  (intervention-request :retry target-id))]
      (is (not (anomaly/anomaly? result)))
      (is (= :retry (:intervention/type result)))
      (is (= target-id (:intervention/target-id result)))
      (is (= :proposed (:intervention/state result))))))

;------------------------------------------------------------------------------ Failure path: unknown intervention type

(deftest create-intervention-invalid-input-on-unknown-type
  (testing "unknown intervention type yields :invalid-input anomaly"
    (let [result (op/create-intervention
                  (intervention-request :unknown-action (random-uuid)))]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= (messages/t :intervention/unknown-type)
             (:anomaly/message result)))
      (is (= :unknown-action
             (get-in result [:anomaly/data :intervention/type]))))))

;------------------------------------------------------------------------------ Failure path: target type cannot be resolved

(deftest create-intervention-invalid-input-when-target-type-unresolvable
  (testing "intervention type with no default target type and no caller-supplied
            target type yields :invalid-input — exercised via a synthetic type
            patched into the bounded vocabulary"
    ;; The validation cascade calls the private `valid-type?` inside
    ;; `intervention.clj` directly (not the interface re-export). Patch
    ;; that symbol to admit a synthetic type with no default-target-type
    ;; mapping; the `:target-type-required` rejection then fires next.
    (with-redefs [intervention/valid-type? (fn [t] (= t :synthetic-no-default))]
      (let [result (op/create-intervention
                    (intervention-request :synthetic-no-default (random-uuid)))]
        (is (anomaly/anomaly? result))
        (is (= :invalid-input (:anomaly/type result)))
        (is (= (messages/t :intervention/target-type-required)
               (:anomaly/message result)))
        (is (= :synthetic-no-default
               (get-in result [:anomaly/data :intervention/type])))))))

;------------------------------------------------------------------------------ Failure path: target type not recognized

(deftest create-intervention-invalid-input-on-unknown-target-type
  (testing "caller-supplied target type that is not in the recognized set yields
            :invalid-input"
    (let [result (op/create-intervention
                  (intervention-request :retry (random-uuid)
                                        :intervention/target-type :not-a-real-target))]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= (messages/t :intervention/unknown-target-type)
             (:anomaly/message result)))
      (is (= :not-a-real-target
             (get-in result [:anomaly/data :intervention/target-type]))))))

;------------------------------------------------------------------------------ Failure path: missing target-id

(deftest create-intervention-invalid-input-on-missing-target-id
  (testing "missing :intervention/target-id yields :invalid-input"
    (let [result (op/create-intervention
                  {:intervention/type :retry
                   :intervention/requested-by "operator@example.com"
                   :intervention/request-source :tui})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= (messages/t :intervention/target-id-required)
             (:anomaly/message result)))
      (is (= :retry
             (get-in result [:anomaly/data :intervention/type]))))))

;------------------------------------------------------------------------------ Failure path: missing requester

(deftest create-intervention-invalid-input-on-missing-requested-by
  (testing "missing :intervention/requested-by yields :invalid-input"
    (let [result (op/create-intervention
                  {:intervention/type :retry
                   :intervention/target-id (random-uuid)
                   :intervention/request-source :tui})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= (messages/t :intervention/requester-required)
             (:anomaly/message result)))
      (is (nil? (get-in result [:anomaly/data :intervention/requested-by])))
      (is (= :tui
             (get-in result [:anomaly/data :intervention/request-source]))))))

(deftest create-intervention-invalid-input-on-missing-request-source
  (testing "missing :intervention/request-source yields :invalid-input"
    (let [result (op/create-intervention
                  {:intervention/type :retry
                   :intervention/target-id (random-uuid)
                   :intervention/requested-by "operator@example.com"})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= (messages/t :intervention/requester-required)
             (:anomaly/message result)))
      (is (= "operator@example.com"
             (get-in result [:anomaly/data :intervention/requested-by])))
      (is (nil? (get-in result [:anomaly/data :intervention/request-source]))))))

;------------------------------------------------------------------------------ Cascade short-circuits in order

(deftest create-intervention-cascade-stops-at-first-rejection
  (testing "an unknown type short-circuits before later checks would also fire —
            request omits target-id and requester, but only the type anomaly
            surfaces"
    (let [result (op/create-intervention
                  {:intervention/type :unknown-action})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= (messages/t :intervention/unknown-type)
             (:anomaly/message result))))))

;------------------------------------------------------------------------------ Boundary variant

(deftest create-intervention-bang-throws-on-anomaly
  (testing "boundary variant throws ex-info carrying the anomaly's message"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          (re-pattern (java.util.regex.Pattern/quote
                                       (messages/t :intervention/unknown-type)))
                          (op/create-intervention!
                           (intervention-request :unknown-action (random-uuid)))))))

(deftest create-intervention-bang-returns-on-success
  (testing "boundary variant returns the constructed intervention on success"
    (let [target-id (random-uuid)
          result (op/create-intervention!
                  (intervention-request :retry target-id))]
      (is (not (anomaly/anomaly? result)))
      (is (= target-id (:intervention/target-id result))))))
