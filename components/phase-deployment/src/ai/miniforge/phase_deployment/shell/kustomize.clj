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
(ns ai.miniforge.phase-deployment.shell.kustomize
  "Kustomize CLI wrappers for deployment phases."
  (:require [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.phase-deployment.shell.exec :as exec]
            [ai.miniforge.phase-deployment.shell.timeouts :as timeouts]
            [ai.miniforge.schema.interface :as schema]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Kustomize wrappers
(def ^{:stratum 0} KustomizeRenderResult
  "The result of producing a Kustomize manifest.

   The manifest is under `:rendered-yaml` and the raw build shell result is
   retained under `:build-result`."
  [:map
   [:success? :boolean]
   [:rendered-yaml [:maybe :string]]
   [:build-result map?]
   [:error {:optional true} any?]
   [:anomaly {:optional true} map?]])

(defn- ^{:stratum 0} kustomize-build!
  "Run `kustomize build` and return `exec/sh-with-timeout`'s raw
   `CommandResult`, whose manifest is under `:stdout`.

   This private process boundary is not a `KustomizeRenderResult` — it has no
   `:rendered-yaml`."
  [kustomize-dir & {:keys [timeout-ms] :or {timeout-ms (get timeouts/timeouts :kustomize-build-ms 60000)}}]
  (exec/sh-with-timeout "kustomize" ["build" kustomize-dir] :timeout-ms timeout-ms))

(defn ^{:stratum 0} kubectl-apply!
  "Apply the supplied manifest bytes without rebuilding their source."
  [rendered-yaml & {:keys [namespace context server-dry-run?]}]
  (let [apply-args (cond-> ["apply" "-f" "-"]
                     namespace (into ["--namespace" namespace])
                     context (into ["--context" context])
                     server-dry-run? (into ["--dry-run=server" "-o" "yaml"]))]
    (exec/sh-with-timeout "kubectl" apply-args
                          :in rendered-yaml
                          :timeout-ms (get timeouts/timeouts
                                           :kustomize-apply-ms 120000))))

(defn- ^{:stratum 0} failure-detail
  [command-result]
  (let [error (:error command-result)]
    (or (not-empty (:stderr command-result))
        (when-not (and (string? error) (str/blank? error)) error)
        (not-empty (get-in command-result [:anomaly :anomaly/message]))
        (msg/t :shell/unknown-command-failure))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} kustomize-render!
  "Build one Kustomize target and report it as a `KustomizeRenderResult`.

   Nothing contacts the cluster. `:rendered-yaml` is nil only when the build
   itself failed, so a nil manifest means no manifest, never a manifest under
   another key."
  [kustomize-dir]
  (let [build-result (kustomize-build! kustomize-dir)]
    (schema/validate-anomaly
     KustomizeRenderResult
     (if (schema/failed? build-result)
       (schema/failure :rendered-yaml
                       (msg/t :shell/kustomize-build-failed
                              {:error (failure-detail build-result)})
                       {:build-result build-result})
       (schema/success :rendered-yaml (:stdout build-result)
                       {:build-result build-result})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (kustomize-build! "/path/to/overlay")
  (kustomize-render! "/path/to/overlay")
  :leave-this-here)
