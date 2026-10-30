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
(ns ai.miniforge.webhook-intake.deliveries-test
  (:require
   [ai.miniforge.webhook-intake.deliveries :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} claim-lifecycle-test
  (let [seen (sut/new-seen)]
    (testing "a new delivery is claimed from its first event"
      (is (= {:claimed? true :published 0} (sut/claim! seen "d-1"))))
    (testing "while in flight, and once done, it is not claimed again"
      (is (= {:claimed? false :status :in-flight} (sut/claim! seen "d-1")))
      (sut/record! seen "d-1" {:status :done :published 2})
      (is (= {:claimed? false :status :done} (sut/claim! seen "d-1"))))
    (testing "after a failure it is claimed from the first unpublished event"
      (sut/claim! seen "d-2")
      (sut/record! seen "d-2" {:status :failed :published 1})
      (is (= {:claimed? true :published 1} (sut/claim! seen "d-2"))))
    (testing "a retried delivery appears once in the eviction order"
      (is (= ["d-1" "d-2"] (:order @seen))))))

(deftest ^{:stratum 0} eviction-keeps-the-newest-test
  (testing "past capacity the oldest delivery is forgotten, from both the order and the map"
    (let [seen (sut/new-seen)]
      (doseq [i (range (inc sut/recent-delivery-capacity))]
        (sut/claim! seen (str "d-" i))
        (sut/record! seen (str "d-" i) {:status :done}))
      (is (= sut/recent-delivery-capacity (count (:order @seen)) (count (:deliveries @seen))))
      (is (not (contains? (:deliveries @seen) "d-0")))
      (is (= {:claimed? true :published 0} (sut/claim! seen "d-0"))))))

(deftest ^{:stratum 0} in-flight-deliveries-are-not-evicted-test
  (testing "a delivery still publishing survives capacity eviction"
    (let [seen (sut/new-seen)]
      (sut/claim! seen "slow")
      (doseq [i (range sut/recent-delivery-capacity)]
        (sut/claim! seen (str "d-" i))
        (sut/record! seen (str "d-" i) {:status :done}))
      (is (= {:claimed? false :status :in-flight} (sut/claim! seen "slow")))
      (is (not (contains? (:deliveries @seen) "d-0")) "the oldest finished delivery went instead"))))
