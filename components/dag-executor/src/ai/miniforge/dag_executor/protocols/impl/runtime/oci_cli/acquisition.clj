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
   [ai.miniforge.dag-executor.result :as result]))

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
  "Call (f) with the thread's interrupt flag clear, then set it again if it
   was set. A timed-out acquire's worker is interrupted; its bounded cleanup
   must still finish, and an interrupt would end the cleanup's own wait and
   kill the runtime command doing it."
  [f]
  (let [interrupted? (Thread/interrupted)]
    (try (f)
         (finally (when interrupted? (.interrupt (Thread/currentThread)))))))

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
   env-file write, an interrupt), (remove!) runs first, with the interrupt
   flag clear (`without-interrupt`): only an ok result gives the caller an
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
  [timeout-ms body-fn]
  (if (or (nil? timeout-ms) (not (pos? timeout-ms)))
    (call-acquire-body body-fn)
    (let [fut (future
                {::ok (call-acquire-body body-fn)})
          deref-result (deref fut timeout-ms ::timeout)]
      (cond
        (= ::timeout deref-result)
        (do
          (future-cancel fut)
          (binding [*out* *err*]
            (println (messages/t :oci/acquire-deadline {:ms timeout-ms})))
          (result/err :timeout
                      (messages/t :oci/acquire-timeout {:ms timeout-ms})
                      {:timeout-ms timeout-ms
                       :surface :acquire-environment!}))

        :else
        (::ok deref-result)))))
