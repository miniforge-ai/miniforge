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

   Uses the declarative view-spec interpreter (screens.edn) for all views.
   Composes overlays (command bar, help, completion popup, confirmation)
   as a pipeline of named overlay functions."
  (:require
   [ai.miniforge.tui-engine.interface :as engine]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.view.interpret :as interpret]
   [ai.miniforge.tui-views.views.help :as help]))

;------------------------------------------------------------------------------ Layer 0
;; View dispatch — data-driven via screen specs

(defn render-active-view
  "Render the active view using the declarative screen spec interpreter."
  [model theme size]
  (if-let [spec (interpret/get-screen-spec (:view model))]
    (interpret/render-screen model theme size spec)
    (layout/text size "Unknown view")))

;------------------------------------------------------------------------------ Layer 1
;; Overlay pipeline — each overlay is (buf, model, theme, [cols rows]) -> buf

(defn overlay-command-bar
  "Add command/search bar at the bottom when in command/search mode."
  [buf model theme [cols rows]]
  (if (not= :normal (:mode model))
    (let [cmd-bar (layout/text [cols 1] (:command-buf model)
                    {:fg (get theme :command-fg :white)
                     :bg (get theme :command-bg :default)
                     :bold? true})]
      (layout/blit buf cmd-bar 0 (dec rows)))
    buf))

(defn overlay-completions
  "Add tab-completion popup above the command bar when completing."
  [buf model theme [cols rows]]
  (if (and (:completing? model) (seq (:completions model)))
    (let [completions (:completions model)
          idx (:completion-idx model)
          max-visible 8
          visible (vec (take max-visible completions))
          longest (apply max 1 (map count visible))
          popup-w (min (+ longest 4) (- cols 4))
          popup-h (+ 2 (count visible))
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
      (layout/blit buf popup 0 y-off))
    buf))

(defn overlay-help
  "Add help overlay centered on screen when help is visible."
  [buf model _theme [cols rows]]
  (if (:help-visible? model)
    (let [overlay (help/render-overlay [cols rows])
          ov-cols (count (first overlay))
          ov-rows (count overlay)
          x (max 0 (quot (- cols ov-cols) 2))
          y (max 0 (quot (- rows ov-rows) 2))]
      (layout/blit buf overlay x y))
    buf))

(defn overlay-confirm
  "Add confirmation dialog centered on screen when confirming."
  [buf model _theme [cols rows]]
  (if-let [{:keys [action label ids]} (:confirm model)]
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
                     (fn [[ic _ir]]
                       (-> (layout/make-buffer [ic 2])
                           (layout/buf-put-string 1 0 msg
                             {:fg :white :bg :default :bold? true})
                           (layout/buf-put-string 1 1 hint
                             {:fg :default :bg :default :bold? false})))})]
      (layout/blit buf overlay x-off y-off))
    buf))

;------------------------------------------------------------------------------ Layer 2
;; Theme post-processing

(defn apply-theme-bg
  "Replace :bg :default cells with the theme's background color."
  [buffer theme]
  (let [bg (:bg theme :default)]
    (if (= :default bg)
      buffer
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
   Resolves theme, renders the active view via the spec interpreter,
   then applies overlays as a pipeline.
   model -> [cols rows] -> cell-buffer"
  [model [cols rows]]
  (if (< rows 4)
    (layout/text [cols rows] "Terminal too small" {:fg :red})
    (let [theme (engine/get-theme (:theme model))
          model (assoc model :resolved-theme theme)
          cmd-active? (not= :normal (:mode model))
          content-rows (if cmd-active? (dec rows) rows)
          ;; Render main content via spec interpreter
          content (render-active-view model theme [cols content-rows])
          ;; Start with a full-size buffer if command bar is active
          base (if cmd-active?
                 (layout/blit (layout/make-buffer [cols rows]) content 0 0)
                 content)]
      ;; Overlay pipeline: each step gets the buffer and can modify it
      (-> base
          (overlay-command-bar model theme [cols rows])
          (overlay-completions model theme [cols rows])
          (overlay-help model theme [cols rows])
          (overlay-confirm model theme [cols rows])
          (apply-theme-bg theme)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m (ai.miniforge.tui-views.model/init-model))
  (layout/buf->strings (root-view m [80 24]))
  :leave-this-here)
