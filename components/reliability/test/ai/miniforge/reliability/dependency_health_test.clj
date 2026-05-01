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

(ns ai.miniforge.reliability.dependency-health-test
  (:require
   [clojure.test :refer [deftest is]]
   [ai.miniforge.reliability.interface :as rel]))

(defn- dependency-failure
  [overrides]
  (merge {:failure/class :failure.class/external
          :failure/source :external-provider
          :failure/vendor :anthropic
          :dependency/class :rate-limit
          :dependency/retryability :retryable
          :failure/message "rate limit"}
         overrides))

(deftest provider-rate-limits-project-degraded-health
  (let [rolling-state (rel/apply-dependency-signals {}
                                             [(dependency-failure {})]
                                             [])
        projection (rel/project-dependency-health rolling-state)
        anthropic-health (get projection :anthropic)]
    (is (= :degraded (:dependency/status anthropic-health)))
    (is (= :provider (:dependency/kind anthropic-health)))
    (is (= 1 (:dependency/failure-count anthropic-health)))))

(deftest repeated-outages-cross-unavailable-threshold
  (let [incidents (repeat 3 (dependency-failure {:dependency/class :outage
                                                 :failure/message "provider outage"}))
        rolling-state (rel/apply-dependency-signals {} incidents [])
        projection (rel/project-dependency-health rolling-state)
        anthropic-health (get projection :anthropic)]
    (is (= :unavailable (:dependency/status anthropic-health)))
    (is (= 3 (:dependency/failure-count anthropic-health)))))

(deftest environment-misconfiguration-is-tracked-separately
  (let [rolling-state (rel/apply-dependency-signals
                       {}
                       [(dependency-failure {:failure/source :user-env
                                             :failure/vendor nil
                                             :dependency/class :misconfiguration
                                             :dependency/retryability :operator-action
                                             :failure/message "missing api key"})]
                       [])
        projection (rel/project-dependency-health rolling-state)
        env-health (get projection :user-env)]
    (is (= :operator-action-required (:dependency/status env-health)))
    (is (= :environment (:dependency/kind env-health)))))

(deftest recovery-resets-health-to-healthy
  (let [rolling-state (rel/apply-dependency-signals
                       {}
                       [(dependency-failure {:dependency/class :outage})]
                       [])
        recovered-state (rel/apply-dependency-signals
                         rolling-state
                         []
                         [{:dependency/id :anthropic
                           :failure/source :external-provider
                           :failure/vendor :anthropic}])
        projection (rel/project-dependency-health recovered-state)
        anthropic-health (get projection :anthropic)]
    (is (= :healthy (:dependency/status anthropic-health)))
    (is (= 0 (:dependency/failure-count anthropic-health)))
    (is (some? (:dependency/last-recovered-at anthropic-health)))))
