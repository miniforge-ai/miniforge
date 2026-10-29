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
(ns version-stamp-test
  "`bb build:cli` stamps the release version into the jar where the CLI
   reads it: the producer (tasks/version_stamp.clj) and the consumer
   (ai.miniforge.cli.main.version) agree on the resource and its shape."
  (:require
   [ai.miniforge.cli.main.version :as version]
   [clojure.test :refer [deftest is testing]]
   [version-stamp :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} temp-jar
  "A jar holding one unrelated entry, standing in for a fresh bb uberjar."
  []
  (let [f (java.io.File/createTempFile "miniforge" ".jar")]
    (.deleteOnExit f)
    (with-open [out (java.util.zip.ZipOutputStream. (java.io.FileOutputStream. f))]
      (.putNextEntry out (java.util.zip.ZipEntry. "ai/miniforge/cli/main.clj"))
      (.write out (.getBytes "(ns ai.miniforge.cli.main)" "UTF-8"))
      (.closeEntry out))
    (str f)))

(defn ^{:stratum 0} entry-text
  "The text of `entry` in `jar`, or nil when the jar has no such entry."
  [jar entry]
  (with-open [z (java.util.zip.ZipFile. ^String jar)]
    (when-let [e (.getEntry z entry)]
      (slurp (.getInputStream z e)))))

(deftest ^{:stratum 0} producer-and-consumer-share-the-resource-test
  (is (= version/resource-name sut/resource-name)))

(deftest ^{:stratum 0} requested-version-test
  (testing "MINIFORGE_VERSION is the version to stamp"
    (is (= "2026.10.28.3" (sut/requested-version {"MINIFORGE_VERSION" "2026.10.28.3"}))))
  (testing "unset or blank means an unstamped build"
    (is (nil? (sut/requested-version {})))
    (is (nil? (sut/requested-version {"MINIFORGE_VERSION" "  "})))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} stamp-jar-is-read-back-by-the-cli-test
  (let [jar (temp-jar)]
    (sut/stamp-jar! jar "2026.10.28.3")
    (testing "the CLI's reader gets the stamped version from the jar entry"
      (is (= "2026.10.28.3"
             (version/resource-version
              (java.io.StringReader. (entry-text jar sut/resource-name))))))
    (testing "the jar's other entries are untouched"
      (is (= "(ns ai.miniforge.cli.main)"
             (entry-text jar "ai/miniforge/cli/main.clj"))))))

(deftest ^{:stratum 1} restamp-replaces-the-version-test
  (let [jar (temp-jar)]
    (sut/stamp-jar! jar "2026.10.28.3")
    (sut/stamp-jar! jar "2026.10.28.4")
    (is (= "2026.10.28.4"
           (version/resource-version
            (java.io.StringReader. (entry-text jar sut/resource-name)))))))
