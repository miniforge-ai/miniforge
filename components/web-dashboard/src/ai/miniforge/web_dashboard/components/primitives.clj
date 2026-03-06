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

(ns ai.miniforge.web-dashboard.components.primitives
  "Primitive UI components for web dashboard.

   Layer 0: Primitives
   - button: Interactive button with variants and sizes
   - badge: Label and status indicator
   - card: Content container with optional header/footer
   - stat-card: Dashboard metric display
   - progress-bar: Completion percentage indicator
   - status-dot: Colored status indicator
   - icon: Unicode icon component"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Helper

(defn- build-class
  "Build CSS class string from opts and modifiers."
  [{:keys [class]} base-class & modifiers]
  (str/join " " (filter some? (concat [base-class] modifiers [class]))))

;------------------------------------------------------------------------------ Layer 0: Primitives

(defn button
  "Button component with variants and sizes.

   Options:
   - :variant - :primary, :secondary, :danger, :ghost (default: :primary)
   - :size - :sm, :md, :lg (default: :md)
   - :disabled - boolean (default: false)
   - :class - additional CSS classes

   Example: (button \"Approve\" {:variant :primary :size :md})"
  [label & [{:keys [variant size disabled] :or {variant :primary size :md} :as opts}]]
  (let [html-attrs (dissoc opts :variant :size :disabled :class)]
    [:button
     (merge
      {:class (build-class opts "btn" (str "btn-" (name variant)) (str "btn-" (name size)))
       :disabled disabled}
      html-attrs)
     label]))

(defn badge
  "Badge component for labels and status indicators.

   Options:
   - :variant - :success, :warning, :error, :info, :neutral (default: :neutral)
   - :icon - optional icon string to display before label
   - :class - additional CSS classes

   Example: (badge \"HIGH\" {:variant :error :icon \"🔴\"})"
  [label & [{:keys [variant icon] :or {variant :neutral} :as opts}]]
  [:span
   {:class (build-class opts "badge" (str "badge-" (name variant)))}
   (when icon [:span.badge-icon icon])
   label])

(defn card
  "Card component with optional title and footer.

   Options:
   - :title - card title string
   - :footer - footer hiccup content
   - :class - additional CSS classes

   Example: (card [:p \"Content\"] {:title \"Card Title\"})"
  [content & [{:keys [title footer] :as opts}]]
  [:div {:class (build-class opts "card")}
   (when title [:div.card-header title])
   [:div.card-body content]
   (when footer [:div.card-footer footer])])

(defn stat-card
  "Metric card for dashboard with large value and label.

   Options:
   - :trend - :up, :down, :neutral (optional)
   - :class - additional CSS classes
   - :href - navigation link (optional)

   Example: (stat-card \"47\" \"PRs\" {:trend :up :href \"/fleet\"})"
  [value label & [{:keys [trend href] :as opts}]]
  [:div (merge {:class (build-class opts "stat-card")}
               (when href {:onclick (str "location.href='" href "'")}))
   [:div.stat-value value]
   [:div.stat-label label]
   (when trend
     [:div.stat-trend {:class (name trend)}
      (case trend
        :up "↑"
        :down "↓"
        :neutral "→"
        "")])])

(defn progress-bar
  "Progress bar component showing completion percentage.

   Options:
   - :show-label - show percentage label (default: false)
   - :variant - :primary, :success, :warning, :error (default: :primary)
   - :class - additional CSS classes

   Example: (progress-bar 70 {:show-label true})"
  [percent & [{:keys [show-label variant] :or {variant :primary} :as opts}]]
  [:div {:class (build-class opts "progress")}
   [:div.progress-fill
    {:style (str "width: " percent "%")
     :class (str "progress-" (name variant))}]
   (when show-label [:div.progress-label (str percent "%")])])

(defn status-dot
  "Colored status indicator dot.

   Statuses: :running, :completed, :failed, :pending, :blocked

   Example: (status-dot :running)"
  [status]
  [:span {:class (str "status-dot " (name status))} "●"])

(defn icon
  "Unicode icon component with optional label.

   Icons: :check, :cross, :clock, :dot, :circle, :arrow-right, :arrow-down, :arrow-right-small

   Options:
   - :class - additional CSS classes

   Example: (icon :check {:class \"success\"})"
  [name & [{:keys [] :as opts}]]
  (let [icons {:check "✓" :cross "✗" :clock "⏳" :dot "●" :circle "○"
               :arrow-right "→" :arrow-down "▼" :arrow-right-small "▸"}]
    [:span {:class (build-class opts "icon")} (get icons name "")]))
