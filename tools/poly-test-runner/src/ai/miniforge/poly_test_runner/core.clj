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
(ns ai.miniforge.poly-test-runner.core
  "Custom Polylith test runner, wired in via workspace.edn's :test
   :create-test-runner. Registered on the clj-poly classpath through the
   :poly alias's :extra-paths (deps.edn), so it is only ever loaded by the
   `poly` CLI process itself, never by application code.

   Polylith's built-in clojure-test-test-runner
   (polylith.clj.core.clojure-test-test-runner.core/run-test-statements)
   throws immediately as soon as ONE test namespace reports any error or
   failure, from inside a `doseq` over every test namespace in the project.
   That throw is uncaught at every level above it (the per-project doseq in
   run-test-statements, and the per-project doseq in
   test-runner-orchestrator.core/run), so a single red namespace silently
   aborts every remaining brick in that project AND every remaining project
   in the same `poly test` invocation — with no message indicating anything
   was skipped. Confirmed against clj-poly 0.2.21-0.3.32 (current latest);
   not fixed upstream as of this writing.

   This reuses the built-in runner's namespace-discovery and statement-
   building helpers verbatim, swapping in a statement runner that always
   finishes the full doseq, then throws once at the end if anything failed
   — satisfying the TestRunner contract (`run-tests` must throw on failure)
   without masking untested bricks."
  (:require
   [polylith.clj.core.clojure-test-test-runner.core :as builtin]
   [polylith.clj.core.test-runner-contract.interface :as test-runner-contract]
   [polylith.clj.core.util.interface.color :as color]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-test-statements-without-early-abort
  "Same body as polylith.clj.core.clojure-test-test-runner.core/run-test-statements,
   except the throw-on-failure moves from inside the doseq (aborting the
   remaining statements) to after it (every statement still runs)."
  [project-name eval-in-project test-statements run-message is-verbose color-mode]
  (println (str run-message))
  (when is-verbose (println (str "# test-statements:\n" test-statements) "\n"))
  (let [any-failed? (atom false)]
    (doseq [statement test-statements]
      (let [{:keys [error fail pass]}
            (try
              (eval-in-project statement)
              (catch Exception e
                (.printStackTrace e)
                (println (str (color/error color-mode "Couldn't run test statement")
                               " for the " (color/project project-name color-mode)
                               " project: " statement " " (color/error color-mode e)))))
            result-str (str "Test results: " pass " passes, " fail " failures, " error " errors.")]
        (if (or (nil? error) (< 0 error) (< 0 fail))
          (do (reset! any-failed? true)
              (println (str "\n" (color/error color-mode result-str))))
          (println (str "\n" (color/ok color-mode result-str))))))
    (when @any-failed?
      (throw (Exception.
              (str "\n" (color/error
                         color-mode
                         (str "One or more test statements failed in the " project-name
                              " project (see full output above). Every namespace in this "
                              "project was executed — none were skipped due to an earlier "
                              "failure."))))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create
  "Drop-in replacement for polylith.clj.core.clojure-test-test-runner.interface/create."
  [{:keys [workspace project]}]
  (let [{:keys [bases components]} workspace
        {:keys [name bricks-to-test projects-to-test namespaces paths]} project
        test-sources-present* (delay (-> paths :test seq))
        bricks-to-test* (delay bricks-to-test)
        projects-to-test* (delay projects-to-test)
        test-statements* (->> [(builtin/brick-test-namespaces (into components bases) @bricks-to-test*)
                                (builtin/project-test-namespaces name @projects-to-test* namespaces)]
                               (into [] (comp cat (map builtin/->test-statement)))
                               (delay))]
    (reify test-runner-contract/TestRunner
      (test-runner-name [_] "Resilient clojure.test runner (completes every brick even if an earlier one errors)")

      (test-sources-present? [_] @test-sources-present*)

      (tests-present? [this {_eval-in-project :eval-in-project :as _opts}]
        (and (test-runner-contract/test-sources-present? this)
             (seq @test-statements*)))

      (run-tests [this {:keys [color-mode eval-in-project is-verbose] :as opts}]
        (when (test-runner-contract/tests-present? this opts)
          (let [run-message (builtin/run-message name components bases @bricks-to-test*
                                                  @projects-to-test* color-mode)]
            (run-test-statements-without-early-abort
             name eval-in-project @test-statements* run-message is-verbose color-mode)))))))
