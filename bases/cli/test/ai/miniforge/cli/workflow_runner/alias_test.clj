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

(ns ai.miniforge.cli.workflow-runner.alias-test
  "Tests for workflow type aliasing and spec key fallback in the workflow runner."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-runner :as sut]))

;------------------------------------------------------------------------------ resolve-workflow-alias

(deftest resolve-workflow-alias-test
  (testing ":standard-sdlc resolves to :canonical-sdlc"
    (is (= :canonical-sdlc (sut/resolve-workflow-alias :standard-sdlc))))

  (testing ":canonical-sdlc passes through unchanged"
    (is (= :canonical-sdlc (sut/resolve-workflow-alias :canonical-sdlc))))

  (testing "unknown workflow types pass through unchanged"
    (is (= :quick-fix (sut/resolve-workflow-alias :quick-fix)))
    (is (= :test-only (sut/resolve-workflow-alias :test-only)))))

;------------------------------------------------------------------------------ load-or-create-workflow alias integration

(deftest load-or-create-workflow-aliases-standard-sdlc-test
  (testing ":standard-sdlc is resolved to :canonical-sdlc before loading"
    (let [loaded-type (atom nil)
          fake-loader (fn [workflow-id _version _opts]
                        (reset! loaded-type workflow-id)
                        {:workflow {:workflow/id workflow-id}
                         :validation {:valid? true}})]
      (sut/load-or-create-workflow fake-loader :standard-sdlc "latest")
      (is (= :canonical-sdlc @loaded-type)
          ":standard-sdlc should be translated to :canonical-sdlc before the loader is called"))))

;------------------------------------------------------------------------------ select-workflow-type spec key fallback

(deftest select-workflow-type-reads-workflow-type-key-test
  (testing ":spec/workflow-type takes precedence over :workflow/type"
    (let [spec {:spec/workflow-type :explicit-type
                :workflow/type :fallback-type}
          result (sut/select-workflow-type spec nil true)]
      (is (= :explicit-type result))))

  (testing ":workflow/type is used when :spec/workflow-type is absent"
    (let [spec {:workflow/type :standard-sdlc}
          result (sut/select-workflow-type spec nil true)]
      (is (= :standard-sdlc result)))))
