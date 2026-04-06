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

(ns ai.miniforge.mcp-context-server.context-cache-test
  "Unit tests for context cache pure helpers and tool handlers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.miniforge.mcp-context-server.context-cache :as cache]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn with-clean-cache [f]
  (cache/reset-state!)
  (try (f) (finally (cache/reset-state!))))

(use-fixtures :each with-clean-cache)

(defn with-temp-dir [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "ctx-cache-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq (io/file dir)))]
          (.delete ^java.io.File file))))))

;------------------------------------------------------------------------------ Layer 1
;; Pure helper tests

(deftest estimate-tokens-test
  (testing "estimates ~4 chars per token"
    (is (= 3 (cache/estimate-tokens "hello world!")))
    (is (= 1 (cache/estimate-tokens "abc"))))

  (testing "handles edge cases"
    (is (= 0 (cache/estimate-tokens "")))
    (is (= 0 (cache/estimate-tokens nil)))
    (is (= 0 (cache/estimate-tokens 42)))))

(deftest apply-offset-limit-test
  (let [content "line0\nline1\nline2\nline3\nline4"]

    (testing "no offset or limit returns all lines"
      (is (= content (cache/apply-offset-limit content nil nil))))

    (testing "offset skips lines"
      (is (= "line2\nline3\nline4" (cache/apply-offset-limit content 2 nil))))

    (testing "limit caps lines"
      (is (= "line0\nline1" (cache/apply-offset-limit content nil 2))))

    (testing "offset + limit together"
      (is (= "line1\nline2" (cache/apply-offset-limit content 1 2))))

    (testing "nil content returns empty"
      (is (= "" (cache/apply-offset-limit nil nil nil))))))

(deftest glob-matches?-test
  (testing "single star matches one segment"
    (is (cache/glob-matches? "src/*.clj" "src/core.clj"))
    (is (not (cache/glob-matches? "src/*.clj" "src/nested/core.clj"))))

  (testing "double star matches any depth"
    (is (cache/glob-matches? "**/*.clj" "src/core.clj"))
    (is (cache/glob-matches? "**/*.clj" "src/deep/nested/core.clj")))

  (testing "exact match"
    (is (cache/glob-matches? "src/core.clj" "src/core.clj"))
    (is (not (cache/glob-matches? "src/core.clj" "src/other.clj"))))

  (testing "dots are literal"
    (is (not (cache/glob-matches? "src/*.clj" "src/corexclj")))))

(deftest grep-file-test
  (let [content "(ns core)\n\n(defn greet [name]\n  (str \"Hello, \" name))"]

    (testing "finds matching lines with line numbers"
      (let [results (cache/grep-file "src/core.clj" content "defn")]
        (is (= 1 (count results)))
        (is (= 3 (:line-number (first results))))
        (is (= "src/core.clj" (:path (first results))))))

    (testing "returns empty on no match"
      (is (empty? (cache/grep-file "src/core.clj" content "zzz_nonexistent"))))

    (testing "returns empty on invalid regex"
      (is (empty? (cache/grep-file "src/core.clj" content "[invalid"))))))

(deftest format-grep-results-test
  (testing "formats as path:line:text"
    (is (= "a.clj:1:hello\nb.clj:5:world"
           (cache/format-grep-results
             [{:path "a.clj" :line-number 1 :text "hello"}
              {:path "b.clj" :line-number 5 :text "world"}])))))

;------------------------------------------------------------------------------ Layer 2
;; Tool handler tests

(deftest handle-context-read-cache-hit-test
  (testing "returns cached content"
    (swap! cache/cache-state assoc-in [:files "src/core.clj"] "(ns core)\n(defn hello [])")
    (let [result (cache/handle-context-read {"path" "src/core.clj"})]
      (is (= "(ns core)\n(defn hello [])" (get-in result [:content 0 :text])))
      (is (not (:isError result))))))

(deftest handle-context-read-offset-limit-test
  (testing "applies offset and limit on cache hit"
    (swap! cache/cache-state assoc-in [:files "src/core.clj"] "line0\nline1\nline2\nline3")
    (let [result (cache/handle-context-read {"path" "src/core.clj" "offset" 1 "limit" 2})]
      (is (= "line1\nline2" (get-in result [:content 0 :text]))))))

(deftest handle-context-read-cache-miss-test
  (testing "falls back to filesystem and records miss"
    (with-temp-dir
      (fn [dir]
        (let [file-path (str dir "/uncached.txt")]
          (spit file-path "filesystem content")
          (let [result (cache/handle-context-read {"path" file-path})]
            (is (= "filesystem content" (get-in result [:content 0 :text])))
            ;; Verify it was cached
            (is (= "filesystem content" (get-in @cache/cache-state [:files file-path])))
            ;; Verify miss was recorded
            (is (= 1 (count (:misses @cache/cache-state))))
            (is (= "context_read" (:tool (first (:misses @cache/cache-state)))))))))))

(deftest handle-context-read-nonexistent-test
  (testing "returns error for nonexistent file"
    (let [result (cache/handle-context-read {"path" "/nonexistent/path.clj"})]
      (is (:isError result))
      (is (re-find #"Error reading" (get-in result [:content 0 :text]))))))

(deftest handle-context-grep-cache-hit-test
  (testing "finds pattern in cached files"
    (swap! cache/cache-state assoc-in [:files "src/alpha.clj"]
           "(ns alpha)\n\n(defn greet [name]\n  (str name))")
    (swap! cache/cache-state assoc-in [:files "src/beta.clj"]
           "(ns beta)\n\n(defn process [x]\n  (inc x))")
    (let [result (cache/handle-context-grep {"pattern" "defn greet"})]
      (is (re-find #"alpha\.clj" (get-in result [:content 0 :text])))
      (is (re-find #"defn greet" (get-in result [:content 0 :text]))))))

(deftest handle-context-grep-specific-file-test
  (testing "path restricts search to one file"
    (swap! cache/cache-state assoc-in [:files "src/alpha.clj"]
           "(defn greet [name] name)")
    (swap! cache/cache-state assoc-in [:files "src/beta.clj"]
           "(defn greet [user] user)")
    (let [result (cache/handle-context-grep {"pattern" "defn" "path" "src/beta.clj"})]
      (is (re-find #"beta\.clj" (get-in result [:content 0 :text])))
      (is (not (re-find #"alpha\.clj" (get-in result [:content 0 :text])))))))

(deftest handle-context-grep-glob-filter-test
  (testing "glob filter restricts search"
    (swap! cache/cache-state assoc-in [:files "src/core.clj"] "(defn foo [])")
    (swap! cache/cache-state assoc-in [:files "test/core_test.clj"] "(deftest foo-test)")
    (let [result (cache/handle-context-grep {"pattern" "def" "glob" "test/*.clj"})]
      (is (re-find #"core_test\.clj" (get-in result [:content 0 :text])))
      (is (not (re-find #"src/" (get-in result [:content 0 :text])))))))

(deftest handle-context-grep-no-match-test
  (testing "returns no matches message"
    (swap! cache/cache-state assoc-in [:files "src/core.clj"] "(ns core)")
    (let [result (cache/handle-context-grep {"pattern" "zzz_nonexistent_zzz"})]
      (is (re-find #"[Nn]o matches" (get-in result [:content 0 :text]))))))

(deftest handle-context-glob-cached-paths-test
  (testing "matches cached file paths"
    (swap! cache/cache-state assoc-in [:files "src/alpha.clj"] "a")
    (swap! cache/cache-state assoc-in [:files "src/beta.clj"] "b")
    (swap! cache/cache-state assoc-in [:files "test/alpha_test.clj"] "t")
    (let [result (cache/handle-context-glob {"pattern" "src/*.clj"})
          text (get-in result [:content 0 :text])]
      (is (re-find #"alpha\.clj" text))
      (is (re-find #"beta\.clj" text))
      (is (not (re-find #"test/" text))))))

(deftest handle-context-glob-no-match-test
  (testing "returns no files matched message"
    (let [result (cache/handle-context-glob {"pattern" "nonexistent/**/*.xyz"})]
      (is (re-find #"[Nn]o files matched" (get-in result [:content 0 :text]))))))

;------------------------------------------------------------------------------ Layer 2
;; Lifecycle tests

(deftest load-cache-roundtrip-test
  (testing "load-cache! reads context-cache.edn and populates atom"
    (with-temp-dir
      (fn [dir]
        (spit (str dir "/context-cache.edn")
              (pr-str {:files {"src/a.clj" "(ns a)" "src/b.clj" "(ns b)"}}))
        (cache/load-cache! dir)
        (is (= "(ns a)" (get-in @cache/cache-state [:files "src/a.clj"])))
        (is (= "(ns b)" (get-in @cache/cache-state [:files "src/b.clj"])))))))

(deftest flush-misses-roundtrip-test
  (testing "flush-misses! writes misses to context-misses.edn"
    (with-temp-dir
      (fn [dir]
        (swap! cache/cache-state update :misses conj
               {:tool "context_read" :path "src/x.clj" :tokens 100
                :timestamp "2026-01-01T00:00:00Z"})
        (cache/flush-misses! dir)
        (let [misses (edn/read-string (slurp (str dir "/context-misses.edn")))]
          (is (= 1 (count misses)))
          (is (= "context_read" (:tool (first misses))))
          (is (= "src/x.clj" (:path (first misses)))))))))

(deftest flush-misses-noop-when-empty-test
  (testing "flush-misses! does not write file when no misses"
    (with-temp-dir
      (fn [dir]
        (cache/flush-misses! dir)
        (is (not (.exists (io/file (str dir "/context-misses.edn")))))))))
