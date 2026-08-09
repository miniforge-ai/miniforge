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
(ns ai.miniforge.buzzword-bingo.interface
  "Public API for the buzzword-bingo component.

   Today: prepares a document for counting by blanking the regions that
   are not authored prose. The counter that consumes it arrives with
   the term catalog."
  (:require
   [ai.miniforge.buzzword-bingo.segment :as segment]))

;------------------------------------------------------------------------------ Layer 0

;; Text preparation
(defn ^{:stratum 0} prose-only
  "Return `text` with code, links, paths and quotations blanked out.

   The result is the same length as `text`, so offsets into it address
   the source. `:score-quotes?` in `opts` keeps blockquoted lines."
  ([text] (segment/prose-only text))
  ([text opts] (segment/prose-only text opts)))

(comment
  (prose-only "Use `robust` in code but not in prose."))
