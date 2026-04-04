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

(ns ai.miniforge.workflow.state-profile-test
  (:require
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.test :refer [deftest is testing]]))

(deftest workflow-state-profile-provider-test
  (testing "workflow state-profile provider loads from app-owned resources on the classpath"
    (let [provider (workflow/load-state-profile-provider)]
      (is (= :software-factory (workflow/default-state-profile-id)))
      (is (= #{:software-factory :etl}
             (set (workflow/available-state-profile-ids))))
      (is (= :software-factory
             (get-in provider [:profiles :software-factory :profile/id])))
      (is (= :etl
             (-> (workflow/resolve-state-profile :etl)
                 :profile/id)))))

  (testing "ETL completion semantics are still available through the ETL-owned resource layer"
    (let [etl-profile (workflow/resolve-state-profile :etl)]
      (is (= #{:completed}
             (:success-terminal-statuses etl-profile)))
      (is (= :completed
             (get-in etl-profile [:event-mappings :published :to])))))

  (testing "missing app profile ids are explicit rather than silently falling back"
    (is (nil? (workflow/resolve-state-profile :missing-profile)))))
