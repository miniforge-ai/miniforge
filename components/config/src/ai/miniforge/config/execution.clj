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
(ns ai.miniforge.config.execution
  "Execution settings in the user config: where agents may run.

   [:execution :require-governed?] true means every agent run must execute
   governed (in a container capsule), never :local (a host worktree with
   the operator's privileges). The CLI refuses such runs up front; the
   workflow runner refuses them again where it acquires the run's
   environment, which every run path reaches.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} governed-required?
  "True when user config `cfg` requires governed execution. Fails closed:
   any value at [:execution :require-governed?] other than false or nil
   (\"true\", \"yes\" or 1 in a hand-edited file) counts as required, so a
   malformed value cannot turn the requirement off."
  [cfg]
  (let [v (get-in cfg [:execution :require-governed?])]
    (not (or (nil? v) (false? v)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (governed-required? {:execution {:require-governed? true}})  ; => true
  (governed-required? {:execution {:require-governed? "yes"}}) ; => true
  (governed-required? {})                                      ; => false

  :end)
