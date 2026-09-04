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
(ns ai.miniforge.workflow.checkpoint-root-support
  "Keeps this project's tests out of the operator's live checkpoint root.

   Project-level twin of the workflow brick's
   `ai.miniforge.workflow.checkpoint-test-support`, which explains the
   leak it stops. The two cannot be one namespace: `bb test:integration`
   runs project tests with the project's own `deps.edn` as the classpath
   (project paths plus brick `src`), so a brick's `test` directory is not
   loadable from here. Keep the two bodies identical."
  (:require
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
   [babashka.fs :as fs]
   [slingshot.slingshot :refer [try+]])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create-temp-checkpoint-root
  "A fresh, empty directory under the JVM temp dir, as a path string.
   Named so a leaked one is attributable to a test run."
  []
  (str (Files/createTempDirectory "mf-checkpoint-test-"
                                  (make-array FileAttribute 0))))

(defn ^{:stratum 0} delete-checkpoint-root!
  "Remove a temp checkpoint root and everything a run wrote under it.

   Best effort: this runs in a fixture's `finally`, so a cleanup failure
   (a file still open on Windows, a permission change under the root)
   must not turn a passing test red or replace a failing test's real
   error. A leaked root is findable by its `mf-checkpoint-test-` prefix."
  [root]
  (try+
    (fs/delete-tree root)
    (catch Exception _
      nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-temp-checkpoint-root
  "Call `f` with the path of a fresh temp checkpoint root.

   While `f` runs, that path is also what
   `checkpoint-store-paths/default-checkpoint-root` returns, so a
   pipeline run inside `f` checkpoints there whether or not it passes
   `:checkpoint/root` explicitly. The directory is deleted afterwards."
  [f]
  (let [root (create-temp-checkpoint-root)]
    (try
      (with-redefs-fn {#'checkpoint-paths/default-checkpoint-root (constantly root)}
        (fn [] (f root)))
      (finally
        (delete-checkpoint-root! root)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-temp-checkpoint-root
  "clojure.test fixture form of `call-with-temp-checkpoint-root`.

   `(use-fixtures :each with-temp-checkpoint-root)` in any namespace that
   runs a pipeline, so no test in it can write to the live root."
  [f]
  (call-with-temp-checkpoint-root (fn [_root] (f))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (call-with-temp-checkpoint-root
   (fn [root] [root (checkpoint-paths/default-checkpoint-root)]))
  :leave-this-here)
