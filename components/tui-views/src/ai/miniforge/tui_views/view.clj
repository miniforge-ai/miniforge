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

(ns ai.miniforge.tui-views.view
  "View dispatch: model -> cell buffer.

   Routes to the correct view renderer based on :view key in model.
   Composes the view tab bar, command/search bar, and active view."
  (:require
   [ai.miniforge.tui-engine.interface :as engine]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.views.workflow-list :as wf-list]
   [ai.miniforge.tui-views.views.workflow-detail :as wf-detail]
   [ai.miniforge.tui-views.views.evidence :as evidence]
   [ai.miniforge.tui-views.views.artifact-browser :as artifact-browser]
   [ai.miniforge.tui-views.views.dag-kanban :as dag-kanban]
   [ai.miniforge.tui-views.views.pr-fleet :as pr-fleet]
   [ai.miniforge.tui-views.views.pr-detail :as pr-detail]
   [ai.miniforge.tui-views.views.train-view :as train-view]
   [ai.miniforge.tui-views.views.repo-manager :as repo-manager]
   [ai.miniforge.tui-views.views.help :as help]))

;------------------------------------------------------------------------------ Layer 0
;; View dispatch

(defn- render-active-view
  "Dispatch to the active view renderer."
  [model size]
  (case (:view model)
    :workflow-list    (wf-list/render model size)
    :workflow-detail  (wf-detail/render model size)
    :evidence         (evidence/render model size)
    :artifact-browser (artifact-browser/render model size)
    :dag-kanban       (dag-kanban/render model size)
    :pr-fleet         (pr-fleet/render model size)
    :pr-detail        (pr-detail/render model size)
    :train-view       (train-view/render model size)
    :repo-manager     (repo-manager/render model size)
    ;; Fallback
    (layout/text size "Unknown view")))

;------------------------------------------------------------------------------ Layer 1
;; Command/search bar overlay

(defn- render-command-bar
  "Render the command or search input bar at the bottom of the screen."
  [[cols _rows] model theme]
  (when (not= :normal (:mode model))
    (layout/text [cols 1] (:command-buf model)
                 {:fg (get theme :command-fg :white)
                  :bg (get theme :command-bg :default)
                  :bold? true})))

;------------------------------------------------------------------------------ Layer 2
;; Theme post-processing

(defn- apply-theme-bg
  "Replace :bg :default cells with the theme's background color.
   This ensures the entire screen has a consistent background."
  [buffer theme]
  (let [bg (:bg theme :default)]
    (if (= :default bg)
      buffer  ; No-op if theme bg is :default
      (mapv (fn [row]
              (mapv (fn [cell]
                      (if (= :default (:bg cell))
                        (assoc cell :bg bg)
                        cell))
                    row))
            buffer))))

;------------------------------------------------------------------------------ Layer 3
;; Root view function

(defn root-view
  "Root view function for the Elm runtime.
   Resolves theme from model, threads it through sub-views,
   and applies theme background as post-processing.
   model -> [cols rows] -> cell-buffer"
  [model [cols rows]]
  (if (< rows 4)
    (layout/text [cols rows] "Terminal too small" {:fg :red})
    (let [;; Resolve theme and attach to model for sub-views
          theme (engine/get-theme (:theme model))
          model (assoc model :resolved-theme theme)
          ;; Reserve 1 row for command bar if in command/search mode
          cmd-active? (not= :normal (:mode model))
          content-rows (if cmd-active? (dec rows) rows)
          ;; Render main view
          content (render-active-view model [cols content-rows])
          ;; Build base buffer
          base0 (if cmd-active?
                  (let [buf (layout/make-buffer [cols rows])
                        cmd-bar (render-command-bar [cols 1] model theme)]
                    (-> buf
                        (layout/blit content 0 0)
                        (layout/blit cmd-bar 0 (dec rows))))
                  content)
          ;; Overlay completion popup if active
          base1 (if (and (:completing? model) (seq (:completions model)))
                  (let [completions (:completions model)
                        idx (:completion-idx model)
                        max-visible 8
                        visible (vec (take max-visible completions))
                        longest (apply max 1 (map count visible))
                        popup-w (min (+ longest 4) (- cols 4))
                        popup-h (+ 2 (count visible))  ;; border top/bottom + items
                        x-off 0
                        y-off (max 0 (- rows 1 popup-h))
                        popup (layout/box [popup-w popup-h]
                                {:border :single
                                 :fg (get theme :border-focus (get theme :border :default))
                                 :content-fn
                                 (fn [[ic ir]]
                                   (reduce
                                     (fn [b [i item]]
                                       (let [selected? (= i idx)
                                             label (subs item 0 (min (count item) ic))]
                                         (layout/buf-put-string b 1 i label
                                           {:fg (if selected?
                                                  (get theme :selected-fg :white)
                                                  (get theme :fg :default))
                                            :bg (if selected?
                                                  (get theme :selected-bg :blue)
                                                  :default)
                                            :bold? selected?})))
                                     (layout/make-buffer [ic ir])
                                     (map-indexed vector visible)))})]
                    (layout/blit base0 popup x-off y-off))
                  base0)
          ;; Overlay help if visible
          base2 (if (:help-visible? model)
                  (let [help-buf (help/render model [cols rows])]
                    (layout/blit base1 help-buf 0 0))
                  base1)
          ;; Overlay confirmation prompt if active
          base3 (if-let [{:keys [action label ids]} (:confirm model)]
                  (let [msg (str label " " (count ids) " item(s)?")
                        hint "(y)es / (n)o"
                        box-w (min (+ (max (count msg) (count hint)) 4) (- cols 4))
                        box-h 5
                        x-off (max 0 (quot (- cols box-w) 2))
                        y-off (max 0 (quot (- rows box-h) 2))
                        overlay (layout/box [box-w box-h]
                                  {:title (str " " (name action) " ")
                                   :border :single
                                   :fg :yellow
                                   :content-fn
                                   (fn [[ic ir]]
                                     (let [buf (layout/make-buffer [ic ir])
                                           buf (layout/buf-put-string buf 1 0 msg
                                                 {:fg :white :bg :default :bold? true})
                                           buf (layout/buf-put-string buf 1 1 hint
                                                 {:fg :default :bg :default :bold? false})]
                                       buf))})]
                    (layout/blit base2 overlay x-off y-off))
                  base2)]
      ;; Apply theme background to all :default bg cells
      (apply-theme-bg base3 theme))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m (model/init-model))
  (layout/buf->strings (root-view m [80 24]))
  :leave-this-here)
