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
(ns ai.miniforge.bb-test-runner.deps-config-test
  "Unit tests for `deps-config`."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-test-runner.deps-config :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-merge-deps-config-includes-test-alias-paths-and-deps
  (testing "deps root and alias classpath merge into one runnable config"
    (let [deps-config {:paths ["tasks"]
                       :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                       :aliases {:test {:extra-paths ["test" "mvp/src"]
                                        :extra-deps '{io.github.cognitect-labs/test-runner
                                                      {:git/tag "v0.5.1"}}}}}
          merged (sut/merge-deps-config deps-config {:alias-key :test})]
      (is (= ["tasks" "test" "mvp/src"] (:paths merged)))
      (is (= '{org.clojure/clojure {:mvn/version "1.12.0"}
               io.github.cognitect-labs/test-runner {:git/tag "v0.5.1"}}
             (:deps merged))))))

(deftest ^{:stratum 0} test-merge-deps-config-supports-multiple-aliases
  (testing "multiple alias keys compose source and test classpaths"
    (let [deps-config {:paths []
                       :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                       :aliases {:dev {:extra-paths ["components/foo/src" "components/foo/resources"]}
                                 :test {:extra-paths ["components/foo/test"]}}}
          merged (sut/merge-deps-config deps-config {:alias-keys [:dev :test]})]
      (is (= ["components/foo/src"
              "components/foo/resources"
              "components/foo/test"]
             (:paths merged))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-test-runner.deps-config-test)

  :leave-this-here)
