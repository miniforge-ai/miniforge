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
(ns ai.miniforge.self-healing.home-test-support
  "Keeps self-healing tests out of the developer's ~/.miniforge.

   The workaround registry and the backend-health store resolve their files
   from `user.home`, and the approval file from `config/miniforge-home`. A test
   that reaches one of their writers through a path fn it did not redefine
   writes the real file: workaround-detector-test redefined the approval path
   only, so its full-flow test wrote a :mock-pattern entry into
   ~/.miniforge/known_workarounds.edn on every run, with or without
   MINIFORGE_HOME.

   `with-temp-home` points every state-file path fn at a fresh temp directory,
   refuses to run the test while any `*-path` fn of a loaded self-healing
   namespace still resolves outside it, and deletes the directory afterwards."
  (:require
   [ai.miniforge.self-healing.backend-health :as backend-health]
   [ai.miniforge.self-healing.workaround-detector :as detector]
   [ai.miniforge.self-healing.workaround-registry :as registry]
   [babashka.fs :as fs]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} state-files
  "Each self-healing state-file path fn, and its file name in a test's temp dir."
  {#'backend-health/backend-health-path "backend_health.edn"
   #'detector/approval-file-path        "workaround_approvals.edn"
   #'registry/workaround-registry-path  "known_workarounds.edn"})

(defn ^{:stratum 0} inside?
  [dir path]
  (fs/starts-with? (fs/absolutize path) (fs/absolutize dir)))

(defn ^{:stratum 0} path-fns
  "Every public zero-argument `*-path` fn of a loaded self-healing namespace
   that is not test code."
  []
  (for [n (all-ns)
        :let [ns-str (str (ns-name n))]
        :when (and (str/starts-with? ns-str "ai.miniforge.self-healing.")
                   (not (str/ends-with? ns-str "-test"))
                   (not (str/ends-with? ns-str "-test-support")))
        [sym v] (ns-publics n)
        :when (and (str/ends-with? (name sym) "-path")
                   (some #{[]} (:arglists (meta v))))]
    v))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-temp-home
  "Call `(f dir)` with every self-healing state file inside `dir`, a fresh temp
   directory deleted afterwards. Throws before calling `f` when a loaded
   self-healing `*-path` fn still resolves outside `dir`."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "mf-self-healing-test-"}))]
    (try
      (with-redefs-fn (update-vals state-files #(constantly (str (fs/path dir %))))
        (fn []
          (doseq [v (path-fns)
                  :let [path (v)]
                  :when (not (inside? dir path))]
            (throw (ex-info (str v " resolves to " path ", outside the test's temp dir " dir)
                            {:var v :path path :dir dir})))
          (f dir)))
      (finally
        (fs/delete-tree dir)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-temp-home
  "clojure.test fixture form of `call-with-temp-home`."
  [f]
  (call-with-temp-home (fn [_dir] (f))))
