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

(ns ai.miniforge.dag-executor.protocols.worktree-copy-test
  "Path-traversal guard tests for WorktreeExecutor copy-file-to/from."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as sut]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- make-temp-dir!
  "Create a real temp directory and return its canonical path string."
  [prefix]
  (let [dir (java.nio.file.Files/createTempDirectory
             prefix
             (into-array java.nio.file.attribute.FileAttribute []))]
    (.toString (.toRealPath dir (into-array java.nio.file.LinkOption [])))))

(defn- write-temp-file!
  "Write `content` to `path` (string) and return the path."
  [path content]
  (spit path content)
  path)

(defn- error-code [r] (get-in r [:error :code]))

;------------------------------------------------------------------------------ Layer 1
;; copy-file-to — traversal guard

(deftest copy-file-to-rejects-dotdot-escape
  (let [worktree (make-temp-dir! "wt-copy-to-")
        src-dir  (make-temp-dir! "wt-src-")
        src-file (write-temp-file! (str src-dir "/payload.txt") "secret")]
    (testing "../../etc/passwd escapes the worktree root → :path-traversal"
      (let [r (sut/copy-file-to worktree src-file "../../etc/passwd")]
        (is (result/err? r))
        (is (= :path-traversal (error-code r)))))
    (testing "../ escapes the worktree root → :path-traversal"
      (let [r (sut/copy-file-to worktree src-file "../escaped.txt")]
        (is (result/err? r))
        (is (= :path-traversal (error-code r)))))))

(deftest copy-file-to-allows-nested-path
  (let [worktree (make-temp-dir! "wt-copy-to-ok-")
        src-dir  (make-temp-dir! "wt-src-ok-")
        src-file (write-temp-file! (str src-dir "/hello.txt") "hello")]
    (testing "a simple nested path inside the worktree succeeds"
      (let [r (sut/copy-file-to worktree src-file "subdir/hello.txt")]
        (is (result/ok? r))
        (is (pos? (:copied-bytes (:data r))))))))

;------------------------------------------------------------------------------ Layer 1
;; copy-file-from — traversal guard

(deftest copy-file-from-rejects-dotdot-escape
  (let [worktree (make-temp-dir! "wt-copy-from-")
        dest-dir (make-temp-dir! "wt-dest-")]
    (testing "../../etc/shadow escapes the worktree root → :path-traversal"
      (let [r (sut/copy-file-from worktree "../../etc/shadow"
                                  (str dest-dir "/out.txt"))]
        (is (result/err? r))
        (is (= :path-traversal (error-code r)))))
    (testing "../escaped.txt escapes the worktree root → :path-traversal"
      (let [r (sut/copy-file-from worktree "../escaped.txt"
                                  (str dest-dir "/out2.txt"))]
        (is (result/err? r))
        (is (= :path-traversal (error-code r)))))))

(deftest copy-file-from-allows-nested-path
  (let [worktree  (make-temp-dir! "wt-copy-from-ok-")
        dest-dir  (make-temp-dir! "wt-dest-ok-")
        _         (io/make-parents (str worktree "/subdir/result.txt"))
        _         (write-temp-file! (str worktree "/subdir/result.txt") "done")]
    (testing "a valid nested path inside the worktree is copied out"
      (let [r (sut/copy-file-from worktree "subdir/result.txt"
                                  (str dest-dir "/result.txt"))]
        (is (result/ok? r))
        (is (pos? (:copied-bytes (:data r))))))))
