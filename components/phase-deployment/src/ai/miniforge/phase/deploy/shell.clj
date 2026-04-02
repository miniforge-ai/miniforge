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

(ns ai.miniforge.phase.deploy.shell
  "Public deployment shell API.

   The implementation is split into focused subnamespaces so the public
   surface remains stable while Pulumi, Kubernetes, Kustomize, and generic
   execution concerns evolve independently."
  (:require [ai.miniforge.phase.deploy.shell.exec :as exec]
            [ai.miniforge.phase.deploy.shell.kubectl :as kubectl]
            [ai.miniforge.phase.deploy.shell.kustomize :as kustomize]
            [ai.miniforge.phase.deploy.shell.pulumi :as pulumi]))

;------------------------------------------------------------------------------ Layer 0
;; Public API

(def sh-with-timeout exec/sh-with-timeout)
(def classify-error exec/classify-error)

(def pulumi! pulumi/pulumi!)
(def pulumi-preview! pulumi/pulumi-preview!)
(def pulumi-up! pulumi/pulumi-up!)
(def pulumi-outputs! pulumi/pulumi-outputs!)

(def kubectl! kubectl/kubectl!)
(def kubectl-rollout-status! kubectl/kubectl-rollout-status!)
(def kubectl-get-pods! kubectl/kubectl-get-pods!)

(def kustomize-build! kustomize/kustomize-build!)
(def kustomize-apply! kustomize/kustomize-apply!)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (sh-with-timeout "echo" ["hello world"])
  (pulumi-preview! "/path/to/project" :stack "dev")
  (kubectl! "get" :extra-args ["pods"] :namespace "default" :output "json")
  (kustomize-build! "/path/to/overlay")
  :leave-this-here)
