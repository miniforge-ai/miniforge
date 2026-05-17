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

(ns ai.miniforge.phase-software-factory.phase-handoff-test
  (:require
   [clojure.test :refer [deftest is]]
   [ai.miniforge.phase-software-factory.phase-handoff :as handoff]))

(def ^:private review-issue
  {:severity :blocking
   :description "GROUP 3 is missing the events show CLI"
   :suggestion "Add miniforge events show coverage"})

(def ^:private acceptance-group-phrase
  "Acceptance Group 4 is missing the export command")

(def ^:private abbreviated-acceptance-group-phrase
  "AG-5 is missing the import command")

(def ^:private acceptance-criterion-phrase
  "AC 6 is missing the resume command")

(def ^:private expected-acceptance-group
  "GROUP 3")

(def ^:private expected-acceptance-group-from-phrase
  "GROUP 4")

(def ^:private expected-abbreviated-acceptance-group
  "AG 5")

(def ^:private expected-acceptance-criterion
  "AC 6")

(def ^:private repair-attempt
  2)

(def ^:private first-repair-attempt
  1)

(def ^:private implement-feedback-summary
  "fix implement")

(def ^:private release-feedback-summary
  "fix release")

(deftest repair-request-normalizes-review-findings-test
  (let [workflow-id (random-uuid)
        request (handoff/repair-request {:workflow-id workflow-id
                                         :source-phase :review
                                         :target-phase :implement
                                         :phase-attempt repair-attempt
                                         :feedback [review-issue]})
        finding (first (get-in request [:frame/body :repair/findings]))]
    (is (= :repair-request (:frame/kind request)))
    (is (= handoff/repair-request-schema (:frame/schema request)))
    (is (= workflow-id (:workflow/id request)))
    (is (= :review (:transition/from request)))
    (is (= :implement (:transition/to request)))
    (is (= repair-attempt (:phase/attempt request)))
    (is (= :missing-acceptance-group (:finding/kind finding)))
    (is (= expected-acceptance-group (:finding/group-id finding)))
    (is (= (:description review-issue) (:finding/summary finding)))
    (is (not (contains? finding :finding/raw)))
    (is (not (contains? (:frame/body request) :repair/raw-feedback)))))

(deftest repair-request-omits-absent-optional-ids-test
  (let [request (handoff/repair-request {:source-phase :review
                                         :target-phase :implement
                                         :feedback [review-issue]})]
    (is (not (contains? request :workflow/id)))
    (is (not (contains? request :phase/attempt)))
    (is (not (contains? (:frame/body request) :repair/attempt)))))

(deftest normalize-findings-detects-configured-group-forms-test
  (let [findings (handoff/normalize-findings [acceptance-group-phrase
                                              abbreviated-acceptance-group-phrase
                                              acceptance-criterion-phrase])]
    (is (= [expected-acceptance-group-from-phrase
            expected-abbreviated-acceptance-group
            expected-acceptance-criterion]
           (mapv :finding/group-id findings)))))

(deftest latest-repair-request-filters-by-target-test
  (let [implement-request (handoff/repair-request {:source-phase :review
                                                   :target-phase :implement
                                                   :phase-attempt first-repair-attempt
                                                   :feedback implement-feedback-summary})
        release-request (handoff/repair-request {:source-phase :review
                                                 :target-phase :release
                                                 :phase-attempt first-repair-attempt
                                                 :feedback release-feedback-summary})
        ctx (-> {}
                (handoff/append-execution-handoff implement-request)
                (handoff/append-execution-handoff release-request))]
    (is (= implement-request
           (handoff/latest-repair-request ctx :implement)))
    (is (= [implement-request release-request]
           (:execution/phase-handoffs ctx)))))
