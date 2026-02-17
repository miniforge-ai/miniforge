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

(ns ai.miniforge.tui-views.view.interpret
  "Generic view-spec interpreter.

   Walks a declarative screen spec (from screens.edn) and produces a cell
   buffer by dispatching to layout primitives and widget renderers. This
   replaces per-view hand-constructed render functions with data.

   The interpreter is the 'eval' stage in the lex→parse→eval pipeline:
     screens.edn (spec) → interpret (eval) → cell buffer

   Layer 2: Depends on layout primitives and projection registry."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-engine.interface.widget :as widget]
   [ai.miniforge.tui-views.view.project :as project]
   [ai.miniforge.tui-views.views.tab-bar :as tab-bar]))

;------------------------------------------------------------------------------ Layer 0
;; Screen spec loading

(def ^:private screen-specs
  "Load screen specs from EDN at namespace init time."
  (-> (io/resource "config/tui/screens.edn") slurp edn/read-string))

(defn get-screen-spec
  "Look up a screen spec by view keyword."
  [view-kw]
  (get screen-specs view-kw))

(defn screen-names
  "Return all registered screen names."
  []
  (keys screen-specs))

;------------------------------------------------------------------------------ Layer 1
;; Footer rendering

(defn- resolve-footer-segment
  "Pick the right footer segment based on model state."
  [model segments]
  (let [sel-count (count (:selected-ids model))
        visual? (:visual-anchor model)
        filtered? (:filtered-indices model)]
    (cond
      (and (pos? sel-count) (:selected segments))
      (str/replace (str (:selected segments)) "{n}" (str sel-count))

      (and visual? (:visual segments))
      (:visual segments)

      (and filtered? (:filtered segments))
      (str/replace (str (:filtered segments)) "{n}"
                   (str (count filtered?)))

      :else
      (or (:normal segments) ""))))

(defn- render-footer
  "Render a footer node. Appends flash message if present."
  [model theme [cols rows] segments]
  (let [base (resolve-footer-segment model segments)
        flash (:flash-message model)
        text (str " " base (when flash (str "  │ " flash)))]
    (layout/text [cols rows] text {:fg (get theme :fg-dim :default)})))

;------------------------------------------------------------------------------ Layer 1
;; Widget rendering

(defn- resolve-columns
  "Resolve :flex width columns to fill remaining space."
  [columns cols sel-w]
  (let [fixed (reduce + sel-w (map (fn [c] (if (= :flex (:width c)) 0 (:width c)))
                                    columns))
        flex-count (count (filter #(= :flex (:width %)) columns))
        flex-w (if (pos? flex-count)
                 (max 10 (quot (- cols fixed) flex-count))
                 0)]
    (mapv (fn [c] (if (= :flex (:width c)) (assoc c :width flex-w) c)) columns)))

(defn- render-table-widget
  "Render a table widget from spec."
  [model theme [cols rows] {:keys [data-fn columns selectable? selection-col?]}]
  (let [project-fn (project/get-projection data-fn)
        data-raw (project-fn model)
        selected (:selected-idx model)
        selected-ids (or (:selected-ids model) #{})
        show-sel? (and selection-col? (seq selected-ids))
        sel-w (if show-sel? 3 0)
        ;; Add selection column to data if needed
        data (if show-sel?
               (mapv (fn [row]
                       (assoc row :sel
                              (if (contains? selected-ids
                                             (or (:_id row) (:id row)))
                                "[x]" "[ ]")))
                     data-raw)
               data-raw)
        resolved-cols (cond-> []
                        show-sel? (conj {:key :sel :header "   " :width 3})
                        true (into (resolve-columns columns cols sel-w)))]
    (if (empty? data)
      (layout/text [cols rows] "  No data available."
                   {:fg (get theme :fg :default)})
      (layout/table [cols rows]
        {:columns resolved-cols :data data
         :selected-row (when selectable? selected)
         :header-fg (get theme :header :cyan)
         :row-fg (get theme :row-fg :default)
         :row-bg (get theme :row-bg :default)
         :selected-fg (get theme :selected-fg :white)
         :selected-bg (get theme :selected-bg :blue)}))))

(defn- render-tree-widget
  "Render a tree widget from spec."
  [model _theme [cols rows] {:keys [data-fn]}]
  (let [project-fn (project/get-projection data-fn)
        nodes (project-fn model)
        expanded (or (get-in model [:detail :expanded-nodes]) #{0})]
    (widget/tree [cols rows]
      {:nodes nodes
       :expanded expanded
       :selected (:selected-idx model)})))

(defn- render-kanban-widget
  "Render a kanban widget from spec."
  [model _theme [cols rows] {:keys [data-fn]}]
  (let [project-fn (project/get-projection data-fn)
        columns (project-fn model)]
    (widget/kanban [cols rows] {:columns columns})))

;------------------------------------------------------------------------------ Layer 2
;; Recursive spec interpreter

(declare interpret-node)

(defn- interpret-split-v
  "Interpret a vertical split node."
  [model theme [cols rows] {:keys [ratio children]}]
  (let [r (if (= :footer ratio)
            (/ (- rows 2.0) rows)
            ratio)]
    (layout/split-v [cols rows] r
      (fn [size] (interpret-node model theme size (first children)))
      (fn [size] (interpret-node model theme size (second children))))))

(defn- interpret-split-h
  "Interpret a horizontal split node."
  [model theme [cols rows] {:keys [ratio children]}]
  (layout/split-h [cols rows] ratio
    (fn [size] (interpret-node model theme size (first children)))
    (fn [size] (interpret-node model theme size (second children)))))

(defn- interpret-box
  "Interpret a box node."
  [model theme [cols rows] {:keys [title title-fn child]}]
  (let [resolved-title (if title-fn
                         (let [ctx-fn (project/get-context title-fn)]
                           (ctx-fn model))
                         title)]
    (layout/box [cols rows]
      {:title resolved-title :border :single :fg (get theme :border :default)
       :content-fn (fn [size] (interpret-node model theme size child))})))

(defn- interpret-text
  "Interpret a text node."
  [_model theme [cols rows] {:keys [content style]}]
  (layout/text [cols rows] content
    (merge {:fg (get theme :fg :default)} style)))

(defn interpret-node
  "Interpret a single view-spec node, dispatching by :type."
  [model theme size node]
  (case (:type node)
    :split-v (interpret-split-v model theme size node)
    :split-h (interpret-split-h model theme size node)
    :box     (interpret-box model theme size node)
    :text    (interpret-text model theme size node)
    :footer  (render-footer model theme size (:segments node))
    :widget  (case (:widget node)
               :table  (render-table-widget model theme size node)
               :tree   (render-tree-widget model theme size node)
               :kanban (render-kanban-widget model theme size node)
               (layout/text size "Unknown widget"))
    ;; Fallback
    (layout/text size "Unknown node type")))

;------------------------------------------------------------------------------ Layer 3
;; Top-level screen rendering

(defn render-screen
  "Render a complete screen from its spec.
   Handles tab-bar / title-bar header + body."
  [model theme [cols rows] screen-spec]
  (let [{:keys [tab-bar title-bar body]} screen-spec]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Header: tab-bar or title-bar
      (fn [[c r]]
        (cond
          tab-bar
          (let [context (when-let [ctx-fn-kw (:context-fn tab-bar)]
                          (let [ctx-fn (project/get-context ctx-fn-kw)]
                            (ctx-fn model)))]
            (tab-bar/render model context [c r]))

          title-bar
          (let [text (if-let [text-fn-kw (:text-fn title-bar)]
                       (let [ctx-fn (project/get-context text-fn-kw)]
                         (ctx-fn model))
                       "MINIFORGE")]
            (layout/text [c r] text
              {:fg (get theme :header :cyan) :bold? true}))

          :else
          (tab-bar/render model nil [c r])))
      ;; Body
      (fn [size]
        (interpret-node model theme size body)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (keys screen-specs)
  (get-screen-spec :workflow-list)
  :leave-this-here)
