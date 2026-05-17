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

(def ^:private expected-acceptance-group
  "GROUP 3")

(deftest repair-request-normalizes-review-findings-test
  (let [workflow-id (random-uuid)
        request (handoff/repair-request {:workflow-id workflow-id
                                         :source-phase :review
                                         :target-phase :implement
                                         :phase-attempt 2
                                         :feedback [review-issue]})
        finding (first (get-in request [:frame/body :repair/findings]))]
    (is (= :repair-request (:frame/kind request)))
    (is (= handoff/repair-request-schema (:frame/schema request)))
    (is (= workflow-id (:workflow/id request)))
    (is (= :review (:transition/from request)))
    (is (= :implement (:transition/to request)))
    (is (= :missing-acceptance-group (:finding/kind finding)))
    (is (= expected-acceptance-group (:finding/group-id finding)))
    (is (= review-issue (:finding/raw finding)))))

(deftest latest-repair-request-filters-by-target-test
  (let [implement-request (handoff/repair-request {:source-phase :review
                                                   :target-phase :implement
                                                   :phase-attempt 1
                                                   :feedback "fix implement"})
        release-request (handoff/repair-request {:source-phase :review
                                                 :target-phase :release
                                                 :phase-attempt 1
                                                 :feedback "fix release"})
        ctx (-> {}
                (handoff/append-execution-handoff implement-request)
                (handoff/append-execution-handoff release-request))]
    (is (= implement-request
           (handoff/latest-repair-request ctx :implement)))
    (is (= [implement-request release-request]
           (:execution/phase-handoffs ctx)))))
