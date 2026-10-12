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
(ns ai.miniforge.policy-pack.path-scope-test
  "Tests for a rule's path scope — `:file-globs` minus `:exclude-globs`.

   `:exclude-globs` carries a carve-out a rule would otherwise only be able
   to state in prose (`excludeGlobs` in MDC frontmatter). The thing worth
   testing is not the glob matching but the agreement: `path-in-scope?`,
   `rule-applies-to-artifact?` and `rule-applies?` are three entry points
   into the same question, and an exclusion honoured by some of them and
   not others is a worse failure than one honoured by none."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.policy-pack.applicability :as applicability]
   [ai.miniforge.policy-pack.registry.support :as support]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private sh-rule-applies-to
  "The shape `workflows/bb-over-shell` compiles to: broad include, one
   carve-out for the fixture tree it must read past."
  {:file-globs    ["bb.edn" "**/*.sh" "scripts/**" "tools/**"]
   :exclude-globs ["examples/**"]})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} path-in-scope-exclusion-beats-include-test
  (testing "an excluded path is out of scope even though an include matches"
    (are [path in-scope?] (= in-scope? (support/path-in-scope? sh-rule-applies-to path))
      "scripts/build.sh"                        true
      "bb.edn"                                  true
      "tools/strata_audit.bb"                   true
      "examples/demo/run-demo.sh"               false
      "examples/demo-polyglot-repo/run-demo.sh" false
      "examples/demo-polyglot-repo/scripts/setup.sh" false
      "src/main.clj"                            false))

  (testing "exclusion applies when :file-globs is absent — a rule scoped by
            alwaysApply alone is still path-tested"
    (is (true?  (support/path-in-scope? {:exclude-globs ["examples/**"]} "src/main.clj")))
    (is (false? (support/path-in-scope? {:exclude-globs ["examples/**"]} "examples/a.sh"))))

  (testing "no path constraints at all → everything is in scope (unchanged)"
    (is (true? (support/path-in-scope? nil "anything.txt")))
    (is (true? (support/path-in-scope? {} "anything.txt")))
    (is (true? (support/path-in-scope? {:file-globs [] :exclude-globs []} "anything.txt"))))

  (testing "nil path is treated as \"\" rather than throwing"
    (is (true?  (support/path-in-scope? {} nil)))
    (is (false? (support/path-in-scope? {:file-globs ["**/*.sh"]} nil)))))

(deftest ^{:stratum 1} rule-applies-to-artifact-honours-exclusion-test
  (let [rule {:rule/id :std/bb-over-shell :rule/applies-to sh-rule-applies-to}]
    (testing "an in-scope artifact still applies"
      (is (true? (applicability/rule-applies-to-artifact?
                  rule {:artifact/path "scripts/build.sh"}))))

    (testing "an excluded artifact does not"
      (is (false? (applicability/rule-applies-to-artifact?
                   rule {:artifact/path "examples/demo/run-demo.sh"}))))

    (testing "a missing artifact path does not throw"
      (is (false? (applicability/rule-applies-to-artifact? rule nil))))))

(deftest ^{:stratum 1} filter-applicable-rules-drops-excluded-path-test
  (testing "the gate's rule filter drops a rule excluded from this path"
    (let [rule  {:rule/id         :std/bb-over-shell
                 :rule/applies-to (assoc sh-rule-applies-to
                                         :phases #{:implement})}
          ctx-in  {:artifact {:artifact/path "scripts/build.sh"} :phase :implement}
          ctx-out {:artifact {:artifact/path "examples/demo/run-demo.sh"} :phase :implement}]
      (is (= [:std/bb-over-shell]
             (mapv :rule/id (applicability/filter-applicable-rules [rule] ctx-in))))
      (is (= []
             (mapv :rule/id (applicability/filter-applicable-rules [rule] ctx-out)))))))

(deftest ^{:stratum 1} rule-applies-honours-exclusion-test
  (let [rule {:rule/id :std/bb-over-shell :rule/applies-to sh-rule-applies-to}]
    (testing "the registry's filter agrees with the applicability filter"
      (is (true?  (support/rule-applies? rule {:artifact {:artifact/path "scripts/build.sh"}})))
      (is (false? (support/rule-applies? rule {:artifact {:artifact/path "examples/demo/run-demo.sh"}}))))

    (testing "the other applies-to filters still compose with the path scope"
      (let [scoped (assoc-in rule [:rule/applies-to :phases] #{:implement})]
        (is (true?  (support/rule-applies? scoped {:artifact {:artifact/path "scripts/build.sh"}
                                                   :phase :implement})))
        (is (false? (support/rule-applies? scoped {:artifact {:artifact/path "scripts/build.sh"}
                                                   :phase :release})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.policy-pack.path-scope-test)
  :leave-this-here)
