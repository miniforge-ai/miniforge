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

(ns ai.miniforge.phase.phase-result-test
  (:require
   [ai.miniforge.phase.phase-result :as pr]
   [clojure.test :refer [deftest is testing]]))

(deftest blocked-builds-refusal-result-test
  (testing "blocked tags an error-shaped result with a RefusalReason cause"
    (let [result (pr/blocked "env-7" "spec missing" :missing-input
                             {:tokens 0 :duration-ms 5})]
      (is (pr/blocked? result))
      (is (= :missing-input (pr/blocked-reason result)))
      (is (= :error (:status result)) "block reuses the error halt path")
      (is (= "env-7" (:environment-id result))))))

(deftest blocked?-false-for-plain-results-test
  (testing "non-blocked results carry no refusal reason"
    (is (not (pr/blocked? (pr/success "env-1" "done"))))
    (is (not (pr/blocked? (pr/error "env-1" "boom" "boom" {}))))
    (is (nil? (pr/blocked-reason (pr/success "env-1" "done"))))))
