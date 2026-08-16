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
(def ^{:stratum 0} KustomizeApplyResult
  [:map
   [:success? :boolean]
   [:rendered-yaml [:maybe :string]]
   [:build-result map?]
   [:apply-result [:maybe map?]]
   [:error {:optional true} any?]
   [:anomaly {:optional true} map?]])

(defn ^{:stratum 0} kustomize-build!
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

(defn ^{:stratum 1} kustomize-apply!
  [kustomize-dir & {:keys [namespace context dry-run?]}]
  (let [build-result (kustomize-build! kustomize-dir)]
    (if (schema/failed? build-result)
      (schema/validate-anomaly
       KustomizeApplyResult
       (schema/failure :rendered-yaml
                       (msg/t :shell/kustomize-build-failed
                              {:error (failure-detail build-result)})
                       {:build-result build-result
                        :apply-result nil}))
      (let [rendered-yaml (:stdout build-result)
            apply-args    (cond-> ["apply" "-f" "-"]
                            namespace (into ["--namespace" namespace])
                            context   (into ["--context" context])
                            dry-run?  (conj "--dry-run=client"))
            apply-result  (exec/sh-with-timeout "kubectl" apply-args
                                                :in rendered-yaml
                                                :timeout-ms (get timeouts/timeouts :kustomize-apply-ms 120000))]
        (if (schema/succeeded? apply-result)
          (schema/validate-anomaly
           KustomizeApplyResult
           (schema/success :rendered-yaml rendered-yaml
                           {:build-result build-result
                            :apply-result apply-result}))
          (schema/validate-anomaly
           KustomizeApplyResult
           (schema/failure :rendered-yaml
                           (failure-detail apply-result)
                           {:build-result build-result
                            :apply-result apply-result})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (kustomize-build! "/path/to/overlay")
  :leave-this-here)
