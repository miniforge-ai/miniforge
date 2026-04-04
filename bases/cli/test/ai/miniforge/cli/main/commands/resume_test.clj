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

(ns ai.miniforge.cli.main.commands.resume-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main.commands.resume :as sut]
   [ai.miniforge.cli.workflow-selection-config :as selection-config]))

(deftest resolve-resume-workflow-test
  (testing "recorded workflow spec wins over configured fallback"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [_profile]
                    (throw (ex-info "should not be called" {})))]
      (is (= {:workflow-type :financial-etl
              :workflow-version "1.2.3"}
             (sut/resolve-resume-workflow
              {:workflow-spec {:name "financial-etl"
                               :version "1.2.3"}})))))

  (testing "missing workflow spec falls back to app-configured default profile"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [profile]
                    (is (= :default profile))
                    :lean-sdlc-v1)]
      (is (= {:workflow-type :lean-sdlc-v1
              :workflow-version "latest"}
             (sut/resolve-resume-workflow {})))))

  (testing "missing configured fallback raises a clear error"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [_profile] nil)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Could not resolve a default workflow for resume"
           (sut/resolve-resume-workflow {}))))))
