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
(ns ai.miniforge.policy-pack.builtin-detectors
  "Built-in :custom detectors, registered at namespace load. Each detector's
   own logic lives in its own sibling namespace under `builtin-detectors.*`
   (rule 210: registering more than one detector's worth of layers in this
   file would exceed the 3-layer budget); this namespace only wires the
   registration symbol — the one `pack.edn` `:custom-fn` data refers to — to
   the sibling's implementation. The rule reaches the detector via
   `:policy-pack/rule` in context (threaded in by
   `detection/run-resolved-custom`)."
  (:require
   [ai.miniforge.policy-pack.detection :as detection]
   [ai.miniforge.policy-pack.builtin-detectors.approved-instance-types :as approved-instance-types]))

;------------------------------------------------------------------------------ Layer 0

;; Registration (load-time side effect — see ns docstring)
(defn- ^{:stratum 0} register!
  []
  (detection/register-custom-fn!
   'ai.miniforge.policy-pack.builtin-detectors/check-approved-instance-types
   approved-instance-types/check-approved-instance-types))

(register!)
