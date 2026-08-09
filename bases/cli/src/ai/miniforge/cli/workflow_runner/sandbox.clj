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
(ns ai.miniforge.cli.workflow-runner.sandbox
  "Container sandbox setup for isolated workflow execution. Runtime-agnostic:
   the host's container runtime is auto-selected (Podman first, Docker
   second), with MINIFORGE_RUNTIME as the explicit override.

   The repo/branch the container checks out is resolved by
   `ai.miniforge.cli.workflow-runner.sandbox-clone-target`.

   Stratification (intra-namespace):
   Layer 0 — `sandbox-release-fn`, the `infer-branch` / `infer-repo-url`
             re-exports, `prepare-sandbox` (no in-ns deps).
   Layer 1 — `setup-sandbox-context` (composes Layer 0)."
  (:require
   [ai.miniforge.cli.runtime-env :as runtime-env]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.sandbox-clone-target :as clone-target]
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0

;; No in-namespace dependencies.
(defn ^{:stratum 0} sandbox-release-fn [executor environment-id]
  (fn []
    (try
      (dag/release-environment! executor environment-id)
      (catch Exception _ nil))))

;; Relocated public vars re-exported so existing `:require [...
;; workflow-runner.sandbox :as sandbox]` call sites resolve unchanged —
;; same convention as the workflow-runner split (miniforge#1667).
(def ^{:stratum 0} infer-branch clone-target/infer-branch)

(def ^{:stratum 0} infer-repo-url clone-target/infer-repo-url)

;; Sandbox preparation — expressed in the clone-target vocabulary.
(defn ^{:stratum 0} prepare-sandbox [spec enriched-spec]
  (let [prep-result (dag/prepare-runtime-executor!
                     (runtime-env/selection-config {:image-type :clojure}))]
    (if-not (dag/ok? prep-result)
      prep-result
      (let [executor (:executor (dag/unwrap prep-result))
            gh-token (System/getenv "GH_TOKEN")
            env-config (cond-> {} gh-token (assoc :env {:GH_TOKEN gh-token}))
            env-result (dag/acquire-environment! executor (random-uuid) env-config)]
        (if-not (dag/ok? env-result)
          env-result
          (let [env-id (:environment-id (dag/unwrap env-result))
                repo-url (clone-target/infer-repo-url spec enriched-spec)
                branch (clone-target/infer-branch spec enriched-spec)]
            (when repo-url
              (dag/clone-and-checkout! executor env-id repo-url branch {}))
            (dag/ok {:executor executor
                     :environment-id env-id
                     :sandbox-workdir "/workspace"})))))))

;------------------------------------------------------------------------------ Layer 1

;; Composes Layer 0.
(defn ^{:stratum 1} setup-sandbox-context [base-context sandbox? spec enriched-spec quiet]
  (if-not sandbox?
    [base-context nil]
    (do
      (when-not quiet
        (println (display/colorize :yellow "🐳 Setting up sandbox container...")))
      (let [result (prepare-sandbox spec enriched-spec)]
        (if-not (dag/ok? result)
          [(assoc base-context :sandbox-error result) nil]
          (let [{:keys [executor environment-id sandbox-workdir]} (dag/unwrap result)]
            (when-not quiet
              (println (display/colorize :green "  ✓ Sandbox container ready")))
            [(assoc base-context
                    :executor executor
                    :environment-id environment-id
                    :sandbox-workdir sandbox-workdir)
             (sandbox-release-fn executor environment-id)]))))))
