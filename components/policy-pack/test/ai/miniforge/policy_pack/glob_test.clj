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
(ns ai.miniforge.policy-pack.glob-test
  "Tests for glob translation and matching.

   Two jobs here. The first is the syntax table `meta/rule-format`
   documents for `globs` / `excludeGlobs` — it advertises brace globs, so
   they have to work. The second is agreement with
   `java.nio.file.PathMatcher`, which is the reference implementation most
   readers will reach for, with the one deliberate divergence pinned
   rather than left to be rediscovered."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.policy-pack.glob :as sut])
  (:import [java.nio.file FileSystems Paths]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} nio-matches?
  "The java.nio reference answer for a pattern/path pair."
  [pattern path]
  (.matches (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))
            (Paths/get path (into-array String []))))

(deftest ^{:stratum 0} glob-syntax-test
  (testing "the syntax meta/rule-format documents"
    (are [pattern path expected] (= expected (sut/matches? pattern path))
      ;; literal
      "bb.edn"                   "bb.edn"            true
      "bb.edn"                   "sub/bb.edn"        false
      ;; * stays inside one segment
      "src/*.clj"                "src/a.clj"         true
      "src/*.clj"                "src/a/b.clj"       false
      ;; ? is exactly one character, inside one segment
      "src/?.clj"                "src/a.clj"         true
      "src/?.clj"                "src/ab.clj"        false
      "src/?.clj"                "src/a/b.clj"       false
      ;; ** crosses segments
      "tools/**"                 "tools/a/b.clj"     true
      "examples/**"              "examples/d/x.sh"   true
      "examples/**"              "src/x.sh"          false
      ;; brace alternatives — advertised by rule-format
      "**/*.{js,ts}"             "src/a.ts"          true
      "**/*.{js,ts}"             "src/a.js"          true
      "**/*.{js,ts}"             "src/a.rb"          false
      "src/**/*.{js,jsx,ts,tsx}" "src/a/b.tsx"       true
      ;; character classes, with negation
      "[abc]/x"                  "a/x"               true
      "[abc]/x"                  "d/x"               false
      "[!abc]/x"                 "d/x"               true
      "[!abc]/x"                 "a/x"               false))

  (testing "no wildcard crosses a segment boundary. A negated class is the
            one that nearly did: `[^abc]` in a regex matches `/`, so
            `src[!x]foo` would have matched `src/foo`"
    (are [pattern path expected] (= expected (sut/matches? pattern path))
      "src[!x]foo"  "src/foo"  false
      "src[!x]foo"  "srcyfoo"  true
      "src[!x]foo"  "srcxfoo"  false
      ;; a `/` listed inside a positive class is stripped, so the class
      ;; cannot match a separator either. PathMatcher rejects the pattern
      ;; outright; matching nothing is the same outcome without a throw.
      "a[b/c]d"     "a/d"      false
      "a[bc]d"      "abd"      true
      ;; `*` and `?` were already segment-bound
      "src/*"       "src/a/b"  false
      "src/?"       "src/ab"   false))

  (testing "inside a class every character is a literal. `&&` is Java's
            intersection operator but two ordinary characters in a glob —
            unescaped, `[a&&b]` compiles to an empty intersection and matches
            nothing, which in an exclusion fails open"
    (are [pattern path expected] (= expected (sut/matches? pattern path))
      "[a&&b]"  "a"  true
      "[a&&b]"  "&"  true
      "[a&&b]"  "b"  true
      "[a&&b]"  "c"  false
      "[a&b]"   "&"  true))

  (testing "a dot is literal, not the regex wildcard"
    (is (false? (sut/matches? "src/*.clj" "src/aXclj"))))

  (testing "an uncompilable pattern matches nothing rather than throwing"
    (is (false? (sut/matches? "[" "a")))
    (is (false? (sut/matches? "{" "a"))))

  (testing "nil path is handled as a string"
    (is (false? (sut/matches? "*.clj" nil)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} agrees-with-path-matcher-test
  (testing "on every form both implementations support, the answers match"
    (doseq [pattern ["bb.edn" "src/*.clj" "src/?.clj" "src/*" "src/?" "tools/**"
                     "examples/**" "**/*.{js,ts}" "src/**/*.{js,jsx,ts,tsx}"
                     "[abc]/x" "[!abc]/x" "src[!x]foo" "[a&&b]" "[a&b]"
                     "docs/**/*.md" "components/**/src/**/*.clj"]
            path    ["bb.edn" "src/a.clj" "src/ab.clj" "src/a/b.clj" "src/a/b"
                     "tools/a/b.clj" "examples/d/x.sh" "src/a.ts" "src/a.js"
                     "src/a/b.tsx" "a/x" "d/x" "src/foo" "srcyfoo" "srcxfoo"
                     "a" "b" "c" "&"
                     "docs/a/b.md" "components/c/src/a/b.clj" "build.clj"]
            ;; `**/` anywhere in the pattern is the one deliberate
            ;; divergence — covered below.
            :when   (not (str/includes? pattern "**/"))]
      (is (= (nio-matches? pattern path) (sut/matches? pattern path))
          (str pattern " vs " path))))

  (testing "`**/` stands for ZERO or more segments here, and one or more under
            PathMatcher, which requires the separator. That is the one
            deliberate divergence: under PathMatcher a `**/*.clj` rule silently
            drops every root-level file. `meta/rule-format` states the
            zero-segment reading"
    (testing "at the start of a pattern"
      (is (true?  (sut/matches? "**/*.clj" "build.clj")))
      (is (false? (nio-matches? "**/*.clj" "build.clj"))))

    (testing "and in the middle, which is where it bites most often —
              `components/**/src/**/*.clj` is meant to cover a component
              whose src sits directly under it"
      (is (true?  (sut/matches? "src/**/*.ts" "src/a.ts")))
      (is (false? (nio-matches? "src/**/*.ts" "src/a.ts"))))

    (testing "both agree once the path has the segment"
      (is (true? (sut/matches? "**/*.clj" "a/build.clj")))
      (is (true? (nio-matches? "**/*.clj" "a/build.clj")))
      (is (true? (sut/matches? "src/**/*.ts" "src/a/b.ts")))
      (is (true? (nio-matches? "src/**/*.ts" "src/a/b.ts"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.policy-pack.glob-test)
  :leave-this-here)
