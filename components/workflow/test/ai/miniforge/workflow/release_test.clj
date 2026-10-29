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
(ns ai.miniforge.workflow.release-test
  "Unit tests for release helpers that do not require git."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.release :as release]
   [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:dynamic *temp-dirs* (atom []))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-temp-dir
  []
  (let [dir (fs/create-temp-dir {:prefix "release-unit-"})]
    (swap! *temp-dirs* conj dir)
    dir))

(defn ^{:stratum 1} cleanup-temp-dirs
  [f]
  (reset! *temp-dirs* [])
  (try
    (f)
    (finally
      (doseq [dir @*temp-dirs*]
        (when (fs/exists? dir)
          (fs/delete-tree dir))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} ensure-parent-dir-test
  (testing "ensure-parent-dir! creates nested directories"
    (let [temp-dir (create-temp-dir)
          test-path (fs/path temp-dir "foo" "bar" "baz.clj")]
      (release/ensure-parent-dir! test-path)
      (is (fs/exists? (fs/path temp-dir "foo" "bar"))))))

(deftest ^{:stratum 2} write-file-test
  (testing "write-file! creates parent directories"
    (let [temp-dir (create-temp-dir)
          file-path (fs/path temp-dir "src" "example.clj")
          content "(ns example)\n(defn hello [] \"world\")"]
      (release/write-file! file-path content)
      (is (fs/exists? file-path))
      (is (= content (slurp (str file-path)))))))

(deftest ^{:stratum 2} delete-file-test
  (testing "delete-file! removes existing files"
    (let [temp-dir (create-temp-dir)
          file-path (fs/path temp-dir "delete-me.clj")]
      (spit (str file-path) "temp")
      (is (true? (release/delete-file! file-path)))
      (is (false? (fs/exists? file-path)))))

  (testing "delete-file! returns false for missing files"
    (let [temp-dir (create-temp-dir)
          file-path (fs/path temp-dir "missing.clj")]
      (is (false? (release/delete-file! file-path))))))

(deftest ^{:stratum 2} process-file-action-path-traversal-test
  (testing "create rejects a path with .. segments that escape the worktree"
    (let [worktree (create-temp-dir)
          result   (release/process-file-action worktree
                                                {:action :create
                                                 :path   "../../etc/evil"
                                                 :content "bad"}
                                                nil)]
      (is (false? (:success? result)))
      (is (re-find #"(?i)traversal" (:error result)))))

  (testing "modify rejects a path with .. segments that escape the worktree"
    (let [worktree (create-temp-dir)
          result   (release/process-file-action worktree
                                                {:action  :modify
                                                 :path    "../../etc/evil"
                                                 :content "bad"}
                                                nil)]
      (is (false? (:success? result)))
      (is (re-find #"(?i)traversal" (:error result)))))

  (testing "delete rejects a path with .. segments that escape the worktree"
    (let [worktree (create-temp-dir)
          result   (release/process-file-action worktree
                                                {:action :delete
                                                 :path   "../../etc/evil"}
                                                nil)]
      (is (false? (:success? result)))
      (is (re-find #"(?i)traversal" (:error result)))))

  (testing "create allows a normal relative path inside the worktree"
    (let [worktree (create-temp-dir)
          result   (release/process-file-action worktree
                                                {:action  :create
                                                 :path    "src/foo.clj"
                                                 :content "(ns foo)"}
                                                nil)]
      (is (true? (:success? result))))))

(deftest ^{:stratum 2} process-file-action-symlink-traversal-test
  (testing "create rejects a dangling symlink pointing outside the worktree"
    (let [worktree (create-temp-dir)
          outside  (create-temp-dir)
          target   (fs/path outside "never-exists.txt")
          link     (fs/path worktree "link.txt")]
      (fs/create-sym-link link target)
      (let [result (release/process-file-action worktree
                                                {:action  :create
                                                 :path    "link.txt"
                                                 :content "bad"}
                                                nil)]
        (is (false? (:success? result)))
        (is (re-find #"(?i)traversal" (:error result)))
        (is (false? (fs/exists? target))))))

  (testing "modify rejects an existing symlink pointing outside the worktree"
    (let [worktree (create-temp-dir)
          outside  (create-temp-dir)
          target   (fs/path outside "existing.txt")]
      (spit (str target) "original")
      (fs/create-sym-link (fs/path worktree "link.txt") target)
      (let [result (release/process-file-action worktree
                                                {:action  :modify
                                                 :path    "link.txt"
                                                 :content "bad"}
                                                nil)]
        (is (false? (:success? result)))
        (is (re-find #"(?i)traversal" (:error result)))
        (is (= "original" (slurp (str target))))))))

(deftest ^{:stratum 2} process-file-action-symlink-ancestor-test
  (testing "create allows a file inside a symlinked worktree root"
    (let [actual-wt   (create-temp-dir)
          link-parent (create-temp-dir)
          link-wt     (fs/path link-parent "wt-link")]
      (fs/create-sym-link link-wt actual-wt)
      (let [result (release/process-file-action link-wt
                                                {:action  :create
                                                 :path    "src/foo.clj"
                                                 :content "(ns foo)"}
                                                nil)]
        (is (true? (:success? result))))))

  (testing "create rejects a path through a symlinked directory outside the worktree"
    (let [worktree (create-temp-dir)
          outside  (create-temp-dir)
          link     (fs/path worktree "link")]
      (fs/create-sym-link link outside)
      (let [result (release/process-file-action worktree
                                                {:action  :create
                                                 :path    "link/new.txt"
                                                 :content "bad"}
                                                nil)]
        (is (false? (:success? result)))
        (is (re-find #"(?i)traversal" (:error result)))
        (is (false? (fs/exists? (fs/path outside "new.txt")))))))

  (testing "create rejects a path using .. after a symlink to escape the worktree"
    (let [worktree (create-temp-dir)
          outside  (create-temp-dir)
          subdir   (fs/path outside "subdir")]
      (fs/create-dirs subdir)
      (fs/create-sym-link (fs/path worktree "jump") subdir)
      ;; jump -> outside/subdir; jump/../new.txt resolves to outside/new.txt
      (let [result (release/process-file-action worktree
                                                {:action  :create
                                                 :path    "jump/../new.txt"
                                                 :content "bad"}
                                                nil)]
        (is (false? (:success? result)))
        (is (false? (fs/exists? (fs/path outside "new.txt"))))))))

(deftest ^{:stratum 2} process-file-action-dotdot-in-unresolved-suffix-test
  (testing "modify rejects '..' through a missing directory that re-enters via a symlink"
    ;; jump -> outside/subdir. The kernel resolves
    ;; missing/../jump/../missing/victim.txt to outside/missing/victim.txt
    ;; once write creates worktree/missing; normalising the suffix does not.
    (let [worktree (create-temp-dir)
          outside  (create-temp-dir)
          victim   (fs/path outside "missing" "victim.txt")]
      (fs/create-dirs (fs/path outside "subdir"))
      (fs/create-dirs (fs/parent victim))
      (spit (str victim) "original")
      (fs/create-sym-link (fs/path worktree "jump") (fs/path outside "subdir"))
      (let [result (release/process-file-action worktree
                                                {:action  :modify
                                                 :path    "missing/../jump/../missing/victim.txt"
                                                 :content "overwritten"}
                                                nil)]
        (is (false? (:success? result)))
        (is (re-find #"(?i)traversal" (:error result)))
        (is (= "original" (slurp (str victim))) "the outside file is unchanged")
        (is (false? (fs/exists? (fs/path worktree "missing")))
            "no directory is created for a rejected path"))))

  (testing "create rejects '..' that would place a new directory outside"
    (let [worktree (create-temp-dir)
          outside  (create-temp-dir)]
      (fs/create-sym-link (fs/path worktree "jump") outside)
      (let [result (release/process-file-action worktree
                                                {:action  :create
                                                 :path    "missing/../jump/newdir/evil.txt"
                                                 :content "bad"}
                                                nil)]
        (is (false? (:success? result)))
        (is (false? (fs/exists? (fs/path outside "newdir")))
            "no directory is created outside the worktree"))))

  (testing "'..' within an existing directory still resolves normally"
    (let [worktree (create-temp-dir)]
      (fs/create-dirs (fs/path worktree "src"))
      (let [result (release/process-file-action worktree
                                                {:action  :create
                                                 :path    "src/../README.md"
                                                 :content "ok"}
                                                nil)]
        (is (true? (:success? result)))
        (is (= "ok" (slurp (str (fs/path worktree "README.md")))))))))

(deftest ^{:stratum 2} process-file-action-delete-removes-only-entries-inside-test
  (testing "delete rejects an entry whose parent resolves outside, even if it links back in"
    ;; inner -> outside/dir, and outside/dir/link -> worktree/keep.txt.
    ;; Following the leaf lands inside the worktree, but deletion removes
    ;; the entry itself: outside/dir/link.
    (let [worktree (create-temp-dir)
          outside  (create-temp-dir)
          out-dir  (fs/path outside "dir")
          keep     (fs/path worktree "keep.txt")
          link     (fs/path out-dir "link")]
      (fs/create-dirs out-dir)
      (spit (str keep) "keep")
      (fs/create-sym-link link keep)
      (fs/create-sym-link (fs/path worktree "inner") out-dir)
      (let [result (release/process-file-action worktree
                                                {:action :delete :path "inner/link"}
                                                nil)]
        (is (false? (:success? result)))
        (is (re-find #"(?i)traversal" (:error result)))
        (is (fs/exists? link {:nofollow-links true}) "the outside entry is still there"))))

  (testing "delete removes a symlink inside the worktree without touching its target"
    (let [worktree (create-temp-dir)
          outside  (create-temp-dir)
          target   (fs/path outside "target.txt")]
      (spit (str target) "outside")
      (fs/create-sym-link (fs/path worktree "link.txt") target)
      (let [result (release/process-file-action worktree
                                                {:action :delete :path "link.txt"}
                                                nil)]
        (is (true? (:success? result)))
        (is (false? (fs/exists? (fs/path worktree "link.txt") {:nofollow-links true})))
        (is (= "outside" (slurp (str target))) "the link's target is untouched"))))

  (testing "delete refuses the worktree root itself"
    (let [worktree (create-temp-dir)]
      (is (false? (:success? (release/process-file-action worktree
                                                          {:action :delete :path "."}
                                                          nil))))
      (is (fs/exists? worktree)))))

(use-fixtures :each cleanup-temp-dirs)
