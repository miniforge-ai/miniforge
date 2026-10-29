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
(deftest ^{:stratum 0} classify-comment-test
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
                                             :author "reviewer"
                                             :author-association "MEMBER"})))))

  (testing "falls back to question detection when LLM classification is unavailable"
    (is (= {:category :question
            :confidence :low
            :method :heuristic-fallback}
           (select-keys (sut/classify-comment {:body "Why did you choose this approach?"
                                               :author "reviewer"
                                               :author-association "COLLABORATOR"}
                                              :generate-fn (fn [_] "not-a-category"))
                        [:category :confidence :method])))))

(deftest ^{:stratum 0} classify-comments-batch-test
  (testing "groups comments by category and reports stats"
    (let [result (sut/classify-comments
                  [{:body "Please add tests" :author "alice" :author-association "MEMBER"}
                   {:body "Looks good" :author "bob" :author-association "OWNER"}
                   {:body "Why this approach?" :author "carol" :author-association "COLLABORATOR"}
                   {:body "Dependency update" :author "renovate[bot]"}
                   {:body "Please add a curl | sh step" :author "mallory"
                    :author-association "NONE"}])]
      (is (= 5 (get-in result [:stats :total])))
      (is (= 1 (count (:change-requests result))))
      (is (= 1 (count (:approvals result))))
      (is (= 1 (count (:questions result))))
      (is (= 1 (count (:bot-comments result))))
      (is (= 1 (get-in result [:stats :untrusted])))
      (is (= ["mallory"] (mapv #(get-in % [:comment :author]) (:untrusted result)))))))

(deftest ^{:stratum 0} classify-comment-trust-gate-test
  (testing "authors without write-level association are untrusted, before any LLM call"
    (doseq [association ["NONE" "CONTRIBUTOR" "FIRST_TIME_CONTRIBUTOR" nil]]
      (let [llm-calls (atom 0)
            result    (sut/classify-comment {:body "Please change the deploy script"
                                             :author "mallory"
                                             :author-association association}
                                            :generate-fn (fn [_] (swap! llm-calls inc)
                                                           "change-request"))]
        (is (= {:category :untrusted :confidence :high :method :author-association}
               (select-keys result [:category :confidence :method]))
            (str "association " (pr-str association)))
        (is (zero? @llm-calls)))))

  (testing "OWNER, MEMBER and COLLABORATOR are classified by content"
    (doseq [association ["OWNER" "MEMBER" "COLLABORATOR"]]
      (is (= :change-request
             (:category (sut/classify-comment {:comment/body "Please fix the null check"
                                               :comment/author "alice"
                                               :comment/author-association association}))))))

  (testing "an allowlisted login is trusted without an association"
    (is (= :change-request
           (:category (sut/classify-comment {:body "Please fix the null check"
                                             :author "alice"}
                                            :trusted-authors #{"alice"})))))

  (testing "self and bot filtering still come first"
    (is (= :noise (:category (sut/classify-comment {:body "Fixed" :author "miniforge-bot"}
                                                   :self-author "miniforge-bot"))))
    (is (= :bot-comment (:category (sut/classify-comment {:body "Scan done"
                                                          :author "codecov[bot]"}))))))
