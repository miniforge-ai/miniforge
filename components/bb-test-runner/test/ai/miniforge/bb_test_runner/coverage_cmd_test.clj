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
(ns ai.miniforge.bb-test-runner.coverage-cmd-test
  "Unit tests for `coverage-cmd`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-test-runner.coverage-cmd :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-coverage-args-builds-cloverage-command
  (testing "coverage argv includes cloverage invocation and derived roots"
    (let [deps-config {:paths ["tasks"]
                       :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                       :aliases {:test {:extra-paths ["test" "mvp/src" "resources"]}}}
          args (sut/coverage-args deps-config {:alias-key :test
                                               :output-dir "target/custom-coverage"
                                               :fail-threshold 75})
          command-string (str/join " " args)]
      (is (some #{"-Sdeps"} args))
      (is (some #{"cloverage.coverage"} args))
      (is (some #{"--src-ns-path"} args))
      (is (some #{"--test-ns-path"} args))
      (is (str/includes? command-string "target/custom-coverage"))
      (is (str/includes? command-string "--fail-threshold 75"))
      (is (str/includes? command-string "--src-ns-path tasks"))
      (is (str/includes? command-string "--src-ns-path mvp/src"))
      (is (str/includes? command-string "--test-ns-path test"))
      (is (not (str/includes? command-string "--src-ns-path resources"))))))

(deftest ^{:stratum 0} test-coverage-install-args-prefetches-cloverage
  (testing "install argv prefetches the cloverage dependency"
    (let [args (sut/coverage-install-args)
          command-string (str/join " " args)]
      (is (= "-P" (first args)))
      (is (str/includes? command-string "cloverage"))
      (is (str/includes? command-string "1.2.4")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-test-runner.coverage-cmd-test)

  :leave-this-here)
