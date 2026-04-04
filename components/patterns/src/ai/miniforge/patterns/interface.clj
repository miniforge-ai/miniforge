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

(ns ai.miniforge.patterns.interface
  "Centralized, named regex patterns for reuse across components.

   Instead of inline regex literals, require this namespace and
   reference patterns by name:

     (re-find patterns/md-heading-file-path line)
     (re-find patterns/rate-limit response-text)"
  (:require [ai.miniforge.patterns.core :as core]))

;; Markdown / file-path extraction
(def md-heading-file-path    core/md-heading-file-path)
(def md-delimited-file-path  core/md-delimited-file-path)
(def md-label-file-path      core/md-label-file-path)
(def md-code-block           core/md-code-block)

;; File extensions
(def file-extension          core/file-extension)

;; EDN / structured content
(def edn-code-block          core/edn-code-block)
(def inline-already-implemented core/inline-already-implemented)

;; Rate-limit detection
(def rate-limit              core/rate-limit)
