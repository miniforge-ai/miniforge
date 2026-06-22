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

(ns ai.miniforge.config.resource
  "Generic classpath EDN config-resource loading. One home for the
   load-this-component's-config-resource pattern so components stop
   hand-rolling their own `(-> (io/resource ...) slurp edn/read-string)`
   loaders.

   Two variants:

   - `load-config-resource` fails fast with a clear ex-info when the
     resource is absent, malformed, not a map, or missing a required key.
     Use it when the values are required (no in-code fallback).
   - `read-config-resource-or` is fail-open: it returns a caller-supplied
     fallback on any error. Use it where call sites already carry literal
     defaults or the component is documented to fail open."
  (:require
   [ai.miniforge.messages.interface :as messages]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private t
  "System-locale translator for this component. The loader's fail-fast
   diagnostics are operator-facing (system-locale, not user UI), but routed
   through the catalog so the one place to audit or override them is data."
  (messages/create-translator "config/config/messages/system.edn"
                              :config/system))

(defn load-config-resource
  "Read an EDN config resource from the classpath, failing fast with a
   clear ex-info when the resource is absent, malformed, not a map, or
   missing a required key. Returns the parsed map.

   `required-keys` (optional) is a collection of keys that must be present
   at the top level; an empty/absent collection skips the key check."
  ([path] (load-config-resource path nil))
  ([path required-keys]
   (let [url (io/resource path)]
     (when (nil? url)
       (throw (ex-info (t :resource/missing {:path path})
                       {:config/resource path})))
     (let [parsed (try
                    (edn/read-string (slurp url :encoding "UTF-8"))
                    (catch InterruptedException e
                      ;; Never swallow cancellation — restore the flag and
                      ;; propagate rather than wrap it as a parse error.
                      (.interrupt (Thread/currentThread))
                      (throw e))
                    (catch Exception e
                      (throw (ex-info (t :resource/malformed {:path path})
                                      {:config/resource path}
                                      e))))]
       (when-not (map? parsed)
         (throw (ex-info (t :resource/not-a-map {:path path})
                         {:config/resource path})))
       (let [missing (vec (remove #(contains? parsed %) required-keys))]
         (when (seq missing)
           (throw (ex-info (t :resource/missing-keys {:path path :keys missing})
                           {:config/resource path :config/missing-keys missing})))
         parsed)))))

(defn read-config-resource-or
  "Read an EDN config resource, returning `fallback` if the resource is
   absent, unreadable, malformed, or not a map (fail-open). Cancellation
   (`InterruptedException`) is propagated, not swallowed."
  [path fallback]
  (try
    (let [url    (io/resource path)
          parsed (when url (edn/read-string (slurp url :encoding "UTF-8")))]
      (if (map? parsed) parsed fallback))
    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      (throw e))
    (catch Exception _ fallback)))
