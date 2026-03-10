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

(ns ai.miniforge.tui-views.update.completion
  "Tab-completion for command mode.

   Pure functions that manage the completion popup state.
   Depends on command.clj for completion metadata.
   Layer 3."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.update.command :as command]))

;------------------------------------------------------------------------------ Layer 0
;; State helpers

(defn dismiss
  "Clear completion state."
  [model]
  (assoc model :completing? false :completions [] :completion-idx nil))

(defn apply-browse-side-effect
  [model result]
  (if-let [fx (:side-effect result)]
    (assoc model
           :side-effect fx
           :browse-repos-loading? true
           :flash-message "Browsing remote repos...")
    model))

(defn strip-browse-sentinel
  [completions]
  (->> completions
       (remove #{"browse"})
       vec))

;------------------------------------------------------------------------------ Layer 1
;; Navigation

(defn next-completion
  "Move to the next completion option (wraps around)."
  [model]
  (let [n (count (:completions model))
        idx (get model :completion-idx 0)]
    (assoc model :completion-idx (mod (inc idx) n))))

(defn prev-completion
  "Move to the previous completion option (wraps around)."
  [model]
  (let [n (count (:completions model))
        idx (get model :completion-idx 0)]
    (assoc model :completion-idx (mod (+ idx (dec n)) n))))

;------------------------------------------------------------------------------ Layer 2
;; Accept / fill

(defn accept
  "Accept the currently highlighted completion into the command buffer.
   Returns model with buffer updated and completion dismissed."
  [model]
  (let [idx (:completion-idx model)
        selected (get (:completions model) idx)]
    (if (nil? selected)
      (dismiss model)
      (let [buf (:command-buf model)
            ;; Check if we're completing a command name or an argument
            has-space? (str/includes? (subs buf 1) " ")]
        (if has-space?
          ;; Replace the partial argument: keep ":cmd " prefix, append selected
          (let [cmd-part (first (str/split (subs buf 1) #"\s+" 2))]
            (if (= selected "browse")
              (let [next-buf (str ":" cmd-part " ")
                    next-model (assoc model :command-buf next-buf)
                    result (command/compute-completions next-model next-buf)
                    completions (strip-browse-sentinel (:completions result))]
                (-> (apply-browse-side-effect next-model result)
                    (assoc :completing? (boolean (seq completions))
                           :completions completions
                           :completion-idx (when (seq completions) 0))))
              (-> model
                  (assoc :command-buf (str ":" cmd-part " " selected))
                  dismiss)))
          ;; Replace the command name
          (-> model
              (assoc :command-buf (str ":" selected " "))
              dismiss))))))

;------------------------------------------------------------------------------ Layer 3
;; Tab handler

(defn exact-command-arg-completions
  [model buf]
  (let [partial (subs buf 1)]
    (when (and (not (str/blank? partial))
               (some #{partial} (command/complete-command-name partial)))
      {:buffer (str ":" partial " ")
       :result (command/compute-completions model (str ":" partial " "))})))

(defn handle-tab
  "Handle Tab press in command mode.
   If not completing: compute completions and open popup.
   If already completing: cycle to next option."
  [model]
  (if (:completing? model)
    ;; Already completing -- cycle forward
    (next-completion model)
    ;; Not completing -- compute options
    (let [buf (:command-buf model)
          has-space? (str/includes? (subs buf 1) " ")
          exact-arg (when-not has-space? (exact-command-arg-completions model buf))
          result (cond
                   has-space?
                   ;; Completing an argument
                   (command/compute-completions model buf)

                   exact-arg
                   (:result exact-arg)

                   :else
                   ;; Completing a command name
                   (let [partial (subs buf 1)
                         names (command/complete-command-name partial)]
                     {:completions names :partial partial}))]
      (cond
        (seq (:completions result))
        (-> (apply-browse-side-effect model result)
            (cond-> exact-arg
              (assoc :command-buf (:buffer exact-arg)))
            (assoc :completing? true
                   :completions (:completions result)
                   :completion-idx 0))

        (:side-effect result)
        (-> (apply-browse-side-effect model result)
            (cond-> exact-arg
              (assoc :command-buf (:buffer exact-arg))))

        ;; No completions available -- no-op
        :else model))))

(defn handle-shift-tab
  "Handle Shift+Tab press in command mode.
   If not completing: compute completions and select last item.
   If already completing: cycle to previous option."
  [model]
  (if (:completing? model)
    ;; Already completing -- cycle backward
    (prev-completion model)
    ;; Not completing -- compute options, select last
    (let [buf (:command-buf model)
          has-space? (str/includes? (subs buf 1) " ")
          exact-arg (when-not has-space? (exact-command-arg-completions model buf))
          result (cond
                   has-space?
                   (command/compute-completions model buf)

                   exact-arg
                   (:result exact-arg)

                   :else
                   (let [partial (subs buf 1)
                         names (command/complete-command-name partial)]
                     {:completions names :partial partial}))]
      (cond
        (seq (:completions result))
        (-> (apply-browse-side-effect model result)
            (cond-> exact-arg
              (assoc :command-buf (:buffer exact-arg)))
            (assoc :completing? true
                   :completions (:completions result)
                   :completion-idx (dec (count (:completions result)))))

        (:side-effect result)
        (-> (apply-browse-side-effect model result)
            (cond-> exact-arg
              (assoc :command-buf (:buffer exact-arg))))

        :else model))))
