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

(ns ai.miniforge.boundary.interface.multi-step-composition-test
  "Threading semantics. `execute` returns the updated chain so each
   call composes with the next. The chain is the *second* positional
   argument (category comes first), so callers thread with `as->` or
   explicit `let` rebinding rather than `->`. Steps accumulate in
   append order; later successes do not mask earlier failures."
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [ai.miniforge.boundary.interface :as boundary]
   [ai.miniforge.response-chain.interface :as chain]))

(defn- boom! [] (throw (RuntimeException. "boom")))

(deftest steps-accumulate-in-append-order
  (testing "operations are recorded in the order they were executed"
    (let [c (as-> (chain/create-chain :flow) c
              (boundary/execute :db      c :a (constantly 1))
              (boundary/execute :io      c :b (constantly 2))
              (boundary/execute :network c :c (constantly 3)))]
      (is (= [:a :b :c] (mapv :operation (chain/steps c)))))))

(deftest mixed-success-and-failure-records-all-steps
  (testing "a throw mid-flow does not stop subsequent steps from being appended"
    (let [c (as-> (chain/create-chain :flow) c
              (boundary/execute :db      c :a (constantly 1))
              (boundary/execute :network c :b boom!)
              (boundary/execute :io      c :c (constantly 3)))]
      (is (= 3 (count (chain/steps c))))
      (is (false? (chain/succeeded? c)))
      (is (= [true false true]
             (mapv :succeeded? (chain/steps c)))))))

(deftest chain-stays-schema-valid-through-composition
  (testing "after each execute the chain still validates against the schema"
    (let [c (as-> (chain/create-chain :flow) c
              (boundary/execute :db      c :a (constantly 1))
              (boundary/execute :timeout c :b boom!)
              (boundary/execute :parse   c :c (constantly 3)))]
      (is (m/validate chain/Chain c))
      (is (every? #(m/validate chain/Step %) (chain/steps c))))))

(deftest last-successful-or-finds-the-pre-failure-success
  (testing "after a failure, last-successful-or returns the most recent successful response"
    (let [c (as-> (chain/create-chain :flow) c
              (boundary/execute :db      c :a (constantly {:user 1}))
              (boundary/execute :network c :b boom!))]
      (is (= {:user 1} (chain/last-successful-or c ::none))))))

(deftest threading-is-pure
  (testing "each execute returns a new chain; the original is unchanged"
    (let [c0 (chain/create-chain :flow)
          c1 (boundary/execute :db c0 :a (constantly 1))]
      (is (= 0 (count (chain/steps c0))))
      (is (= 1 (count (chain/steps c1)))))))
