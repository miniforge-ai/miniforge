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
(ns ai.miniforge.patterns.core
  "Centralized regex pattern registry.

   Named patterns for reuse across components. Each pattern has a
   descriptive name so its purpose is clear at the call site.")

;------------------------------------------------------------------------------ Layer 0

;; Markdown / file-path extraction patterns
(def ^{:stratum 0} md-heading-file-path
  #"#{1,4}\s+[`*]*([^\s`*]+\.\w{1,6})[`*]*")

(def ^{:stratum 0} md-delimited-file-path
  #"[`*]+([^\s`*]+\.\w{1,6})[`*]+")

(def ^{:stratum 0} md-label-file-path
  #"(?i)(?:file|path):\s*`?([^\s`]+\.\w{1,6})`?")

(def ^{:stratum 0} md-code-block
  #"```(?:(\w+)\n)?([^`]+)```")

;; File extension patterns
(def ^{:stratum 0} file-extension
  #"\.(\w+)$")

;; EDN / structured content patterns
(def ^{:stratum 0} edn-code-block
  #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```")

(def ^{:stratum 0} inline-already-implemented
  #"\{:status\s+:already-implemented[^}]*\}")

;; Rate-limit detection (canonical pattern — use this everywhere)
(def ^{:stratum 0} rate-limit
  #"(?i)you've hit your limit|rate.?limit|429|quota.?exceeded|resets \d+[ap]m")
