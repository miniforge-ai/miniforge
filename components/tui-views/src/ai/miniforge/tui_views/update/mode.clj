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

(ns ai.miniforge.tui-views.update.mode
  "Mode switching and command buffer manipulation.

   Pure functions for switching between normal/command/search modes.
   Layer 3.")

;------------------------------------------------------------------------------ Layer 3
;; Mode switching

(defn enter-command-mode [model]
  (assoc model :mode :command :command-buf ":"))

(defn enter-search-mode [model]
  (assoc model :mode :search :command-buf "/" :search-results []))

(defn exit-mode [model]
  (assoc model :mode :normal :command-buf "" :search-results []))

(defn command-append [model ch]
  (update model :command-buf str ch))

(defn command-backspace [model]
  (let [buf (:command-buf model)]
    (if (> (count buf) 1)
      (assoc model :command-buf (subs buf 0 (dec (count buf))))
      (exit-mode model))))
