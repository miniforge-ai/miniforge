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

(ns ai.miniforge.tui-views.palette
  "Shared RGB color palette for TUI views.

   Fixed status colors with good contrast on both light and dark terminal
   backgrounds. ANSI keywords are terminal-dependent, so these use explicit
   RGB triples to guarantee consistent appearance.

   All view namespaces that need status or accent colors should reference
   these defs rather than inlining RGB literals.")

;; ─────────────────────────────────────────────────────────────────────────────
;; Semantic status colors — fixed RGB values

(def status-pass    [0 180 80])      ;; medium green
(def status-fail    [220 50 40])     ;; medium red
(def status-warning [200 160 0])     ;; darker yellow/gold
(def status-info    [0 150 180])     ;; teal/darker cyan
