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

(ns ai.miniforge.anomaly.interface.any-anomaly-test
  "Transitional dual-shape predicate behavior. `any-anomaly?` must say
   yes for both the canonical anomaly map (`:anomaly/type`) and the
   legacy response anomaly map (`:anomaly/category`) while the W2
   convergence wave still has un-migrated producers. Removed in W5
   once every producer is canonical."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest any-anomaly?-true-for-canonical-anomaly
  (testing "any-anomaly? accepts a constructed canonical anomaly"
    (is (true? (anomaly/any-anomaly?
                (anomaly/anomaly :fault "boom" {}))))))

(deftest any-anomaly?-true-for-legacy-response-anomaly
  (testing "any-anomaly? accepts a legacy response anomaly carrying
            `:anomaly/category` — the shape un-migrated producers
            still emit during the W2 convergence wave"
    (is (true? (anomaly/any-anomaly?
                {:anomaly/category :anomalies/fault
                 :anomaly/message  "x"})))))

(deftest any-anomaly?-false-for-non-anomaly-map
  (testing "any-anomaly? rejects a plain map carrying neither shape"
    (is (false? (anomaly/any-anomaly? {:some/key "value"})))))

(deftest any-anomaly?-false-for-nil
  (testing "any-anomaly? rejects nil"
    (is (false? (anomaly/any-anomaly? nil)))))

(deftest any-anomaly?-false-for-non-map
  (testing "any-anomaly? rejects non-map inputs"
    (is (false? (anomaly/any-anomaly? "not a map")))
    (is (false? (anomaly/any-anomaly? 42)))
    (is (false? (anomaly/any-anomaly? :keyword)))
    (is (false? (anomaly/any-anomaly? [])))
    (is (false? (anomaly/any-anomaly? #{})))))

(deftest any-anomaly?-true-for-legacy-key-with-nil-value
  (testing "a map carrying `:anomaly/category` bound to nil is treated
            as the caller's bug, not as 'not an anomaly': the predicate
            uses `contains?`, which returns true when the key is
            present regardless of its value. Documented choice — the
            degenerate map flows through to the classifier so the
            upstream producer's bug surfaces rather than being silently
            dropped here. W5 retirement will delete this branch."
    (is (true? (anomaly/any-anomaly? {:anomaly/category nil})))))
