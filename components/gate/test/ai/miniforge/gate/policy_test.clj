;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.

(ns ai.miniforge.gate.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.gate.policy :as policy]))

;; -------------------------------------------------------------------------- check-review-approved
;; Cross-component contract: the reviewer agent (components/agent) produces an
;; artifact shaped like `build-review-artifact`'s output, with `:review/decision`
;; at the top level. The 2026-05-16 event-log-tool-visibility dogfood
;; (see project_dogfood_findings_2026_05_16) ran end-to-end and the reviewer
;; emitted :approved, but a strict ReviewIssue schema upstream silently
;; flipped the decision to :rejected. The gate side has always been correct;
;; pin the contract from this side so a future "fix" can't loosen
;; check-review-approved into a false positive.

(deftest check-review-approved-passes-on-explicit-approved
  (testing ":review/decision :approved → :passed? true"
    (is (= {:passed? true}
           (policy/check-review-approved
             {:review/decision :approved
              :review/summary "ok"
              :review/issues []}
             {})))))

(deftest check-review-approved-passes-on-conditionally-approved
  (testing ":review/decision :conditionally-approved → :passed? true"
    (is (= {:passed? true}
           (policy/check-review-approved
             {:review/decision :conditionally-approved}
             {})))))

(deftest check-review-approved-fails-on-changes-requested
  (let [result (policy/check-review-approved
                 {:review/decision :changes-requested}
                 {})]
    (is (false? (:passed? result)))
    (is (= :not-approved (-> result :errors first :type)))))

(deftest check-review-approved-fails-on-rejected
  (let [result (policy/check-review-approved
                 {:review/decision :rejected}
                 {})]
    (is (false? (:passed? result)))))

(deftest check-review-approved-fallback-paths-still-honored
  (testing "legacy artifact shapes without :review/decision fall back to metadata flags"
    (is (= {:passed? true}
           (policy/check-review-approved {:metadata {:approved true}} {})))
    (is (= {:passed? true}
           (policy/check-review-approved {:artifact/metadata {:approved true}} {})))
    (is (= {:passed? true}
           (policy/check-review-approved {:review {:approved true}} {})))))

(deftest check-review-approved-no-signal-fails-closed
  (testing "artifact with no decision and no metadata flag → :passed? false"
    (let [result (policy/check-review-approved {} {})]
      (is (false? (:passed? result))))))
