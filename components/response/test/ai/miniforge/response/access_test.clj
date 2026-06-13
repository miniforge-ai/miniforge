;; Title: Miniforge.ai
;; Author: Christopher Lester (christopher@miniforge.ai)
;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.response.access-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.response.access :as access]))

(deftest phase-output-reads-canonical-location
  (testing "phase-output is [:result :output] — the one place"
    (is (= {:review/decision :approved}
           (access/phase-output {:result {:output {:review/decision :approved}}})))
    (is (nil? (access/phase-output {:result {:artifact {:review/decision :approved}}}))
        "does NOT fall back to :artifact — that ambiguity was the bug")
    (is (nil? (access/phase-output {})))))

(deftest review-decision-reads-canonical-key
  (testing "review-decision is :review/decision on the output map"
    (is (= :approved (access/review-decision {:review/decision :approved})))
    (is (= :rejected (access/review-decision {:review/decision :rejected})))
    (is (nil? (access/review-decision {:metadata {:approved true}}))
        "no fossil fallback to legacy :approved flags")
    (is (nil? (access/review-decision {})))))
