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
(ns ai.miniforge.bb-test-runner.coverage-exec
  "Impure Cloverage execution: read the repo's `deps.edn`, prefetch the
   tool, and shell out to run it. Pure argv derivation lives in
   `coverage-cmd`; this namespace is the I/O boundary that calls it.

   Layer 0: `load-deps-config` (no same-file deps) and
   `install-coverage-tool` (its one dependency, `coverage-install-args`,
   is a cross-namespace call to `coverage-cmd`).
   Layer 1: `run-coverage`, built on `load-deps-config`."
  (:require [ai.miniforge.bb-test-runner.coverage-cmd :as coverage-cmd]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import
   [java.lang String System]
   [java.nio.charset StandardCharsets]
   [java.nio.file Files]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} load-deps-config
  "Read and parse a repo-local deps.edn file."
  [repo-root]
  (let [deps-path (str (fs/path repo-root "deps.edn"))]
    (edn/read-string
     (String. (Files/readAllBytes (.toPath (fs/file deps-path)))
              StandardCharsets/UTF_8))))

(defn ^{:stratum 0} install-coverage-tool
  "Prefetch the Cloverage dependency into the repo's local Clojure cache."
  [{:keys [repo-root]}]
  (let [root (or repo-root ".")
        args (coverage-cmd/coverage-install-args)]
    (shell/with-sh-dir root
      (let [{:keys [exit out err]} (apply shell/sh "clojure" args)]
        (when-not (str/blank? out)
          (println out))
        (when-not (str/blank? err)
          (.println System/err err))
        exit))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-coverage
  "Run Cloverage for the repo rooted at `repo-root` using the selected
   deps.edn alias. Streams output and returns the process exit code."
  [{:keys [repo-root] :as opts}]
  (let [root (or repo-root ".")
        deps-config (load-deps-config root)
        args (coverage-cmd/coverage-args deps-config opts)]
    (shell/with-sh-dir root
      (let [{:keys [exit out err]} (apply shell/sh "clojure" args)]
        (when-not (str/blank? out)
          (println out))
        (when-not (str/blank? err)
          (.println System/err err))
        exit))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (load-deps-config ".")
  (install-coverage-tool {:repo-root "."})
  (run-coverage {:repo-root "." :alias-key :test})

  :leave-this-here)
