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
(ns ai.miniforge.dag-executor.scratch-gc-queue-scope-test
  "The scratch-ref GC queue lives under MINIFORGE_HOME and reports an
   unreadable queue instead of reading it as empty. Uses a throwaway git
   repository; `gc-scratch-refs!` is never reached."
  (:require
   [ai.miniforge.config.interface :as mf-config]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.scratch-gc-queue :as sut]
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} git-repo!
  "A new git repository `dir`/`repo-name`; returns its canonical path."
  [dir repo-name]
  (let [repo (fs/file dir repo-name)]
    (fs/create-dirs repo)
    (shell/sh "git" "init" "-q" (str repo))
    (.getCanonicalPath repo)))

(deftest ^{:stratum 0} queue-path-follows-miniforge-home-test
  (let [home (str (fs/create-temp-dir))]
    (with-redefs [mf-config/miniforge-home (constantly home)]
      (is (= (str (fs/file home "scratch-gc-queue.edn")) (sut/gc-queue-path))))
    (fs/delete-tree home)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unreadable-queue-is-reported-and-kept-test
  (let [dir     (str (fs/create-temp-dir))
        queue   (str dir "/queue.edn")
        repo    (git-repo! dir "a")
        damaged "[{:workflow-id \"wf-a\" :finished-at"]
    (with-redefs [sut/gc-queue-path (constantly queue)]
      (doseq [[op call] [["enqueue" #(sut/enqueue-workflow-gc! "wf-new")]
                         ["GC pass" #(sut/run-deferred-gc! repo 7)]]]
        (spit queue damaged)
        (let [r      (call)
              copies (fs/glob dir "queue.edn.corrupt-*")]
          (testing op
            (testing "returns an error instead of treating the queue as empty"
              (is (result/err? r)))
            (testing "keeps the unreadable content in a quarantine copy"
              (is (= [damaged] (mapv (comp slurp str) copies)))))
          (run! fs/delete copies)))
      (testing "the queue is usable again afterwards"
        (is (result/ok? (sut/enqueue-workflow-gc! "wf-next")))))
    (fs/delete-tree dir)))
