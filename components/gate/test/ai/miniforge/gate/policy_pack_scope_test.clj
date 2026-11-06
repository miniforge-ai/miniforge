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
(ns ai.miniforge.gate.policy-pack-scope-test
  "The policy gate blocks on judge findings inside the top-level forms a
   change edits, and records findings elsewhere in the file as warnings."
  (:require
   [ai.miniforge.gate.policy-pack :as sut]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private path
  "The changed file every test judges."
  "src/demo/core.clj")

(def ^{:stratum 0} ^:private base-content
  "The file at the task base: form A on lines 3-6, form B on lines 8-10."
  (str "(ns demo.core)\n"
       "\n"
       "(defn a\n"
       "  []\n"
       "  ;; tmp\n"
       "  1)\n"
       "\n"
       "(defn b\n"
       "  []\n"
       "  2)\n"))

(def ^{:stratum 0} ^:private judge-rule
  "An acting rule with no :custom-fn, so the LLM judge evaluates it."
  {:rule/id          :test/judge
   :rule/enabled?    true
   :rule/severity    :high
   :rule/applies-to  {:phases #{:verify}}
   :rule/detection   {:type :custom}
   :rule/enforcement {:action :hard-halt :message "judge finding"}})

(defn- ^{:stratum 0} git!
  "Run git in DIR with no inherited GIT_* variables, so a test run from a
   hook cannot write to the enclosing repository."
  [dir & args]
  (apply shell/sh "git" "-C" (str dir)
         (concat args [:env (into {} (remove #(str/starts-with? (key %) "GIT_")) (System/getenv))])))

(defn- ^{:stratum 0} findings-at
  "An analyze fn whose judge reports one finding in FILE per entry of
   LINES (nil for a finding with no line)."
  [file lines]
  (fn [_llm-client _complete-fn _repo-path rule]
    {:rule/id    (:rule/id rule)
     :status     :completed
     :violations (mapv #(cond-> {:file file :message "finding"} % (assoc :line %)) lines)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} base-worktree
  "A temp git worktree with `path` committed as BASE, or with only a README
   when BASE is nil (so `path` is new). Returns [dir sha]."
  [base]
  (let [dir  (.toFile (java.nio.file.Files/createTempDirectory
                       "policy-scope" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file dir (if base path "README.md"))]
    (.mkdirs (.getParentFile file))
    (spit file (or base "base"))
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "-c" "user.email=test@test" "-c" "user.name=test" "-c" "commit.gpgsign=false"
          "commit" "-q" "-m" "base")
    [dir (str/trim (:out (git! dir "rev-parse" "HEAD")))]))

(def ^{:stratum 1} ^:private edit-a
  "Changes form A's body only."
  (str/replace base-content "  1)" "  10)"))

(defn- ^{:stratum 1} check
  "The verify policy gate on CONTENT as the change to `path` in REPO
   ([dir base-sha] from `base-worktree`, deleted afterwards), with the
   judge reporting at LINES."
  [[dir sha] {:keys [content worktree?] :or {worktree? true}} & lines]
  (let [artifact {:code/files     [{:path path :content content :action :modify}]
                  :code/base-refs [sha]}
        ctx      (cond-> {:policy-packs        [{:pack/id "scope-test" :pack/rules [judge-rule]}]
                          :llm-backend         :fake-client
                          :semantic-analyze-fn (findings-at path lines)}
                   worktree? (assoc :execution/worktree-path (str dir)))]
    (try
      (sut/check-policy-verify artifact ctx)
      (finally (fs/delete-tree dir)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} finding-outside-the-edited-form-warns-test
  (let [result (check (base-worktree base-content) {:content edit-a} 9)]
    (is (:passed? result))
    (is (empty? (:errors result)))
    (is (= [:test/judge] (map :code (:warnings result))))
    (is (str/includes? (-> result :warnings first :message) (str path ":9")))))

(deftest ^{:stratum 2} finding-inside-the-edited-form-blocks-test
  (let [result (check (base-worktree base-content) {:content edit-a} 4 9)]
    (is (not (:passed? result)))
    (is (= [:test/judge] (map :code (:errors result))))
    (testing "the blocking error names only the finding inside the edit"
      (is (= [4] (map :line (-> result :errors first :location :matches)))))))

(deftest ^{:stratum 2} finding-on-an-added-line-between-forms-blocks-test
  (let [content (str/replace base-content "\n\n(defn b" "\n;; added\n\n(defn b")]
    (is (not (:passed? (check (base-worktree base-content) {:content content} 7))))))

(deftest ^{:stratum 2} removing-lines-inside-a-form-edits-that-form-test
  (let [content (str/replace base-content "  ;; tmp\n" "")]
    (is (not (:passed? (check (base-worktree base-content) {:content content} 4))))
    (is (:passed? (check (base-worktree base-content) {:content content} 8)))))

(deftest ^{:stratum 2} removing-metadata-above-a-form-edits-that-form-test
  (let [base    (str/replace base-content "(defn b" "^:private\n(defn b")
        content base-content]
    (is (not (:passed? (check (base-worktree base) {:content content} 9))))))

(deftest ^{:stratum 2} replacing-a-line-and-removing-the-next-prefix-edits-both-forms-test
  (let [base    "(ns demo.core)\n(def a 1)\n^:private\n(defn b\n  []\n  2)\n"
        content "(ns demo.core)\n(def a 2)\n(defn b\n  []\n  2)\n"]
    (is (not (:passed? (check (base-worktree base) {:content content} 4))))))

(deftest ^{:stratum 2} replacing-the-line-above-a-form-leaves-it-unedited-test
  (let [base    "(ns demo.core)\n(def a 1)\n(defn b\n  []\n  2)\n"
        content "(ns demo.core)\n(def a 2)\n(defn b\n  []\n  2)\n"]
    (is (:passed? (check (base-worktree base) {:content content} 4)))))

(deftest ^{:stratum 2} unwrapping-a-comment-form-edits-the-function-inside-test
  (let [base    "(ns demo.core)\n(comment\n  (defn f\n    []\n    1)\n  )\n"
        content "(ns demo.core)\n  (defn f\n    []\n    1)\n"]
    (is (not (:passed? (check (base-worktree base) {:content content} 3))))))

(deftest ^{:stratum 2} a-copy-inserted-above-its-original-is-the-one-in-scope-test
  (let [base    "(ns demo.core)\n(def a 1)\n(def b 2)\n"
        content "(ns demo.core)\n(def b 2)\n(def a 1)\n(def b 2)\n"]
    (is (not (:passed? (check (base-worktree base) {:content content} 2))) "the copy")
    (is (:passed? (check (base-worktree base) {:content content} 4)) "the original")))

(deftest ^{:stratum 2} duplicate-literal-content-blocks-test
  (let [content (str/replace edit-a "  10)" "  {:k 1 :k 2})")]
    (is (not (:passed? (check (base-worktree base-content) {:content content} 9))))))

(deftest ^{:stratum 2} new-file-blocks-on-any-line-test
  (is (not (:passed? (check (base-worktree nil) {:content base-content} 9)))))

(deftest ^{:stratum 2} unplaceable-findings-block-test
  (testing "a finding with no line, line 0, or a line past the file's last"
    (is (not (:passed? (check (base-worktree base-content) {:content edit-a} nil))))
    (is (not (:passed? (check (base-worktree base-content) {:content edit-a} 0))))
    (is (not (:passed? (check (base-worktree base-content) {:content edit-a} 11)))))
  (testing "no worktree to diff against"
    (is (not (:passed? (check (base-worktree base-content) {:content edit-a :worktree? false} 9)))))
  (testing "content that does not parse"
    (is (not (:passed? (check (base-worktree base-content) {:content (str edit-a "(defn c\n")} 9))))))
