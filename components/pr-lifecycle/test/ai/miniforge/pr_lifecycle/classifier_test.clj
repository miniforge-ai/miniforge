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

(ns ai.miniforge.pr-lifecycle.classifier-test
  (:require
   [ai.miniforge.pr-lifecycle.classifier :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Classifier tests

(deftest classify-comment-test
  (testing "filters self comments as noise"
    (is (= {:category :noise
            :confidence :high
            :method :self-filter}
           (select-keys (sut/classify-comment {:body "Fixed in commit abc123"
                                               :author "miniforge-bot"}
                                              :self-author "miniforge-bot")
                        [:category :confidence :method]))))

  (testing "detects bot comments from author login"
    (is (= :bot-comment
           (:category (sut/classify-comment {:body "Automated scan"
                                             :author "dependabot[bot]"})))))

  (testing "uses heuristic approval detection when there are no actionable indicators"
    (is (= :approval
           (:category (sut/classify-comment {:body "LGTM, ship it"
                                             :author "reviewer"})))))

  (testing "falls back to question detection when LLM classification is unavailable"
    (is (= {:category :question
            :confidence :low
            :method :heuristic-fallback}
           (select-keys (sut/classify-comment {:body "Why did you choose this approach?"
                                               :author "reviewer"}
                                              :generate-fn (fn [_] "not-a-category"))
                        [:category :confidence :method])))))

(deftest classify-comments-batch-test
  (testing "groups comments by category and reports stats"
    (let [result (sut/classify-comments
                  [{:body "Please add tests" :author "alice"}
                   {:body "Looks good" :author "bob"}
                   {:body "Why this approach?" :author "carol"}
                   {:body "Dependency update" :author "renovate[bot]"}])]
      (is (= 4 (get-in result [:stats :total])))
      (is (= 1 (count (:change-requests result))))
      (is (= 1 (count (:approvals result))))
      (is (= 1 (count (:questions result))))
      (is (= 1 (count (:bot-comments result)))))))
