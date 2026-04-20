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

(ns ai.miniforge.bb-config.core-test
  "Layer 0 reader is tested via a tmp-file fixture. Layer 1's default-path
   is tested by passing an explicit path — we avoid depending on the test
   runner's cwd resolving a repo root."
  (:refer-clojure :exclude [load get])
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-config.core :as sut]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(defn- with-tmp-edn
  "Write `content` to a fresh .edn file, invoke `(f path)`, clean up."
  [content f]
  (let [path (str (fs/create-temp-file {:prefix "bb-tasks" :suffix ".edn"}))]
    (try
      (spit path content)
      (f path)
      (finally (fs/delete-if-exists path)))))

;------------------------------------------------------------------------------ Layer 1
;; Pure reader

(deftest test-read-edn-returns-empty-for-missing-file
  (testing "given a non-existent path → `{}`"
    (is (= {} (sut/read-edn "/nonexistent/bb-tasks.edn")))))

(deftest test-read-edn-parses-edn-content
  (testing "given a valid EDN file → the parsed map"
    (with-tmp-edn "{:a 1 :b {:c 2}}"
      (fn [path]
        (is (= {:a 1 :b {:c 2}} (sut/read-edn path)))))))

;------------------------------------------------------------------------------ Layer 2
;; Slice API

(deftest test-get-returns-task-slice-from-path
  (testing "given a path + key → the slice under that key"
    (with-tmp-edn "{:generate-icon {:sizes [16 32]} :publish {:r2-bucket \"x\"}}"
      (fn [path]
        (is (= {:sizes [16 32]}     (sut/get path :generate-icon)))
        (is (= {:r2-bucket "x"}     (sut/get path :publish)))
        (is (nil?                   (sut/get path :unknown)))))))

(deftest test-get-accepts-preloaded-map
  (testing "given a map + key → slice without re-reading"
    (is (= {:x 1} (sut/get {:foo {:x 1}} :foo)))
    (is (nil?     (sut/get {:foo {:x 1}} :bar)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-config.core-test)

  :leave-this-here)
