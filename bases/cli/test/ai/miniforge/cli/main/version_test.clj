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
(ns ai.miniforge.cli.main.version-test
  "The CLI reports the version stamped into its classpath resource, and
   the dev fallback when no build stamped one."
  (:require
   [ai.miniforge.cli.main.version :as sut]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} temp-resource
  "A file URL whose content is `content`, standing in for the stamped
   classpath resource."
  [content]
  (let [f (java.io.File/createTempFile "miniforge-version" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    (io/as-url f)))

(deftest ^{:stratum 0} info-falls-back-without-resource-test
  (testing "the test classpath carries no stamped resource"
    (is (nil? (io/resource sut/resource-name))))
  (testing "an unstamped build reports the dev fallback, not a stale literal"
    (is (= "dev" (:version (sut/info))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} resource-version-test
  (testing "a stamped resource yields its :version"
    (is (= "2026.10.28.3"
           (sut/resource-version (temp-resource "{:version \"2026.10.28.3\"}")))))
  (testing "no resource yields nil"
    (is (nil? (sut/resource-version nil))))
  (testing "a resource without a usable :version yields nil"
    (is (nil? (sut/resource-version (temp-resource "{}"))))
    (is (nil? (sut/resource-version (temp-resource "{:version \"  \"}"))))
    (is (nil? (sut/resource-version (temp-resource "{:version 2026}"))))))

(deftest ^{:stratum 1} info-reads-stamped-resource-test
  (let [stamped (temp-resource "{:version \"2026.10.28.3\"}")]
    (with-redefs [io/resource (fn [path]
                                (when (= sut/resource-name path) stamped))]
      (is (= "2026.10.28.3" (:version (sut/info)))))))

(deftest ^{:stratum 1} print-shows-the-stamped-version-test
  (let [stamped (temp-resource "{:version \"2026.10.28.3\"}")]
    (with-redefs [io/resource (fn [path]
                                (when (= sut/resource-name path) stamped))]
      (let [first-line (first (str/split-lines (with-out-str (sut/print!))))]
        (is (str/ends-with? first-line " 2026.10.28.3"))))))
