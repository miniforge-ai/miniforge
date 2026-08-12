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
(ns ai.miniforge.policy-pack.repair.builtin
  "Built-in repair functions (whitespace, trailing newline). Split out of
   `ai.miniforge.policy-pack.repair` (rule 210: the parent namespace measured
   4 real layers, max 3; these concrete repair implementations are
   layer-coherent on their own). Not registered at load time — a caller
   opts in via `ai.miniforge.policy-pack.repair.registry/register-repair!`.

   Layer 0: whitespace-repair, trailing-newline-repair"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} whitespace-repair
  "Repair function for whitespace violations (trailing whitespace, mixed tabs/spaces)."
  [_violation artifact _context]
  (let [content (get artifact :content "")
        fixed (-> content
                  (str/replace #"[ \t]+\n" "\n")
                  (str/replace #"\t" "  "))]
    (if (= content fixed)
      {:success? false :artifact artifact :fix-description "No whitespace issues found"}
      {:success? true
       :artifact (assoc artifact :content fixed)
       :fix-description "Removed trailing whitespace and converted tabs to spaces"})))

(defn ^{:stratum 0} trailing-newline-repair
  "Ensure file ends with exactly one newline. Uses `trimr`, which strips ALL
   trailing whitespace (spaces/tabs/newlines), not just newline characters,
   before appending the single `\\n` — so a file with trailing spaces on its
   last line loses them too. That overlaps `whitespace-repair` by design:
   this repair's job is the end-of-file newline invariant, not narrowly
   scoped to newline characters alone."
  [_violation artifact _context]
  (let [content (get artifact :content "")
        fixed (str (clojure.string/trimr content) "\n")]
    {:success? true
     :artifact (assoc artifact :content fixed)
     :fix-description "Ensured file ends with single newline"}))
