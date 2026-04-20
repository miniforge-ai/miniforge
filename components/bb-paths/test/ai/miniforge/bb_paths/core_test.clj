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

(ns ai.miniforge.bb-paths.core-test
  "Unit tests for bb-paths. Repo-root resolution is exercised against a
   scratch directory tree so the tests don't depend on the host repo's
   layout."
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [ai.miniforge.bb-paths.core :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Factories

(defn- with-scratch
  "Call `f` inside a fresh scratch directory. Deletes it on the way out.
   `f` receives the scratch-dir path as its only argument."
  [f]
  (let [d (sut/tmp-dir! "bb-paths-test-")]
    (try (f d)
         (finally (sut/delete-tree! d)))))

(defn- touch-bb-edn
  "Create an empty `bb.edn` at `dir`. Returns the containing dir."
  [dir]
  (fs/create-file (str dir "/bb.edn"))
  dir)

;------------------------------------------------------------------------------ Layer 1
;; Unit tests

(deftest test-under-root-joins-segments
  (testing "given segments → joined path beneath repo root"
    (let [root (sut/repo-root)
          joined (sut/under-root "a" "b")]
      (is (.startsWith joined root))
      (is (.endsWith joined "/a/b")))))

(deftest test-ensure-dir-creates-missing-and-is-idempotent
  (testing "given missing path → creates it"
    (with-scratch
      (fn [scratch]
        (let [target (str scratch "/x/y/z")]
          (is (not (fs/exists? target)))
          (sut/ensure-dir! target)
          (is (fs/exists? target))
          (testing "given existing path → idempotent"
            (sut/ensure-dir! target)
            (is (fs/exists? target))))))))

(deftest test-delete-tree-handles-nil-and-missing
  (testing "given nil → no-op"
    (is (nil? (sut/delete-tree! nil))))
  (testing "given missing path → no-op"
    (is (nil? (sut/delete-tree! "/tmp/does-not-exist-bb-paths-test")))))

(deftest test-tmp-dir-returns-fresh-directory
  (testing "given prefix → fresh directory"
    (let [d1 (sut/tmp-dir! "bb-paths-fresh-")
          d2 (sut/tmp-dir! "bb-paths-fresh-")]
      (try
        (is (fs/exists? d1))
        (is (fs/exists? d2))
        (is (not= d1 d2))
        (finally
          (sut/delete-tree! d1)
          (sut/delete-tree! d2))))))

(deftest test-repo-root-finds-nearest-bb-edn
  (testing "given a scratch tree with a bb.edn marker → walks up to it"
    (with-scratch
      (fn [scratch]
        (let [root (touch-bb-edn scratch)
              nested (sut/ensure-dir! (str scratch "/a/b/c"))]
          (is (fs/exists? (str root "/bb.edn")))
          (is (fs/exists? nested)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-paths.core-test)

  :leave-this-here)
