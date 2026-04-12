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
