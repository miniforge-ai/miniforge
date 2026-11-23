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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.acquisition
  "Acquisition deadline for the OCI-CLI executor: runs an acquire body with a
   wall-clock timeout and keeps its bounded cleanup from being interrupted."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.dag-executor.result :as result]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-acquisition-timeout-ms
  "Default ceiling on `acquire-environment!` wall-clock duration. Picked
   so a cold-cache image pull (~60 s on a slow link for the runner
   images) still fits, while a stuck Docker daemon fails within two
   minutes instead of blocking the workflow indefinitely. Override per
   call via `env-config[:acquisition-timeout-ms]`. The 2026-05-16
   dogfood hung 33+ minutes on stuck Docker acquires before PR #895's
   test-side mock landed; the production-side guard lives here."
  120000)

(defn ^{:stratum 0} without-interrupt
  "Call (f) where no interrupt of this thread can reach it, and return its
   value or throw what it threw. A timed-out acquire's worker is interrupted
   (`future-cancel`), and the interrupt can land after the worker has begun
   cleaning up; the bounded cleanup must still finish, and an interrupt
   would end its wait and kill the runtime command doing it. So (f) runs on
   a thread of its own while this one waits, and an interrupt this thread
   had pending or gets meanwhile is set on it again afterwards."
  [f]
  (let [task (future (try+ {::value (f)}
                           (catch Object _ {::thrown (:throwable &throw-context)})))]
    (loop [interrupted? (Boolean/valueOf (Thread/interrupted))]
      (let [r (try+ (deref task)
                    (catch InterruptedException _ ::interrupted))]
        (if (= ::interrupted r)
          (recur true)
          (do (when interrupted? (.interrupt (Thread/currentThread)))
              (if-let [thrown (::thrown r)]
                (throw thrown)
                (::value r))))))))

(defn- ^{:stratum 0} acquire-failed-result
  [throwable]
  (result/err :acquire-failed
              (or (ex-message throwable) (messages/t :oci/acquire-failed))
              (cond-> {:surface :acquire-environment!
                       :exception-class (.getName (class throwable))}
                (ex-data throwable) (assoc :exception-data (ex-data throwable)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} removing-unless-ok
  "(f), the part of an acquire after its container started. Unless it
   returns an ok result, whether it returns an error or throws (a failed
   env-file write, an interrupt), (remove!) runs first, out of reach of an
   interrupt (`without-interrupt`): only an ok result gives the caller an
   environment id to release the container with."
  [remove! f]
  (let [ok? (volatile! false)]
    (try
      (let [r (f)]
        (vreset! ok? (result/ok? r))
        r)
      (finally
        (when-not @ok? (without-interrupt remove!))))))

(defn- ^{:stratum 1} call-acquire-body
  [body-fn]
  (try
    (body-fn)
    (catch Throwable t
      (acquire-failed-result t))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-acquisition-timeout
  "Run `body-fn` (zero-arity) with a wall-clock deadline. Returns the
   body's return value when it completes in time, or a `:timeout`
   error result (the standard dag-executor code) when the deadline
   fires.

   A body still running at the deadline is cancelled (its thread is
   interrupted) but may still finish, even with an ok result such as an
   acquire whose container started, which the caller, already holding
   the timeout, has no id to release. Whichever of the body's finish and
   the deadline comes first decides: a body that finishes after the
   deadline hands its result to `abandon!` (one argument, run out of reach
   of the cancelling interrupt, which can still land after the body
   returns: `without-interrupt`) to release what it acquired.

   `timeout-ms` of `nil` or `<= 0` disables the timeout (tests that
   pre-mock the executor wholesale rely on this escape hatch).

   Caveats and follow-ups:

   - Exceptions thrown by `body-fn` are returned as a standard
     `:acquire-failed` error result. `deref` would otherwise wrap them
     in `ExecutionException`; this fn captures the original throwable so
     callers receive explicit failure data instead of exception control
     flow.
   - `future-cancel` only interrupts the worker thread; it does NOT
     interrupt owned child processes now that runtime calls use
     ProcessBuilder directly instead of `clojure.java.shell/sh`."
  [timeout-ms body-fn abandon!]
  (if (or (nil? timeout-ms) (not (pos? timeout-ms)))
    (call-acquire-body body-fn)
    (let [state (atom :running)
          fut (future
                (let [r (call-acquire-body body-fn)]
                  (when-not (compare-and-set! state :running :finished)
                    (without-interrupt #(abandon! r)))
                  {::ok r}))
          deref-result (deref fut timeout-ms ::timeout)]
      (cond
        (not= ::timeout deref-result)
        (::ok deref-result)

        (compare-and-set! state :running :abandoned)
        (do
          (future-cancel fut)
          (binding [*out* *err*]
            (println (messages/t :oci/acquire-deadline {:ms timeout-ms})))
          (result/err :timeout
                      (messages/t :oci/acquire-timeout {:ms timeout-ms})
                      {:timeout-ms timeout-ms
                       :surface :acquire-environment!}))

        ;; The body finished as the deadline fired: its result is the caller's.
        :else
        (::ok @fut)))))
