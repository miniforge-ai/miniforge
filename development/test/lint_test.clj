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
(ns lint-test
  "Pins `lint/stratum-staged`'s merge-commit skip. Mid-merge the staged
   set includes every file arriving from the merged-in branch — files
   the merging author did not touch, already stratum-linted on their
   own PRs, and possibly mid-way through a namespace split on main
   (legitimately SL003 there). The gate must skip before listing the
   staged files at all, and must keep dispatching normally outside a
   merge. Merge *detection* is pinned by `commit-budget-test` against a
   real linked-worktree merge; this namespace pins the gate's use of it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [commit-budget]
            [lint]
            [stratum]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ^:private unlisted-staged-by-ext
  "A `staged-by-ext` stand-in that fails if anything calls it.

   Mid-merge the gate has to return before listing staged files, not
   merely ignore the listing: an incoming file must never be read,
   autofixed, or re-staged by a gate that has declined to judge the
   merge. Throwing also means a lost skip surfaces here as this error
   rather than as a real `bb -Sdeps` linter run inside the test suite."
  [_ext]
  (throw (ex-info "staged-by-ext called while a merge was in progress" {})))

(def ^{:stratum 0} ^:private staged-clj-file
  "One fully-staged .clj file — the ordinary, non-merge commit."
  "components/codex/src/ai/miniforge/codex/core.clj")

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} merge-skips-stratum-lint-test
  (testing "mid-merge the gate skips before listing staged files at all"
    (let [out (with-redefs [commit-budget/merge-in-progress? (constantly true)
                            lint/staged-by-ext unlisted-staged-by-ext]
                (with-out-str (lint/stratum-staged)))]
      (is (str/includes? out "skipped (merge in progress)"))
      (is (not (str/includes? out "Stratum-linting"))))))

(deftest ^{:stratum 1} non-merge-commit-still-stratum-linted-test
  (testing "outside a merge, fully-staged files still reach autofix"
    (let [calls (atom [])]
      (with-redefs [commit-budget/merge-in-progress? (constantly false)
                    lint/staged-by-ext (fn [ext]
                                         (if (= ext ".clj") [staged-clj-file] []))
                    lint/unstaged-files (constantly #{})
                    stratum/autofix-and-restage! (fn [files]
                                                   (swap! calls conj [:autofix files]))
                    stratum/lint-only-and-fail! (fn [files]
                                                  (swap! calls conj [:lint-only files]))]
        (let [out (with-out-str (lint/stratum-staged))]
          (is (str/includes? out "Stratum-linting 1 Clojure file(s)"))
          (is (not (str/includes? out "skipped")))
          (is (= [[:autofix [staged-clj-file]]] @calls)))))))
