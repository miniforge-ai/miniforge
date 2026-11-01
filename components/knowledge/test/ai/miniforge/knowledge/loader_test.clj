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
(ns ai.miniforge.knowledge.loader-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.knowledge.loader :as sut]
   [ai.miniforge.knowledge.store :as store])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} initialize-knowledge-store-default-rules-dir-test
  (testing "defaults rules loading to .cursor/rules"
    (let [captured-rules-dir (atom nil)]
      (with-redefs [sut/load-rules (fn [_store rules-dir]
                                     (reset! captured-rules-dir rules-dir)
                                     {:loaded 0 :failed 0})
                    sut/load-docs (fn [_store _project-root]
                                    {:loaded 0 :failed 0 :files []})]
        (is (= 0 (:total (sut/initialize-knowledge-store! ::store {}))))
        (is (= ".cursor/rules" @captured-rules-dir))))))

(deftest ^{:stratum 0} initialize-knowledge-store-explicit-rules-dir-test
  (testing "respects an explicit rules-dir override"
    (let [captured-rules-dir (atom nil)]
      (with-redefs [sut/load-rules (fn [_store rules-dir]
                                     (reset! captured-rules-dir rules-dir)
                                     {:loaded 2 :failed 0})
                    sut/load-docs (fn [_store _project-root]
                                    {:loaded 1 :failed 0 :files []})]
        (let [result (sut/initialize-knowledge-store! ::store {:rules-dir ".standards"})]
          (is (= ".standards" @captured-rules-dir))
          (is (= 3 (:total result))))))))

(deftest ^{:stratum 0} load-project-docs-names-loaded-files-test
  (testing "returns the loaded doc files' names; it used to call .getName
            on the loaded zettel maps and throw whenever a doc file existed"
    (let [dir (.toFile (Files/createTempDirectory "loader-test"
                                                  (make-array FileAttribute 0)))
          doc (io/file dir "agents.md")]
      (try
        (spit doc "# Agents\n\nProject guidance.")
        (let [result (sut/load-project-docs (store/create-store) (str dir))]
          (is (= 1 (:loaded result)))
          (is (= 0 (:failed result)))
          (is (= ["agents.md"] (:files result))))
        (finally
          (io/delete-file doc true)
          (io/delete-file dir true))))))
