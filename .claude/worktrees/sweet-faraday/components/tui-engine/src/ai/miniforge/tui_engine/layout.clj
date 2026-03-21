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

(ns ai.miniforge.tui-engine.layout
  "Layout primitives for TUI rendering.

   Convenience namespace that re-exports all layout functions from stratified
   sub-namespaces (buffer, container, table). Each function takes a size
   [cols rows] and options, returning a cell buffer -- a 2D vector of
   {:char :fg :bg :bold?} maps.

   Layout functions are pure -- they take data and return data. No side effects."
  (:require
   [ai.miniforge.tui-engine.layout.buffer :as buffer]
   [ai.miniforge.tui-engine.layout.container :as container]
   [ai.miniforge.tui-engine.layout.table :as table]))

;; Re-export from buffer
(def empty-cell buffer/empty-cell)
(def make-buffer buffer/make-buffer)
(def buf-put-char buffer/buf-put-char)
(def buf-put-string buffer/buf-put-string)
(def blit buffer/blit)
(def buf->strings buffer/buf->strings)
(def text buffer/text)

;; Re-export from container
(def box container/box)
(def split-h container/split-h)
(def split-v container/split-v)

;; Re-export from table
(def table table/table)
(def pad table/pad)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Quick layout test
  (buf->strings (text [20 1] "Hello, world!"))
  ;; => ["Hello, world!       "]

  (buf->strings (box [20 5] {:title "Test" :border :single}))
  ;; => ["┌─Test──────────────┐"
  ;;     "│                  │"
  ;;     "│                  │"
  ;;     "│                  │"
  ;;     "└──────────────────┘"]

  :leave-this-here)
