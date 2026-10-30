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
(ns ai.miniforge.workflow.stop-signal
  "Make a cancel reach work in flight. A cancel only sets `:stopped` in the
   run's control state, which the runner reads at phase boundaries and the
   DAG loop before each batch. These hooks act on it the moment it is set:
   the thread running a phase is interrupted (both LLM paths kill the
   agent CLI's process tree on interrupt), and a DAG batch's task futures
   are cancelled.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} on-stop!
  "Call `f` once when `control-state` (an atom) becomes stopped: from the
   thread that stops it, or at once on this thread when it already is.
   Returns a no-arg fn that removes the hook; a nil control state gets no
   hook."
  [control-state f]
  (if (nil? control-state)
    (fn [])
    (let [hook   (Object.)
          fired  (atom false)
          fire!  #(when (compare-and-set! fired false true) (f))]
      (add-watch control-state hook
                 (fn [_ _ old new]
                   (when (and (:stopped new) (not (:stopped old)))
                     (fire!))))
      (when (:stopped @control-state) (fire!))
      #(remove-watch control-state hook))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} interrupting-on-stop
  "`(f)` on this thread, interrupting the thread if `control-state` is
   stopped while `f` runs, so blocking work in it (an LLM call waiting on
   its CLI) is abandoned. An interrupt this caused is cleared before
   returning, so what follows (the checkpoint write) runs uninterrupted;
   the stop itself stays in the control state for the next boundary. An
   interrupt from elsewhere is left alone."
  [control-state f]
  (let [thread      (Thread/currentThread)
        interrupted (atom false)
        remove-hook (on-stop! control-state
                              #(do (reset! interrupted true)
                                   (.interrupt thread)))]
    (try
      (f)
      (finally
        (remove-hook)
        (when @interrupted (Thread/interrupted))))))
