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
(defn ^{:stratum 0} with-clean-cache [f]
  (cache/reset-state!)
  (try (f) (finally (cache/reset-state!))))

(defn ^{:stratum 0} with-temp-dir [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "ctx-cache-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq (io/file dir)))]
          (.delete ^java.io.File file))))))

;; Pure helper tests
(deftest ^{:stratum 0} estimate-tokens-test
  (testing "estimates ~4 chars per token"
    (is (= 3 (cache/estimate-tokens "hello world!")))
    (is (= 1 (cache/estimate-tokens "abc"))))

  (testing "handles edge cases"
    (is (= 0 (cache/estimate-tokens "")))
    (is (= 0 (cache/estimate-tokens nil)))
    (is (= 0 (cache/estimate-tokens 42)))))

(deftest ^{:stratum 0} apply-offset-limit-test
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

(deftest ^{:stratum 0} glob-matches?-test
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

(deftest ^{:stratum 0} grep-file-test
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

(deftest ^{:stratum 0} format-grep-results-test
  (testing "formats as path:line:text"
    (is (= "a.clj:1:hello\nb.clj:5:world"
           (cache/format-grep-results
             [{:path "a.clj" :line-number 1 :text "hello"}
              {:path "b.clj" :line-number 5 :text "world"}])))))

;; Tool handler tests
(deftest ^{:stratum 0} handle-context-read-cache-hit-test
  (testing "returns cached content"
    (swap! cache/cache-state assoc-in [:files "src/core.clj"] "(ns core)\n(defn hello [])")
    (let [result (cache/handle-context-read {"path" "src/core.clj"})]
      (is (= "(ns core)\n(defn hello [])" (get-in result [:content 0 :text])))
      (is (not (:isError result))))))

(deftest ^{:stratum 0} handle-context-read-offset-limit-test
  (testing "applies offset and limit on cache hit"
    (swap! cache/cache-state assoc-in [:files "src/core.clj"] "line0\nline1\nline2\nline3")
    (let [result (cache/handle-context-read {"path" "src/core.clj" "offset" 1 "limit" 2})]
      (is (= "line1\nline2" (get-in result [:content 0 :text]))))))

(deftest ^{:stratum 0} handle-context-read-nonexistent-test
  (testing "returns error for nonexistent file"
    (let [result (cache/handle-context-read {"path" "/nonexistent/path.clj"})]
      (is (:isError result))
      (is (re-find #"Error reading" (get-in result [:content 0 :text]))))))

(deftest ^{:stratum 0} handle-context-grep-cache-hit-test
  (testing "finds pattern in cached files"
    (swap! cache/cache-state assoc-in [:files "src/alpha.clj"]
           "(ns alpha)\n\n(defn greet [name]\n  (str name))")
    (swap! cache/cache-state assoc-in [:files "src/beta.clj"]
           "(ns beta)\n\n(defn process [x]\n  (inc x))")
    (let [result (cache/handle-context-grep {"pattern" "defn greet"})]
      (is (re-find #"alpha\.clj" (get-in result [:content 0 :text])))
      (is (re-find #"defn greet" (get-in result [:content 0 :text]))))))

(deftest ^{:stratum 0} handle-context-grep-specific-file-test
  (testing "path restricts search to one file"
    (swap! cache/cache-state assoc-in [:files "src/alpha.clj"]
           "(defn greet [name] name)")
    (swap! cache/cache-state assoc-in [:files "src/beta.clj"]
           "(defn greet [user] user)")
    (let [result (cache/handle-context-grep {"pattern" "defn" "path" "src/beta.clj"})]
      (is (re-find #"beta\.clj" (get-in result [:content 0 :text])))
      (is (not (re-find #"alpha\.clj" (get-in result [:content 0 :text])))))))

(deftest ^{:stratum 0} handle-context-grep-glob-filter-test
  (testing "glob filter restricts search"
    (swap! cache/cache-state assoc-in [:files "src/core.clj"] "(defn foo [])")
    (swap! cache/cache-state assoc-in [:files "test/core_test.clj"] "(deftest foo-test)")
    (let [result (cache/handle-context-grep {"pattern" "def" "glob" "test/*.clj"})]
      (is (re-find #"core_test\.clj" (get-in result [:content 0 :text])))
      (is (not (re-find #"src/" (get-in result [:content 0 :text])))))))

(deftest ^{:stratum 0} handle-context-grep-no-match-test
  (testing "returns no matches message"
    (swap! cache/cache-state assoc-in [:files "src/core.clj"] "(ns core)")
    (let [result (cache/handle-context-grep {"pattern" "zzz_nonexistent_zzz"})]
      (is (re-find #"[Nn]o matches" (get-in result [:content 0 :text]))))))

(deftest ^{:stratum 0} handle-context-glob-cached-paths-test
  (testing "matches cached file paths"
    (swap! cache/cache-state assoc-in [:files "src/alpha.clj"] "a")
    (swap! cache/cache-state assoc-in [:files "src/beta.clj"] "b")
    (swap! cache/cache-state assoc-in [:files "test/alpha_test.clj"] "t")
    (let [result (cache/handle-context-glob {"pattern" "src/*.clj"})
          text (get-in result [:content 0 :text])]
      (is (re-find #"alpha\.clj" text))
      (is (re-find #"beta\.clj" text))
      (is (not (re-find #"test/" text))))))

(deftest ^{:stratum 0} handle-context-glob-no-match-test
  (testing "returns no files matched message"
    (let [result (cache/handle-context-glob {"pattern" "nonexistent/**/*.xyz"})]
      (is (re-find #"[Nn]o files matched" (get-in result [:content 0 :text]))))))

;------------------------------------------------------------------------------ Write-invalidation + cross-phase persistence (Fable #4)
(defn- ^{:stratum 0} read-text [result]
  (get-in result [:content 0 :text]))

(deftest ^{:stratum 0} save-cache-noop-without-source-root-test
  (testing "save-cache! is a safe no-op when there's no source-root"
    (cache/reset-state!)
    (swap! cache/cache-state assoc :files {"x" "y"})
    (is (nil? (cache/save-cache!)) "no throw, nothing to persist without a worktree")))

(deftest ^{:stratum 0} handle-context-write-rejects-bad-input-test
  (cache/reset-state!)
  (is (:isError (cache/handle-context-write {"path" "" "content" "x"}))
      "blank path rejected")
  (is (:isError (cache/handle-context-write {"path" "f" "content" nil}))
      "nil content rejected"))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} handle-context-read-cache-miss-test
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

;; Lifecycle tests
(deftest ^{:stratum 1} load-cache-roundtrip-test
  (testing "load-cache! reads context-cache.edn and populates atom"
    (with-temp-dir
      (fn [dir]
        (spit (str dir "/context-cache.edn")
              (pr-str {:files {"src/a.clj" "(ns a)" "src/b.clj" "(ns b)"}}))
        (cache/load-cache! dir "/tmp/repo-root")
        (is (= "(ns a)" (get-in @cache/cache-state [:files "src/a.clj"])))
        (is (= "(ns b)" (get-in @cache/cache-state [:files "src/b.clj"])))
        (is (= "/tmp/repo-root" (:source-root @cache/cache-state)))))))

;; The submit/artifact.edn tests were removed with the submit tool — the
;; artifact is now the worktree/container diff (promotion), not an agent
;; metadata channel.
(deftest ^{:stratum 1} handle-context-read-source-root-fallback-test
  (testing "relative file reads resolve from source-root when cache is empty"
    (with-temp-dir
      (fn [dir]
        (let [repo-root (str dir "/repo")
              file-path (str repo-root "/components/demo/core.clj")]
          (.mkdirs (io/file (str repo-root "/components/demo")))
          (spit file-path "(ns demo.core)")
          (cache/load-cache! dir repo-root)
          (let [result (cache/handle-context-read {"path" "components/demo/core.clj"})]
            (is (= "(ns demo.core)" (get-in result [:content 0 :text])))
            (is (= "(ns demo.core)"
                   (get-in @cache/cache-state [:files "components/demo/core.clj"])))))))))

(deftest ^{:stratum 1} handle-context-glob-source-root-fallback-test
  (testing "relative glob fallback searches from source-root"
    (with-temp-dir
      (fn [dir]
        (let [repo-root (str dir "/repo")]
          (.mkdirs (io/file (str repo-root "/components/alpha/src/demo")))
          (spit (str repo-root "/components/alpha/src/demo/core.clj") "(ns demo.core)")
          (cache/load-cache! dir repo-root)
          (let [result (cache/handle-context-glob {"pattern" "components/*/src/**/*.clj"})]
            (is (re-find #"components/alpha/src/demo/core\.clj"
                         (get-in result [:content 0 :text])))))))))

(deftest ^{:stratum 1} handle-context-glob-source-root-fallback-prunes-git-test
  (testing "filesystem glob fallback ignores .git contents"
    (with-temp-dir
      (fn [dir]
        (let [repo-root (str dir "/repo")]
          (.mkdirs (io/file (str repo-root "/components/alpha/src/demo")))
          (.mkdirs (io/file (str repo-root "/.git/objects/aa")))
          (spit (str repo-root "/components/alpha/src/demo/core.clj") "(ns demo.core)")
          (spit (str repo-root "/.git/objects/aa/hidden.clj") "(ns hidden)")
          (cache/load-cache! dir repo-root)
          (let [result (cache/handle-context-glob {"pattern" "**/*.clj"})
                text (get-in result [:content 0 :text])]
            (is (re-find #"components/alpha/src/demo/core\.clj" text))
            (is (not (re-find #"\.git/objects/aa/hidden\.clj" text)))))))))

(deftest ^{:stratum 1} flush-misses-roundtrip-test
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

(deftest ^{:stratum 1} flush-misses-noop-when-empty-test
  (testing "flush-misses! does not write file when no misses"
    (with-temp-dir
      (fn [dir]
        (cache/flush-misses! dir)
        (is (not (.exists (io/file (str dir "/context-misses.edn")))))))))

(deftest ^{:stratum 1} read-through-invalidates-on-disk-change-test
  (testing "a cached read is re-read when the file changes on disk — no stale
            content after a write (the safety guard for forcing write-heavy
            agents onto context_read, and for cross-phase persistence)"
    (with-temp-dir
      (fn [dir]
        (let [f (io/file dir "src/a.clj")]
          (io/make-parents f)
          (spit f "V1")
          (cache/load-cache! dir dir)
          (is (= "V1" (read-text (cache/handle-context-read {"path" "src/a.clj"})))
              "first read caches V1")
          (spit f "V2")
          (is (.setLastModified f (+ (.lastModified f) 10000))
              "precondition: filesystem must honor setLastModified for this test")
          (is (= "V2" (read-text (cache/handle-context-read {"path" "src/a.clj"})))
              "second read returns fresh V2, not the stale cached V1"))))))

(deftest ^{:stratum 1} save-cache-persists-across-phases-test
  (testing "save-cache! writes the accumulated cache to <source-root>/.miniforge,
            and a fresh load (next phase) picks it up"
    (with-temp-dir
      (fn [dir]
        (let [f (io/file dir "src/b.clj")]
          (io/make-parents f)
          (spit f "BBB")
          (cache/load-cache! dir dir)
          (cache/handle-context-read {"path" "src/b.clj"})
          (cache/save-cache!)
          (is (.exists (io/file dir ".miniforge/context-cache.edn"))
              "persistent cache file written under .miniforge")
          ;; Simulate the next phase: new process state, same worktree.
          (cache/reset-state!)
          (cache/load-cache! (str dir "/no-prepop") dir)
          (is (= "BBB" (get-in @cache/cache-state [:files "src/b.clj"]))
              "the earlier phase's read is present in the next phase's cache")
          (is (contains? (:mtimes @cache/cache-state) "src/b.clj")
              "its mtime is restored so staleness is still detectable"))))))

;; context_write handler
(deftest ^{:stratum 1} handle-context-write-writes-to-workdir-and-caches-test
  (with-temp-dir
    (fn [dir]
      (cache/reset-state!)
      (cache/set-workdir! dir)
      (let [resp (cache/handle-context-write {"path" "src/new.clj" "content" "(ns new)"})]
        (is (not (:isError resp)) "write succeeds")
        (testing "file landed in the worktree (workdir), not source-root"
          (is (= "(ns new)" (slurp (io/file dir "src/new.clj")))))
        (testing "read-after-write coherence — context_read returns the new content"
          (let [read-resp (cache/handle-context-read {"path" "src/new.clj"})]
            (is (= "(ns new)" (-> read-resp :content first :text)))))))))

(deftest ^{:stratum 1} handle-context-write-overwrites-existing-test
  (with-temp-dir
    (fn [dir]
      (cache/reset-state!)
      (cache/set-workdir! dir)
      (spit (io/file dir "a.clj") "(ns a)")
      (let [resp (cache/handle-context-write {"path" "a.clj" "content" "(ns a-v2)"})]
        (is (not (:isError resp)))
        (is (= "(ns a-v2)" (slurp (io/file dir "a.clj"))))
        (is (= "(ns a-v2)" (-> (cache/handle-context-read {"path" "a.clj"}) :content first :text))
            "an existing file is edited and the cache reflects it")))))

(deftest ^{:stratum 1} handle-context-write-rejects-path-escapes-test
  (with-temp-dir
    (fn [dir]
      (cache/reset-state!)
      (cache/set-workdir! dir)
      (testing "`..` traversal escaping the worktree is refused"
        (is (:isError (cache/handle-context-write
                       {"path" "../escape.clj" "content" "x"})))
        (is (not (.exists (io/file dir ".." "escape.clj")))))
      (testing "absolute paths are refused"
        (is (:isError (cache/handle-context-write
                       {"path" "/tmp/abs-escape.clj" "content" "x"})))
        (is (not (.exists (io/file "/tmp/abs-escape.clj"))))))))

(deftest ^{:stratum 1} context-reads-are-recorded-hits-and-misses-test
  (cache/reset-state!)
  ;; A virtual pin path: present in the cache, no file on disk. cache-stale?
  ;; never fires for it, so it resolves as a :cache hit forever — the pin
  ;; primitive the Codex SPEC §7.4 blackboard pin relies on.
  (swap! cache/cache-state assoc-in
         [:files ".miniforge/codex-consider.md"] "pinned landings")
  (cache/handle-context-read {"path" ".miniforge/codex-consider.md"})
  (cache/handle-context-read {"path" "no/such/file.txt"})
  (let [reads (:reads @cache/cache-state)]
    (is (= [{:path ".miniforge/codex-consider.md" :source :cache}
            {:path "no/such/file.txt" :source :absent}]
           (mapv #(select-keys % [:path :source]) reads))
        "every resolution recorded, hit and miss (§7.4.2)")
    (is (every? :timestamp reads)))
  (testing "flush writes context-reads.edn"
    (with-temp-dir
      (fn [dir]
        (cache/flush-reads! dir)
        (let [f (io/file (str dir "/context-reads.edn"))]
          (is (.exists f))
          (is (= 2 (count (edn/read-string (slurp f))))))))))

(use-fixtures :each with-clean-cache)
