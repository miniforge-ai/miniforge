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
(ns ai.miniforge.spec-parser.documented-markdown-specs-test
  "The Markdown spec examples the docs publish parse with their
   description, intent and scope intact. Reads the real files, so an edit
   to an example that the parser cannot handle fails here."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.spec-parser.interface :as spec-parser]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} repo-file
  "Absolute path of `rel` in the repo, found by walking up from the working
   directory (the workspace root for `poly test`; lower for a brick-local run)."
  [rel]
  (some (fn [dir]
          (let [p (fs/path dir rel)]
            (when (fs/exists? p) (str p))))
        (take-while some? (iterate fs/parent (fs/absolutize ".")))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fenced-markdown-example
  "Content of the first ```markdown fenced block in the repo file `rel`."
  [rel]
  (some->> (repo-file rel)
           slurp
           (re-find #"(?s)```markdown\n(.*?)\n```")
           second))

(deftest ^{:stratum 1} fix-bug-example-spec-file-test
  (let [path (repo-file "examples/workflows/fix-bug.md")
        spec (spec-parser/parse-spec-file path)]
    (testing "intent type and scope survive"
      (is (= {:type :bugfix
              :scope ["components/auth/src" "components/middleware/src"]}
             (:spec/intent spec))))
    (testing "flow-list tags are strings, not symbols"
      (is (= ["bugfix" "authentication" "security"] (:spec/tags spec))))
    (testing "the parsed spec validates"
      (is (true? (:valid? (spec-parser/validate-spec spec)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} quickstart-markdown-spec-test
  (let [example (fenced-markdown-example "docs/quickstart.md")
        spec    (spec-parser/normalize-spec (spec-parser/parse-content :markdown example))]
    (is (some? example) "docs/quickstart.md has a ```markdown spec example")
    (testing "the `description: |` block reaches :spec/description, followed by the body"
      (is (str/starts-with? (:spec/description spec)
                            "The login form times out after 5 seconds on slow connections."))
      (is (str/includes? (:spec/description spec)
                         "add a loading indicator.\n\n## Context")))
    (testing "the nested intent survives with a keyword :type"
      (is (= {:type :bugfix} (:spec/intent spec))))
    (testing "the parsed spec validates"
      (is (true? (:valid? (spec-parser/validate-spec spec)))))))

(deftest ^{:stratum 2} writing-specs-markdown-spec-test
  (let [example (fenced-markdown-example "docs/user-guide/writing-specs.md")
        spec    (spec-parser/normalize-spec (spec-parser/parse-content :markdown example))]
    (is (some? example) "docs/user-guide/writing-specs.md has a ```markdown spec example")
    (testing "the `description: |` block reaches :spec/description"
      (is (str/starts-with? (:spec/description spec)
                            "Implement a password reset flow with email verification.")))
    (testing "intent type and scope survive"
      (is (= {:type :feature :scope ["src/auth/"]} (:spec/intent spec))))
    (testing "the parsed spec validates"
      (is (true? (:valid? (spec-parser/validate-spec spec)))))))
