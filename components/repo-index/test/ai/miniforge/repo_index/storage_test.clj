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
(ns ai.miniforge.repo-index.storage-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.repo-index.storage :as storage]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-repo-root
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "repo-index-storage-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ^{:stratum 0} temp-files
  "Names of leftover temp files in the repo's index directory."
  [repo-root]
  (->> (.listFiles (io/file repo-root ".miniforge/index"))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".tmp"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} save-index-round-trips-and-leaves-no-temp-file-test
  (testing "a saved index loads back unchanged and its temp file is gone"
    (let [root (temp-repo-root)
          index {:tree-sha "abc123" :files [{:path "src/a.clj"}]}]
      (is (some? (storage/save-index root index)))
      (is (= index (storage/load-index root "abc123")))
      (is (empty? (temp-files root))))))

(deftest ^{:stratum 1} save-index-failure-leaves-no-temp-file-test
  (testing "a failed move returns nil and removes its temp file"
    ;; A non-empty directory where the index file belongs makes the
    ;; replacing move fail deterministically.
    (let [root (temp-repo-root)
          blocker (io/file root ".miniforge/index/abc123.edn")]
      (.mkdirs blocker)
      (spit (io/file blocker "keep") "x")
      (is (nil? (storage/save-index root {:tree-sha "abc123"})))
      (is (empty? (temp-files root)) "no orphaned temp file after the failed move"))))

(deftest ^{:stratum 1} save-index-preserves-existing-on-write-failure-test
  (testing "a write failure during second save leaves the first index intact"
    ;; with-redefs intercepts spit so it writes corrupt bytes then throws,
    ;; mimicking an out-of-disk-space scenario mid-write.
    ;; With the atomic impl, spit targets the temp file — destination is
    ;; untouched — so the first index is still readable after the failure.
    (let [root       (temp-repo-root)
          first-idx  {:tree-sha "abc" :files [{:path "src/first.clj"}]}
          second-idx {:tree-sha "abc" :files [{:path "src/second.clj"}]}
          orig-spit  clojure.core/spit]
      (is (some? (storage/save-index root first-idx)))
      (with-redefs [clojure.core/spit
                    (fn [f _content]
                      (orig-spit f "{:partial true" :append false)
                      (throw (java.io.IOException. "simulated disk-full")))]
        (is (nil? (storage/save-index root second-idx))))
      (is (= first-idx (storage/load-index root "abc"))
          "first index survives the failed second write")
      (is (empty? (temp-files root)) "no orphaned temp file after the failure"))))
