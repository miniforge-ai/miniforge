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
(ns ai.miniforge.cli.main.commands.artifact-cmds.provenance-view
  "Renders a provenance detail block for the `artifact provenance` command.
   Split out of `ai.miniforge.cli.main.commands.artifact-cmds` (rule 210: the
   parent namespace measured 4 real layers, max 3; the header/fields/sections
   spec for provenance and its renderer are layer-coherent on their own)."
  (:require
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} keyword->str
  "Convert a value to string, rendering keywords as their name."
  [v]
  (if (keyword? v) (name v) (str v)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private provenance-spec
  {:header   :artifact/provenance-header
   :fields   [[:artifact/workflow-id :artifact/provenance-workflow {:transform keyword->str}]
              [:artifact/phase       :artifact/provenance-phase    {:transform keyword->str}]
              [:artifact/agent-id    :artifact/provenance-agent    {:transform keyword->str}]
              [:artifact/git-commit  :artifact/provenance-commit   {:transform keyword->str}]
              [:artifact/created-at  :artifact/provenance-created  {:transform keyword->str}]]
   :sections [{:key :artifact/parent-ids :header :artifact/provenance-parents
               :entry :artifact/provenance-parent-entry :entry-fn (fn [id] {:id id})}
              {:key :artifact/files :header :artifact/provenance-files
               :entry :artifact/provenance-file-entry :entry-fn (fn [p] {:path p})}]})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} display-provenance
  "Render the full provenance block for an artifact."
  [id provenance]
  (display/render-detail (assoc provenance-spec :header-params {:id id}) provenance))
