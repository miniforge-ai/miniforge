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

(ns ai.miniforge.cli.main.commands.run-test
  "Tests for the run command — input type detection and markdown dispatch."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.main.commands.run :as sut]))

;------------------------------------------------------------------------------ Layer 0: Input type detection

(deftest detect-input-type-test
  (testing "spec map detected by :spec/title"
    (is (= :spec (sut/detect-input-type {:spec/title "T" :spec/description "D"}))))

  (testing "dag map detected by :dag-id"
    (is (= :dag (sut/detect-input-type {:dag-id "abc" :tasks []}))))

  (testing "plan map detected by :plan/id"
    (is (= :plan (sut/detect-input-type {:plan/id "p1"}))))

  (testing "unrecognized map returns nil"
    (is (nil? (sut/detect-input-type {:some/key "value"}))))

  (testing "empty map returns nil"
    (is (nil? (sut/detect-input-type {})))))

(deftest markdown-spec?-test
  (testing ".md extension is markdown"
    (is (true? (sut/markdown-spec? "docs/design/compliance-scanner.md"))))

  (testing ".markdown extension is markdown"
    (is (true? (sut/markdown-spec? "specs/my-spec.markdown"))))

  (testing ".edn extension is not markdown"
    (is (false? (sut/markdown-spec? "specs/my-spec.edn"))))

  (testing ".json extension is not markdown"
    (is (false? (sut/markdown-spec? "specs/my-spec.json"))))

  (testing ".yaml extension is not markdown"
    (is (false? (sut/markdown-spec? "specs/my-spec.yaml"))))

  (testing "path with no extension is not markdown"
    (is (false? (sut/markdown-spec? "Makefile")))))
