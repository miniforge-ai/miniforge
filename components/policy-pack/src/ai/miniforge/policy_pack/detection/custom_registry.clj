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
(ns ai.miniforge.policy-pack.detection.custom-registry
  "Explicit extension registry for custom policy detectors, plus the
   reflection helpers that decide whether a candidate fn is a valid
   2-arity detector. Split out of `ai.miniforge.policy-pack.detection`
   (rule 210: the combined namespace measured 6 real layers, max 3) —
   same approach as the mdc-compiler split, miniforge#1729-#1743, and
   the workflow-runner split, miniforge#1662.

   `custom-fn-registry`, `resolve-custom-fn`, and `detector-predicate?` are
   public (not private, despite being internal mechanics) because
   `ai.miniforge.policy-pack.detection` — the parent namespace, not this
   one — is the caller that actually resolves/runs/(un)registers custom
   detectors; this namespace only stores the registry and validates
   candidate fns.

   Layer 0: the registry atom, method-declaration reflection primitives
   Layer 1: arity-reflection (over declared-method?), registry lookup
     (over the registry atom)
   Layer 2: detector-predicate? (over Layer 0 + Layer 1)")

;------------------------------------------------------------------------------ Layer 0

(defonce ^{:stratum 0}
  ^{:doc "Explicit extension registry for custom policy detectors.

Keys are the symbols stored under `:rule/detection :custom-fn`; values are
2-arity detector functions. This keeps pack manifests data-driven without
depending on ambient namespace loading or raw var resolution."}
  custom-fn-registry
  (atom {}))

(defn- ^{:stratum 0} declared-method?
  [method-name arity f]
  (some (fn [^java.lang.reflect.Method method]
          (and (= method-name (.getName method))
               (= arity (count (.getParameterTypes method)))))
        (.getDeclaredMethods (class f))))

(defn- ^{:stratum 0} reflectable-invoke?
  "True when `f`'s class declares any `invoke` method. JVM functions do; SCI
   functions under babashka do not, so arity reflection is blind there."
  [f]
  (boolean (some #(= "invoke" (.getName ^java.lang.reflect.Method %))
                 (.getDeclaredMethods (class f)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} variadic-accepts-arity?
  [arity f]
  (when (declared-method? "getRequiredArity" 0 f)
    (try
      (<= (.getRequiredArity f) arity)
      (catch Throwable _ false))))

(defn ^{:stratum 1} resolve-custom-fn
  "Look up a `:custom` rule's `:custom-fn` symbol in the explicit registry.
   Missing registrations are the no-op case routed to the judge."
  [rule]
  (when-let [custom-fn-sym (get-in rule [:rule/detection :custom-fn])]
    (get @custom-fn-registry custom-fn-sym)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} detector-predicate?
  "True when `f` is a custom detector fn. On the JVM its 2-arity shape is verified
   by reflection; under babashka/SCI (whose fn classes expose no reflectable
   `invoke` methods, or where reflection is unavailable) arity cannot be
   introspected, so any `fn?` is accepted rather than rejecting a valid detector
   we cannot check — this lets detector namespaces register at LOAD time on both
   runtimes, instead of deferring registration to first use."
  [f]
  (and (fn? f)
       (try
         (or (declared-method? "invoke" 2 f)
             (variadic-accepts-arity? 2 f)
             (not (reflectable-invoke? f)))
         ;; Reflection/introspection failure (e.g. babashka) → accept the fn.
         ;; Catch Exception, not Throwable, so JVM Errors are not masked.
         (catch Exception _ true))))
