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
(ns ai.miniforge.cli.web.components-test.fixtures
  "Shared sample data for the `components-test` sibling namespaces (rule
   210: the original `components_test.clj` measured 4 real layers, over
   the 3-layer budget). A PR analysis, a PR carrying that analysis, and a
   fleet carrying that PR — the layer-coherent common base the
   detail-panel and dashboard sibling tests build on.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} sample-analysis
  {:risk :low
   :complexity :simple
   :summary "Documentation update"
   :suggested-action "Safe to merge"
   :reasons ["2 files modified"]
   :total-changes 18
   :file-count 2})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} sample-selected-pr
  {:number 42
   :title "Improve dashboard coverage"
   :author {:login "chris"}
   :url "https://example.test/pr/42"
   :repo "miniforge"
   :additions 12
   :deletions 6
   :analysis sample-analysis})

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} sample-fleet
  [{:repo "miniforge"
    :prs [sample-selected-pr]}])
