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
            [ai.miniforge.schema.interface :as schema])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0
;; Kustomize wrappers

(def KustomizeApplyResult
  [:map
   [:success? :boolean]
   [:rendered-yaml [:maybe :string]]
   [:build-result map?]
   [:apply-result [:maybe map?]]
   [:error {:optional true} any?]
   [:anomaly {:optional true} map?]])

(defn- validate-result!
  [result]
  (schema/validate KustomizeApplyResult result))

(defn kustomize-build!
  [kustomize-dir & {:keys [timeout-ms] :or {timeout-ms 60000}}]
  (exec/sh-with-timeout "kustomize" ["build" kustomize-dir] :timeout-ms timeout-ms))

(defn kustomize-apply!
  [kustomize-dir & {:keys [namespace context dry-run?]}]
  (let [build-result (kustomize-build! kustomize-dir)]
    (if (schema/failed? build-result)
      (validate-result!
       (schema/failure :rendered-yaml
                       (msg/t :shell/kustomize-build-failed
                              {:error (get build-result :stderr "")})
                       {:build-result build-result
                        :apply-result nil}))
      (let [rendered-yaml (:stdout build-result)
            apply-args    (cond-> ["apply" "-f" "-"]
                            namespace (into ["--namespace" namespace])
                            context   (into ["--context" context])
                            dry-run?  (conj "--dry-run=client"))
            tmp-file      (File/createTempFile "kustomize-" ".yaml")
            _             (spit tmp-file rendered-yaml)
            apply-result  (exec/sh-with-timeout "kubectl"
                                                (-> (subvec apply-args 0 1)
                                                    (into ["-f" (.getAbsolutePath tmp-file)])
                                                    (into (subvec apply-args 3)))
                                                :timeout-ms 120000)]
        (try
          (.delete tmp-file)
          (catch Exception _ nil))
        (if (schema/succeeded? apply-result)
          (validate-result!
           (schema/success :rendered-yaml rendered-yaml
                           {:build-result build-result
                            :apply-result apply-result}))
          (validate-result!
           (schema/failure :rendered-yaml
                           (get apply-result
                                :stderr
                                (msg/t :shell/kubectl-apply-failed))
                           {:build-result build-result
                            :apply-result apply-result})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (kustomize-build! "/path/to/overlay")
  :leave-this-here)
