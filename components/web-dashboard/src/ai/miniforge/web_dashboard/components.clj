;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.components
  "Reusable Hiccup component library for web dashboard.

   Components are pure functions that return Hiccup data structures.
   Each component follows a consistent pattern: (component content & [opts])

   This namespace re-exports all components from sub-namespaces for backwards compatibility.

   Organization:
   - ai.miniforge.web-dashboard.components.primitives - Layer 0: button, badge, card, stat-card, progress-bar, status-dot, icon
   - ai.miniforge.web-dashboard.components.layouts - Layer 1: page, panel, grid, split
   - ai.miniforge.web-dashboard.components.data - Layer 2: table, tree, timeline"
  (:require
   [ai.miniforge.web-dashboard.components.primitives :as primitives]
   [ai.miniforge.web-dashboard.components.layouts :as layouts]
   [ai.miniforge.web-dashboard.components.data :as data]))

;------------------------------------------------------------------------------ Re-exports

;; Layer 0: Primitives
(def button primitives/button)
(def badge primitives/badge)
(def card primitives/card)
(def stat-card primitives/stat-card)
(def progress-bar primitives/progress-bar)
(def status-dot primitives/status-dot)
(def icon primitives/icon)

;; Layer 1: Layouts
(def page layouts/page)
(def panel layouts/panel)
(def grid layouts/grid)
(def split layouts/split)

;; Layer 2: Data Display
(def table data/table)
(def tree data/tree)
(def timeline data/timeline)
