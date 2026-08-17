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
(def ^{:stratum 0} KustomizeResult
  "The one result shape for producing a Kustomize manifest, shared by
   `kustomize-render!` and `kustomize-apply!`.

   The manifest is always under `:rendered-yaml` and the raw build shell
   result under `:build-result`. Render and apply differ in exactly one
   key: `:apply-result` is nil for a render, because nothing was applied.

   They share a shape deliberately. When rendering returned a raw shell
   result and applying returned this one, a caller reading `:rendered-yaml`
   off the render got nil and read a healthy render as an empty one."
  [:map
   [:success? :boolean]
   [:rendered-yaml [:maybe :string]]
   [:build-result map?]
   [:apply-result [:maybe map?]]
   [:error {:optional true} any?]
   [:anomaly {:optional true} map?]])

(defn ^{:stratum 0} kustomize-build!
  "Run `kustomize build` and return `exec/sh-with-timeout`'s raw
   `CommandResult`, whose manifest is under `:stdout`.

   This is the unwrapped process boundary, NOT a `KustomizeResult` — it has
   no `:rendered-yaml`. Callers outside this namespace want
   `kustomize-render!`."
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
  "Build one Kustomize target and report it as a `KustomizeResult`.

   `:apply-result` is nil — nothing was applied, and nothing contacted the
   cluster. `:rendered-yaml` is nil only when the build itself failed, so a
   nil manifest means no manifest, never a manifest under another key."
  [kustomize-dir]
  (let [build-result (kustomize-build! kustomize-dir)]
    (schema/validate-anomaly
     KustomizeResult
     (if (schema/failed? build-result)
       (schema/failure :rendered-yaml
                       (msg/t :shell/kustomize-build-failed
                              {:error (failure-detail build-result)})
                       {:build-result build-result
                        :apply-result nil})
       (schema/success :rendered-yaml (:stdout build-result)
                       {:build-result build-result
                        :apply-result nil})))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} kustomize-apply!
  "Render one Kustomize target and apply exactly those bytes.

   Returns the same `KustomizeResult` shape as `kustomize-render!`, so a
   caller reads the manifest from `:rendered-yaml` whichever it called. A
   build failure is returned unchanged from the render — there is nothing
   to apply and no kubectl runs."
  [kustomize-dir & {:keys [namespace context dry-run?]}]
  (let [rendered (kustomize-render! kustomize-dir)]
    (if (schema/failed? rendered)
      rendered
      (let [rendered-yaml (:rendered-yaml rendered)
            build-result  (:build-result rendered)
            apply-args    (cond-> ["apply" "-f" "-"]
                            namespace (into ["--namespace" namespace])
                            context   (into ["--context" context])
                            dry-run?  (conj "--dry-run=client"))
            apply-result  (exec/sh-with-timeout "kubectl" apply-args
                                                :in rendered-yaml
                                                :timeout-ms (get timeouts/timeouts :kustomize-apply-ms 120000))]
        (schema/validate-anomaly
         KustomizeResult
         (if (schema/succeeded? apply-result)
           (schema/success :rendered-yaml rendered-yaml
                           {:build-result build-result
                            :apply-result apply-result})
           ;; `schema/failure` nils its data key and merges opts last. The
           ;; manifest is re-set deliberately: the build succeeded, and what
           ;; kubectl rejected is the evidence worth keeping.
           (schema/failure :rendered-yaml
                           (failure-detail apply-result)
                           {:build-result build-result
                            :apply-result apply-result
                            :rendered-yaml rendered-yaml})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (kustomize-build! "/path/to/overlay")
  (kustomize-render! "/path/to/overlay")
  :leave-this-here)
