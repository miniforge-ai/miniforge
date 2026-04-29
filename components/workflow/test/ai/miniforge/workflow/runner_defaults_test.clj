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

;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.workflow.runner-defaults-test
  "Tests for runner configuration-as-data defaults."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.runner-defaults :as defaults]))

(deftest max-phases-test
  (testing "returns a positive integer"
    (is (pos-int? (defaults/max-phases)))))

(deftest max-backoff-ms-test
  (testing "returns a positive integer"
    (is (pos-int? (defaults/max-backoff-ms)))))

(deftest backoff-base-ms-test
  (testing "returns a positive integer"
    (is (pos-int? (defaults/backoff-base-ms)))))

(deftest pause-poll-interval-ms-test
  (testing "returns a positive integer"
    (is (pos-int? (defaults/pause-poll-interval-ms)))))

(deftest default-workdir-test
  (testing "returns a non-blank string"
    (is (string? (defaults/default-workdir)))
    (is (not (clojure.string/blank? (defaults/default-workdir))))))

(deftest task-branch-prefix-test
  (testing "returns a non-blank string"
    (is (string? (defaults/task-branch-prefix)))))

(deftest default-docker-image-test
  (testing "returns a non-blank string"
    (is (string? (defaults/default-docker-image)))
    (is (clojure.string/includes? (defaults/default-docker-image) ":"))))
