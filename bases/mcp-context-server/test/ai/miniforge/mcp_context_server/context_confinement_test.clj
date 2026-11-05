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
(ns ai.miniforge.mcp-context-server.context-confinement-test
  "context_read and context_grep stay inside source-root, and nothing an
   agent passes to context_grep reaches ripgrep as a flag."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.mcp-context-server.context-cache :as cache]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-clean-cache [f]
  (cache/reset-state!)
  (try (f) (finally (cache/reset-state!))))

(defn- ^{:stratum 0} temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "ctx-confinement-test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ^{:stratum 0} response-text [result]
  (get-in result [:content 0 :text]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} repo-with-outside
  "A source root holding src/a.clj, and a sibling directory outside it
   holding secret.txt. Points the cache at the source root."
  []
  (let [base (temp-dir)
        repo (io/file base "repo")
        outside (io/file base "outside")]
    (.mkdirs (io/file repo "src"))
    (.mkdirs outside)
    (spit (io/file repo "src" "a.clj") "(ns a) ;; needle")
    (spit (io/file outside "secret.txt") "needle in the secret")
    (swap! cache/cache-state assoc :source-root (str repo))
    {:repo repo :outside outside}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} context-read-refuses-paths-outside-source-root-test
  (let [{:keys [repo outside]} (repo-with-outside)]
    (testing "an absolute path elsewhere is refused and not cached"
      (let [result (cache/handle-context-read {"path" (str (io/file outside "secret.txt"))})]
        (is (:isError result))
        (is (not (str/includes? (str (response-text result)) "needle in the secret")))
        (is (empty? (:files @cache/cache-state)))))
    (testing "a relative path that climbs out with .. is refused"
      (is (:isError (cache/handle-context-read {"path" "../outside/secret.txt"}))))
    (testing "a symlink inside the repo pointing out is refused"
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file repo "link.txt")) (.toPath (io/file outside "secret.txt"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (is (:isError (cache/handle-context-read {"path" "link.txt"}))))
    (testing "files inside the source root still read, by relative or absolute path"
      (is (= "(ns a) ;; needle" (response-text (cache/handle-context-read {"path" "src/a.clj"}))))
      (is (= "(ns a) ;; needle"
             (response-text (cache/handle-context-read {"path" (str (io/file repo "src" "a.clj"))})))))))

(deftest ^{:stratum 2} context-grep-pattern-is-never-an-rg-flag-test
  (testing "a pattern that looks like an rg option is searched for as text"
    (let [{:keys [repo]} (repo-with-outside)
          marker (io/file repo "ran")
          script (io/file repo "pre.sh")]
      (spit (io/file repo "src" "flags.txt") "usage: --pre=never-run --files\n")
      (spit script (str "#!/bin/sh\ntouch '" marker "'\ncat \"$1\"\n"))
      (.setExecutable script true)
      (is (str/includes? (str (response-text (cache/handle-context-grep {"pattern" "--files"
                                                                         "path" "src"})))
                         "flags.txt")
          "rg must take --files as the pattern, not as an option")
      (cache/handle-context-grep {"pattern" (str "--pre=" script) "path" "src"})
      (is (not (.exists marker)) "ripgrep must not run a --pre program"))))

(deftest ^{:stratum 2} context-grep-refuses-paths-outside-source-root-test
  (let [{:keys [outside]} (repo-with-outside)]
    (testing "an absolute path elsewhere finds nothing"
      (let [text (response-text (cache/handle-context-grep {"pattern" "needle"
                                                            "path" (str outside)}))]
        (is (not (str/includes? (str text) "needle in the secret")))))
    (testing "a .. path finds nothing"
      (let [text (response-text (cache/handle-context-grep {"pattern" "needle"
                                                            "path" "../outside"}))]
        (is (not (str/includes? (str text) "needle in the secret")))))
    (testing "a path inside the root still searches"
      (is (str/includes? (str (response-text (cache/handle-context-grep {"pattern" "needle"
                                                                         "path" "src"})))
                         "a.clj")))))

(deftest ^{:stratum 2} context-grep-glob-is-a-glob-test
  (testing "the glob parameter filters files rather than being taken as a path"
    (let [{:keys [repo]} (repo-with-outside)]
      (spit (io/file repo "src" "b.md") "needle in markdown")
      (let [text (str (response-text (cache/handle-context-grep {"pattern" "needle"
                                                                 "glob" "**/*.clj"})))]
        (is (str/includes? text "a.clj"))
        (is (not (str/includes? text "b.md")))))))

(deftest ^{:stratum 2} context-grep-without-a-path-searches-the-source-root-test
  (testing "an uncached file is found when no path is given"
    (repo-with-outside)
    (let [text (str (response-text (cache/handle-context-grep {"pattern" "needle"})))]
      (is (str/includes? text "src/a.clj"))
      (is (not (str/includes? text "./src/a.clj")) "paths match the cache's relative keys")
      (is (contains? (:files @cache/cache-state) "src/a.clj")))))

(deftest ^{:stratum 2} persisted-outside-entries-are-not-served-test
  (testing "an outside-root entry in a cache persisted before confinement is dropped on load"
    (let [{:keys [repo outside]} (repo-with-outside)
          secret-path (str (io/file outside "secret.txt"))
          artifact-dir (temp-dir)]
      (.mkdirs (io/file repo ".miniforge"))
      (spit (io/file repo ".miniforge" "context-cache.edn")
            (pr-str {:files {secret-path "needle in the secret"
                             "src/a.clj" "(ns a) ;; needle"}
                     :mtimes {}}))
      (cache/load-cache! (str artifact-dir) (str repo))
      (is (= #{"src/a.clj"} (set (keys (:files @cache/cache-state)))))
      (is (:isError (cache/handle-context-read {"path" secret-path})))
      (is (not (str/includes? (str (response-text (cache/handle-context-grep {"pattern" "needle"})))
                              "secret")))
      (is (not (str/includes? (str (response-text (cache/handle-context-grep {"pattern" "needle"
                                                                              "path" secret-path})))
                              "needle in the secret")))))

  (testing "an outside entry already in memory is not served by read or a path-scoped grep"
    (let [{:keys [outside]} (repo-with-outside)
          secret-path (str (io/file outside "secret.txt"))]
      (swap! cache/cache-state assoc-in [:files secret-path] "needle in the secret")
      (is (:isError (cache/handle-context-read {"path" secret-path})))
      (is (not (str/includes? (str (response-text (cache/handle-context-grep {"pattern" "needle"
                                                                              "path" secret-path})))
                              "needle in the secret"))))))

(use-fixtures :each with-clean-cache)
