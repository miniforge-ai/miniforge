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

(ns ai.miniforge.repo-index.schema
  "Malli schemas for repo index domain types.

   Layer 0 — pure data definitions, no dependencies.")

;------------------------------------------------------------------------------ Layer 0
;; File Record

(def FileRecord
  "Schema for a single file entry in the repo index."
  [:map
   [:path [:string {:min 1}]]
   [:blob-sha [:string {:min 7}]]
   [:size :int]
   [:lines :int]
   [:language [:maybe :string]]
   [:generated? :boolean]])

;------------------------------------------------------------------------------ Layer 0
;; Repo Index

(def RepoIndex
  "Schema for the complete repo index."
  [:map
   [:tree-sha [:string {:min 7}]]
   [:repo-root [:string {:min 1}]]
   [:files [:vector FileRecord]]
   [:file-count :int]
   [:total-lines :int]
   [:languages [:map-of :string :int]]
   [:indexed-at inst?]])

;------------------------------------------------------------------------------ Layer 0
;; Repo Map Slice

(def RepoMapEntry
  "Schema for a single entry in the repo map."
  [:map
   [:path [:string {:min 1}]]
   [:lang [:maybe :string]]
   [:lines :int]
   [:size :int]])

(def RepoMapSlice
  "Schema for a token-budgeted repo map slice."
  [:map
   [:tree-sha [:string {:min 7}]]
   [:entries [:vector RepoMapEntry]]
   [:total-files :int]
   [:shown-files :int]
   [:truncated? :boolean]
   [:token-estimate :int]])

;------------------------------------------------------------------------------ Layer 0
;; Search Hit

(def Snippet
  "Schema for a search result snippet."
  [:map
   [:start-line :int]
   [:end-line :int]
   [:text [:string {:min 0}]]])

(def SearchHit
  "Schema for a single search result."
  [:map
   [:path [:string {:min 1}]]
   [:score :double]
   [:snippets [:vector Snippet]]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[malli.core :as m])

  (m/validate FileRecord
    {:path "src/core.clj"
     :blob-sha "abc1234"
     :size 1024
     :lines 42
     :language "clojure"
     :generated? false})
  ;; => true

  :leave-this-here)
