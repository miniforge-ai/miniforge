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

(ns ai.miniforge.tui-engine.interface.layout
  "Public layout API for tui-engine.

   Re-exports layout primitives so that consuming components can depend
   on the interface boundary rather than internal namespaces."
  (:require
   [ai.miniforge.tui-engine.layout :as layout]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Cell buffer operations

(def empty-cell
  "Default empty cell: {:char \\space :fg :white :bg :black :bold? false}"
  layout/empty-cell)

(def make-buffer
  "Create an empty cell buffer of [cols rows].
   Returns a 2D vector of empty-cell maps."
  layout/make-buffer)

(def buf-put-char
  "Put a single character at [col row] in buffer.
   cell is a map: {:char :fg :bg :bold?}
   Returns updated buffer."
  layout/buf-put-char)

(def buf-put-string
  "Write a string into the buffer at [col row] with given style.
   style is a map: {:fg :bg :bold?}
   Returns updated buffer."
  layout/buf-put-string)

(def blit
  "Copy source buffer onto dest buffer at [col row] offset.
   Returns updated dest buffer."
  layout/blit)

(def buf->strings
  "Convert a cell buffer to a vector of plain strings (for testing).
   Returns vector of strings, one per row."
  layout/buf->strings)

;; ─────────────────────────────────────────────────────────────────────────────
;; Layout primitives

(def text
  "Render styled text span into a buffer.
   Signature: [[cols rows] content opts]
   opts: {:fg :bg :bold? :align}
   Returns cell buffer."
  layout/text)

(def box
  "Render a bordered box with optional content.
   Signature: [[cols rows] opts]
   opts: {:border :title :fg :bg :bold? :content-fn}
   Returns cell buffer."
  layout/box)

(def split-h
  "Split horizontally into left/right panes.
   Signature: [[cols rows] ratio left-fn right-fn]
   ratio is fraction [0.0, 1.0] for left pane.
   Returns cell buffer."
  layout/split-h)

(def split-v
  "Split vertically into top/bottom panes.
   Signature: [[cols rows] ratio top-fn bottom-fn]
   ratio is fraction [0.0, 1.0] for top pane.
   Returns cell buffer."
  layout/split-v)

(def table
  "Render a data table with column headers and rows.
   Signature: [[cols rows] opts]
   opts: {:columns :data :selected-row :header-fg :header-bg :row-fg :row-bg}
   Returns cell buffer."
  layout/table)

(def pad
  "Add padding around content-fn's output.
   Signature: [[cols rows] padding content-fn]
   padding: [top right bottom left] or single int.
   Returns cell buffer."
  layout/pad)
