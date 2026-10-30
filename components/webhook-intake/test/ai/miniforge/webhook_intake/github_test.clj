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
(ns ai.miniforge.webhook-intake.github-test
  "GitHub deliveries map to the events workflow triggers match. Payloads
   are built from real GitHub API objects for miniforge-ai/miniforge
   (merged PR #2254, open PR #2259, the failing check suite of #2244's CI
   run) in the webhook envelope (action, repository, sender)."
  (:require
   [ai.miniforge.webhook-intake.github :as sut]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} delivery
  "A delivery of `event-name` whose body is the fixture `fixture`, or the
   fixture's payload transformed by `f`."
  ([event-name fixture] (delivery event-name fixture identity))
  ([event-name fixture f]
   {:event-name  event-name
    :delivery-id "72d3162e-cc78-11e3-81ab-4c9367dc0958"
    :payload     (-> (str "ai/miniforge/webhook_intake/github-fixtures/" fixture ".json")
                     io/resource slurp (json/parse-string true) f)}))

(def ^{:stratum 0} repo-1
  "A webhook pull_requests entry's repo object for this repository."
  {:id 1137151617 :name "miniforge"})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} merged-pull-request-test
  (testing "a merged pull request becomes :pr/merged with what triggers match on"
    (let [[event & more] (sut/delivery->events (delivery "pull_request" "pull_request.closed.merged"))]
      (is (empty? more))
      (is (= {:event/type :pr/merged :pr/repo "miniforge-ai/miniforge"
              :pr/branch "refactor/ci-checks-namespace" :pr/id 2254
              :pr/merge-sha "16840259d909d46bee2b328b9391a2186a39f280"
              :github/event "pull_request" :github/action "closed" :github/sender "H31MDALLR"}
             (select-keys event [:event/type :pr/repo :pr/branch :pr/id :pr/merge-sha
                                 :github/event :github/action :github/sender])))
      (is (uuid? (:event/id event))))))

(deftest ^{:stratum 1} closed-unmerged-pull-request-test
  (testing "a pull request closed without merging maps to nothing"
    (is (empty? (sut/delivery->events
                 (delivery "pull_request" "pull_request.closed.merged"
                           #(assoc-in % [:pull_request :merged] false)))))))

(deftest ^{:stratum 1} opened-pull-request-test
  (testing "an opened pull request becomes :pr/opened"
    (is (= [[:pr/opened 2259 "fix/ci-fixer-needs-logs"]]
           (map (juxt :event/type :pr/id :pr/branch)
                (sut/delivery->events (delivery "pull_request" "pull_request.opened")))))))

(deftest ^{:stratum 1} fork-pull-request-test
  (testing "a pull request from a fork maps to nothing"
    (is (empty? (sut/delivery->events
                 (delivery "pull_request" "pull_request.opened"
                           #(assoc-in % [:pull_request :head :repo :full_name] "someone/fork")))))))

(deftest ^{:stratum 1} failed-check-suite-test
  (testing "a failed suite listing no pull request maps to nothing (fork suites look like this)"
    (is (empty? (sut/delivery->events (delivery "check_suite" "check_suite.completed.failure")))))
  (testing "one :pr/ci-failed per same-repository pull request, with the suite's app and id"
    (is (= [[:pr/ci-failed 2244 "github-actions" 111056210235]]
           (map (juxt :event/type :pr/id :ci/app :ci/check-suite-id)
                (sut/delivery->events
                 (delivery "check_suite" "check_suite.completed.failure"
                           #(assoc-in % [:check_suite :pull_requests]
                                      [{:number 2244 :head {:repo repo-1} :base {:repo repo-1}}
                                       {:number 2251 :head {:repo {:id 9 :name "fork"}} :base {:repo repo-1}}]))))))))

(deftest ^{:stratum 1} fork-check-suite-test
  (testing "a suite with no head branch (how GitHub reports fork suites) maps to nothing"
    (is (empty? (sut/delivery->events
                 (delivery "check_suite" "check_suite.completed.failure"
                           #(assoc-in % [:check_suite :head_branch] nil)))))))

(deftest ^{:stratum 1} ignored-deliveries-test
  (testing "suites still running, neutral suites, and other events map to nothing"
    (is (empty? (sut/delivery->events
                 (delivery "check_suite" "check_suite.completed.failure"
                           #(assoc % :action "requested")))))
    (doseq [conclusion ["neutral" "success"]]
      (is (empty? (sut/delivery->events
                   (delivery "check_suite" "check_suite.completed.failure"
                             #(-> %
                                  (assoc-in [:check_suite :conclusion] conclusion)
                                  (assoc-in [:check_suite :pull_requests]
                                            [{:number 1 :head {:repo repo-1} :base {:repo repo-1}}])))))
          conclusion))
    (is (empty? (sut/delivery->events (delivery "ping" "pull_request.opened"))))))
