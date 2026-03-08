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

(ns ai.miniforge.tui-views.view.overlays
  "Overlay pipeline for TUI views.

   Each overlay is a function (buf, model, theme, [cols rows]) -> buf that
   conditionally composites UI chrome on top of the main content buffer.

   Overlays:
   - Command bar: shown when in command/search mode
   - Completions: tab-completion popup above command bar
   - Help: centered keybinding reference overlay
   - Confirm: confirmation dialog for batch actions"
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.palette :as palette]
   [ai.miniforge.tui-views.views.help :as help]))

;------------------------------------------------------------------------------ Layer 0
;; Overlay functions

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

(defn- confirm-item-label
  "Format a confirmation item ID for display."
  [id]
  (cond
    (and (vector? id) (= 2 (count id)))
    (let [[repo num] id]
      (str "  " repo " #" num))
    (string? id) (str "  " id)
    :else (str "  " id)))

(defn overlay-confirm
  "Add confirmation dialog centered on screen showing affected items."
  [buf model _theme [cols rows]]
  (if-let [{:keys [action label ids]} (:confirm model)]
    (let [id-list (sort (map confirm-item-label ids))
          max-show (min (count id-list) (- rows 8))
          shown (take max-show id-list)
          truncated? (> (count id-list) max-show)
          header (str label " " (count ids) " item(s)?")
          hint "  (Y)es  /  (C)ancel"
          max-label-w (reduce max 0 (map count (cons header (cons hint shown))))
          box-w (min (+ max-label-w 4) (- cols 4))
          ;; 2 border + 1 header + items + 1 blank + 1 hint
          box-h (+ 4 (count shown) (if truncated? 1 0))
          x-off (max 0 (quot (- cols box-w) 2))
          y-off (max 0 (quot (- rows box-h) 2))
          content-h (- box-h 2) ;; inside borders
          overlay (layout/box [box-w box-h]
                    {:title (str " " (name action) " ")
                     :border :single
                     :fg palette/status-warning
                     :content-fn
                     (fn [[ic _ir]]
                       (let [cbuf (layout/make-buffer [ic content-h])
                             cbuf (layout/buf-put-string cbuf 1 0 header
                                    {:fg :default :bg :default :bold? true})
                             cbuf (reduce (fn [b [i lbl]]
                                            (layout/buf-put-string b 1 (inc i) lbl
                                              {:fg :default :bg :default :bold? false}))
                                          cbuf
                                          (map-indexed vector shown))
                             trunc-row (inc (count shown))
                             cbuf (if truncated?
                                    (layout/buf-put-string cbuf 1 trunc-row
                                      (str "  ... and " (- (count id-list) max-show) " more")
                                      {:fg :default :bg :default :bold? false})
                                    cbuf)
                             hint-row (dec content-h)]
                         (layout/buf-put-string cbuf 1 hint-row hint
                           {:fg :default :bg :default :bold? false})))})]
      (layout/blit buf overlay x-off y-off))
    buf))
