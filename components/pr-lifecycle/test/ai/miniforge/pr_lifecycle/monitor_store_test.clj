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
(ns ai.miniforge.pr-lifecycle.monitor-store-test
  "Repo-slug paths and file reads for PR monitor state."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.config.interface :as mf-config]
   [ai.miniforge.pr-lifecycle.monitor-store :as sut]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} test-home
  "One temporary miniforge home for this namespace, emptied before each test."
  (str (fs/create-temp-dir {:prefix "mf-pr-monitor-store-"})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} isolated-home
  [run-test]
  (fs/delete-tree (fs/path test-home "state"))
  (with-redefs [mf-config/miniforge-home (constantly test-home)]
    (run-test)))

(deftest ^{:stratum 1} repo-file-test
  (testing "an owner/name slug gets its own directory under the miniforge home"
    (is (= (str test-home "/state/pr-monitor/repos/acme/app/watermarks.edn")
           (str (sut/repo-file "acme/app" "watermarks.edn"))
           (str (sut/repo-file "Acme/App" "watermarks.edn")))))
  (testing "anything else gets no file"
    (doseq [slug [nil "" "acme" "acme/app/extra" "../app" "acme/.." "./app" "a b/c"]]
      (is (nil? (sut/repo-file slug "watermarks.edn")) (pr-str slug)))))

(deftest ^{:stratum 1} read-map-test
  (let [file (str (fs/path test-home "state" "f.edn"))]
    (fs/create-dirs (fs/parent file))
    (is (nil? (sut/read-map file)) "absent file")
    (doseq [text ["" "{:a 1} {:b 2}" "[1 2]" "{:a"]]
      (spit file text)
      (is (anomaly/anomaly? (sut/read-map file)) (pr-str text)))
    (spit file "{:a 1}")
    (is (= {:a 1} (sut/read-map file)))))

(use-fixtures :each isolated-home)
