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

(ns ai.miniforge.compliance-scanner.exceptions-as-data.path-traversal-test
  "Security: analyze-file must reject relative-path values that escape repo-root.
   A caller supplying \"../../../etc/passwd\" must receive [] — not file contents."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.compliance-scanner.exceptions-as-data :as exc]))

(defn- with-temp-root
  "Create a temp directory, call (f root-path), then delete the tree."
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "exc-traversal-" (random-uuid)))
               .mkdirs)]
    (try (f (.getAbsolutePath root))
         (finally (doseq [^java.io.File ff (reverse (file-seq root))]
                    (.delete ff))))))

(deftest path-traversal-via-dotdot-is-rejected
  (testing "relative-path with .. segments that escape repo-root returns []"
    (with-temp-root
     (fn [root]
       ;; Construct a traversal path that would resolve outside root.
       ;; e.g. root = /tmp/exc-traversal-<uuid>, escape = ../../../etc/passwd
       (let [result (exc/analyze-file root "../../../etc/passwd")]
         (is (= [] result)
             "analyze-file must return [] when relative-path escapes repo-root"))))))

(deftest path-traversal-via-absolute-path-is-rejected
  (testing "absolute path that is outside repo-root returns []"
    (with-temp-root
     (fn [root]
       ;; Supply an absolute path outside root directly as relative-path.
       ;; io/file ignores root when the second arg is absolute on most JVMs,
       ;; so canonical resolution must still catch this.
       (let [result (exc/analyze-file root "/etc/passwd")]
         (is (= [] result)
             "analyze-file must return [] when path resolves outside repo-root"))))))

(deftest legitimate-path-inside-root-is-allowed
  (testing "a path that stays inside repo-root is analyzed normally"
    (with-temp-root
     (fn [root]
       (let [rel "components/foo/src/ai/miniforge/foo/core.clj"
             f   (io/file root rel)]
         (io/make-parents f)
         (spit f "(ns ai.miniforge.foo.core)\n(defn safe [] :ok)")
         ;; No throws → no violations expected
         (let [result (exc/analyze-file root rel)]
           (is (vector? result))
           (is (empty? result))))))))
