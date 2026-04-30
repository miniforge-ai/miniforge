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

(ns ai.miniforge.compliance-scanner.exceptions-as-data.output-format-test
  "Output shape: violations carry the canonical scanner Violation keys plus
   the linter-specific fields (`:severity`, `:column`, `:classification`)."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.compliance-scanner.exceptions-as-data :as exc]))

(defn- make-fixture-file!
  "Create a small synthetic file under a temp directory and return its
   relative path."
  [^java.io.File root rel content]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f content)
    rel))

(defn- with-temp-repo
  "Run `f` against a fresh temp directory; cleans up afterward.
   Uses random-uuid to keep parallel test runs from colliding."
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "exc-out-test-" (random-uuid)))
               .mkdirs)]
    (try (f root)
         (finally (doseq [^java.io.File ff (reverse (file-seq root))]
                    (.delete ff))))))

(deftest violation-shape-matches-scanner-canonical-keys
  (with-temp-repo
   (fn [root]
     (let [rel (make-fixture-file!
                root
                "components/foo/src/ai/miniforge/foo/core.clj"
                "(ns ai.miniforge.foo.core)\n(defn b [] (throw (ex-info \"x\" {})))")
           result (exc/scan-repo (.getAbsolutePath root))
           v (first (:violations result))]
       (testing "rule identification keys"
         (is (= :std/exceptions-as-data (:rule/id v)))
         (is (= "005" (:rule/category v)))
         (is (= "Exceptions as Data" (:rule/title v))))
       (testing "location keys (file:line:col)"
         (is (= rel (:file v)))
         (is (pos-int? (:line v)))
         (is (some? (:column v))))
       (testing "human-facing keys"
         (is (string? (:current v)))
         (is (string? (:suggested v))))
       (testing "linter contract keys"
         (is (= :warning (:severity v)) "severity is :warning, never :error")
         (is (false? (:auto-fixable? v)) "linter does not auto-fix")
         (is (contains? #{:cleanup-needed :fatal-only}
                        (:classification v))))))))

(deftest empty-repo-yields-zero-violations
  (with-temp-repo
   (fn [root]
     (let [result (exc/scan-repo (.getAbsolutePath root))]
       (is (= [] (:violations result)))
       (is (= 0 (:files-scanned result)))
       (is (= {:cleanup-needed 0 :fatal-only 0} (:counts result)))))))

(deftest summary-counts-match-classifications
  (with-temp-repo
   (fn [root]
     (make-fixture-file!
      root "components/foo/src/ai/miniforge/foo/core.clj"
      (str "(ns ai.miniforge.foo.core)\n"
           "(defn cleanup-site []\n"
           "  (throw (ex-info \"GET failed\" {})))\n"
           "(defn fatal-site []\n"
           "  (throw (ex-info \"Unknown thing\" {})))\n"))
     (let [{:keys [counts violations]} (exc/scan-repo (.getAbsolutePath root))]
       (is (= 2 (count violations)))
       (is (= 1 (:cleanup-needed counts)))
       (is (= 1 (:fatal-only counts)))))))
