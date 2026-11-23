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
(ns ai.miniforge.agent-environment.interface.protocol
  "What a provider supplies: compute behind a namespace the contract names.
   Providers never decide tenancy, leases or authority; the controller has
   already admitted every call that reaches them. Every operation returns
   data; a provider converts its client's exceptions at its own boundary.")

;------------------------------------------------------------------------------ Layer 0

(defprotocol ^{:stratum 0} EnvironmentProvider
  (capabilities [provider]
    "The provider's declared `Capabilities`.")
  (probe [provider request]
    "Live preflight: a vector of failure reasons, empty when the provider
     can satisfy `request` now.")
  (provision! [provider target request]
    "Create the environment named by `target` (`:target/namespace`,
     `:target/generation`), recording the generation on what it creates.
     Returns `{:provision/uid s :provision/steps [Step]}`; a failed step
     leaves earlier steps applied and later ones unattempted.")
  (observe [provider target]
    "`{:observed/uid s-or-nil :observed/generation n-or-nil :observed/ready?
     bool}` for `target`; the generation is the one recorded at provision.")
  (exec! [provider target command]
    "Run `{:exec/argv [s] :exec/timeout-ms n}`. Returns `{:exec/exit n
     :exec/stdout s :exec/stderr s}`. Refuses a target whose UID differs.")
  (write-file! [provider target path text]
    "Write UTF-8 `text` at `path` inside the environment.")
  (read-file [provider target path]
    "The UTF-8 text at `path` inside the environment, or an anomaly.")
  (cancel! [provider target]
    "Stop running work; returns `{:cancel/steps [Step]}`.")
  (deprovision! [provider target]
    "Delete everything `provision!` created. Returns `{:deprovision/steps
     [Step]}`; a delete accepted but not confirmed gone is
     `:outcome/uncertain`, never applied."))
