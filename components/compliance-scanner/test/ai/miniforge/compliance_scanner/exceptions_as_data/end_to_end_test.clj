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

(ns ai.miniforge.compliance-scanner.exceptions-as-data.end-to-end-test
  "End-to-end: run the linter against a small synthetic fixture tree and
   assert the expected hit count and category split."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.compliance-scanner.exceptions-as-data :as exc]))

(defn- write!
  [^java.io.File root rel content]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f content)))

(defn- with-fixture
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "exc-e2e-" (random-uuid)))
               .mkdirs)]
    (try (f root)
         (finally (doseq [^java.io.File ff (reverse (file-seq root))]
                    (.delete ff))))))

(deftest synthetic-tree-yields-expected-counts
  (with-fixture
   (fn [root]
     ;; 1) Non-boundary cleanup-needed site
     (write! root
             "components/cleanup-needed/src/ai/miniforge/cleanup_needed/core.clj"
             "(ns ai.miniforge.cleanup-needed.core)
              (defn fetch! [url]
                (throw (ex-info \"GET request failed\" {:url url})))")

     ;; 2) Non-boundary :fatal-only site (programmer-error guard)
     (write! root
             "components/fatal-only/src/ai/miniforge/fatal_only/core.clj"
             "(ns ai.miniforge.fatal-only.core)
              (defn dispatch [k]
                (case k
                  :a 1
                  (throw (ex-info (str \"Unknown dispatch: \" k) {:k k}))))")

     ;; 3) Boundary file — should be skipped entirely
     (write! root
             "bases/cli/src/ai/miniforge/cli/main.clj"
             "(ns ai.miniforge.cli.main)
              (defn -main [& _]
                (throw (ex-info \"bad cli\" {})))")

     ;; 4) Test file — should NOT be scanned (linter is production-source-only)
     (write! root
             "components/cleanup-needed/test/ai/miniforge/cleanup_needed/core_test.clj"
             "(ns ai.miniforge.cleanup-needed.core-test)
              (deftest x (is (thrown? Exception (throw (ex-info \"t\" {})))))")

     ;; 5) Docstring with the word \"throws\" — must not trigger
     (write! root
             "components/clean-mod/src/ai/miniforge/clean_mod/core.clj"
             "(ns ai.miniforge.clean-mod.core)
              (defn pure
                \"Returns x; never throws or calls ex-info.\"
                [x]
                x)")

     (let [{:keys [violations files-scanned counts]}
           (exc/scan-repo (.getAbsolutePath root))]
       (testing "files-scanned excludes test files and includes only src"
         ;; src files: 3 (cleanup, fatal, boundary, clean-mod). Test file excluded.
         (is (= 4 files-scanned)))

       (testing "boundary file contributes no violations"
         (is (every? #(not (re-find #"cli/main" (:file %))) violations)))

       (testing "test file contributes no violations"
         (is (every? #(not (re-find #"/test/" (:file %))) violations)))

       (testing "expected counts"
         ;; 1 cleanup-needed, 1 fatal-only, 0 boundary, 0 docstring, 0 test
         (is (= 2 (count violations)))
         (is (= 1 (:cleanup-needed counts)))
         (is (= 1 (:fatal-only counts))))))))
