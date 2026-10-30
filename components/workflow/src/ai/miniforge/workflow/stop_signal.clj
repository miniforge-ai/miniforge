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
   DAG loop before each batch. `interrupting-on-stop` acts on it the moment
   it is set: the thread running a phase step is interrupted (both LLM
   paths kill the agent CLI's process tree on interrupt). Work in that
   scope that waits on tasks which stop on their own (a DAG batch) runs in
   `coordinating`, so it collects their results instead.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :dynamic true} *hook*
  "The `interrupting-on-stop` scope this thread runs in, or nil."
  nil)

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

(defn ^{:stratum 0} stop-caused?
  "True when `control-state` is stopped and `t` or one of its causes is an
   interruption: the failure a stop's interrupt produced in blocking work."
  [control-state t]
  (boolean
   (and (some-> control-state deref :stopped)
        ;; ClosedByInterruptException by name: babashka does not expose it.
        (some #(or (instance? InterruptedException %)
                   (= "java.nio.channels.ClosedByInterruptException" (.getName (class %))))
              (take-while some? (iterate ex-cause t))))))

(defn- ^{:stratum 0} interrupt!
  "Interrupt `hook`'s thread, unless the scope is disarmed. The monitor
   orders it against disarming: once disarmed, no interrupt lands."
  [{:keys [monitor armed interrupted ^Thread thread]}]
  (locking monitor
    (when @armed
      (reset! interrupted true)
      (.interrupt thread))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} interrupting-on-stop
  "`(f)` on this thread, interrupting the thread if `control-state` is
   stopped while `f` runs, so blocking work in it (an LLM call waiting on
   its CLI) is abandoned. On the way out the scope is disarmed first, so a
   stop callback still in flight cannot interrupt what follows; then an
   interrupt this caused is cleared, so what follows (workspace
   persistence, the checkpoint write) runs uninterrupted. The stop itself
   stays in the control state for the next boundary. An interrupt from
   elsewhere is left alone."
  [control-state f]
  (let [monitor     (Object.)
        armed       (atom true)
        interrupted (atom false)
        hook        {:monitor monitor :armed armed :interrupted interrupted
                     :thread (Thread/currentThread) :control-state control-state}
        remove-hook (on-stop! control-state #(interrupt! hook))]
    (try
      (binding [*hook* hook] (f))
      (finally
        (locking monitor (reset! armed false))
        (remove-hook)
        (when @interrupted (Thread/interrupted))))))

(defn ^{:stratum 1} coordinating
  "`(f)` with this thread's `interrupting-on-stop` scope disarmed, for work
   that waits on tasks which stop on their own (DAG batch tasks run
   sub-workflows sharing the control state): interrupting the waiter would
   abandon their results. An interrupt the scope already delivered is
   cleared first. Re-armed after, unless the run stopped meanwhile: that
   stop (its callback possibly still in flight) is left to the next
   boundary. Outside a scope of this thread's own, just `(f)`."
  [f]
  (let [{:keys [monitor armed interrupted thread control-state]} *hook*]
    (if (and monitor (identical? (Thread/currentThread) thread))
      (do (locking monitor
            (reset! armed false)
            (when @interrupted (Thread/interrupted)))
          (try
            (f)
            (finally
              (locking monitor
                (when-not (some-> control-state deref :stopped)
                  (reset! armed true))))))
      (f))))

(comment ::stop-signal)
