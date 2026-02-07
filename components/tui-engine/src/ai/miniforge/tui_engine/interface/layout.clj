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

(def empty-cell layout/empty-cell)
(def make-buffer layout/make-buffer)
(def buf-put-char layout/buf-put-char)
(def buf-put-string layout/buf-put-string)
(def blit layout/blit)
(def buf->strings layout/buf->strings)

;; ─────────────────────────────────────────────────────────────────────────────
;; Layout primitives

(def text layout/text)
(def box layout/box)
(def split-h layout/split-h)
(def split-v layout/split-v)
(def table layout/table)
(def pad layout/pad)
