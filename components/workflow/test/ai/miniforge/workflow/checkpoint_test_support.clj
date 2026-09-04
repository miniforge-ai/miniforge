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
(ns ai.miniforge.workflow.checkpoint-test-support
  "Keeps test checkpoints out of the operator's live checkpoint root.

   `runner/run-pipeline` and `runner/execute-single-iteration` persist a
   machine snapshot, manifest and phase checkpoints on every iteration.
   Unless the caller supplies `:checkpoint/root`, the root resolves
   through `checkpoint-store-paths/default-checkpoint-root` to
   `~/.miniforge/checkpoints` — the same directory a real run writes to
   and that bench forensics (eval/codex-traps) read from. A test that
   runs a pipeline with default opts therefore leaves a real-looking run
   directory behind on the developer's machine. On 2026-09-03 that
   directory held ~187k run directories, most of them from tests.

   `with-temp-checkpoint-root` is a clojure.test fixture: while the test
   runs, the default root is a fresh temp directory, deleted afterwards.
   `call-with-temp-checkpoint-root` is the same thing for a test that
   also needs the path, to pass as `:checkpoint/root` or to read back.

   The override is a `with-redefs` on the single resolution point, below
   config. Nothing above it works from inside a test: `MINIFORGE_HOME`
   is process environment a running JVM cannot change, and it would not
   move the root anyway — the default config resource
   (`config/default-user-config-fallback.edn`) sets
   `[:workflow :checkpoint-root]` to `~/.miniforge/checkpoints`, so the
   merged config carries that value even under an empty home."
  (:require
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
   [babashka.fs :as fs])
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
  "Remove a temp checkpoint root and everything a run wrote under it."
  [root]
  (fs/delete-tree root))

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
