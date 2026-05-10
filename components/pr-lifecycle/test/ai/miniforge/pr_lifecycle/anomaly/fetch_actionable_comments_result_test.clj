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

(ns ai.miniforge.pr-lifecycle.anomaly.fetch-actionable-comments-result-test
  "Coverage for `responder/fetch-actionable-comments-result`
   (anomaly-returning) and the private boundary helper.

   Upstream poller error → `:fault` anomaly. The private boundary
   helper rethrows via `response/throw-anomaly!` with
   `:anomalies/fault`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [ai.miniforge.pr-lifecycle.responder :as responder]))

;------------------------------------------------------------------------------ Happy path

(deftest fetch-result-returns-comments-and-groups-on-success
  (testing "successful poller fetch returns {:comments :groups}"
    (with-redefs [poller/fetch-pr-comments
                  (fn [_ _]
                    (dag/ok {:comments [{:comment/type :review-comment
                                         :comment/author "alice"
                                         :comment/path "src/x.clj"
                                         :comment/body "fix this"}]}))]
      (let [result (responder/fetch-actionable-comments-result "/tmp" 42)]
        (is (not (anomaly/anomaly? result)))
        (is (vector? (:comments result)))
        (is (vector? (:groups result)))
        (is (= 1 (count (:comments result))))))))

(deftest fetch-result-filters-bot-comments
  (testing "bot-authored comments are filtered out"
    (with-redefs [poller/fetch-pr-comments
                  (fn [_ _]
                    (dag/ok {:comments [{:comment/type :review-comment
                                         :comment/author "github-actions[bot]"
                                         :comment/path "src/x.clj"
                                         :comment/body "auto comment"}
                                        {:comment/type :review-comment
                                         :comment/author "alice"
                                         :comment/path "src/x.clj"
                                         :comment/body "real comment"}]}))]
      (let [result (responder/fetch-actionable-comments-result "/tmp" 42)]
        (is (= 1 (count (:comments result))))
        (is (= "alice" (:comment/author (first (:comments result)))))))))

;------------------------------------------------------------------------------ Failure path

(deftest fetch-result-returns-anomaly-on-poller-err
  (testing "poller DAG err yields :fault anomaly"
    (with-redefs [poller/fetch-pr-comments
                  (fn [_ _] (dag/err :network "down"))]
      (let [result (responder/fetch-actionable-comments-result "/tmp" 42)]
        (is (anomaly/anomaly? result))
        (is (= :fault (:anomaly/type result)))
        (is (= 42 (:pr-number (:anomaly/data result))))
        (is (= "down" (:error (:anomaly/data result))))))))

(deftest fetch-result-anomaly-message-stable
  (testing ":anomaly/message is the canonical fetch-failure string"
    (with-redefs [poller/fetch-pr-comments
                  (fn [_ _] (dag/err :downstream "boom"))]
      (let [result (responder/fetch-actionable-comments-result "/tmp" 42)]
        (is (= "Failed to fetch PR comments" (:anomaly/message result)))))))
