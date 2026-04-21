(ns ai.miniforge.connector.retry
  "Connector retry policy presets.

   Loads named retry policies from
   resources/config/connector/retry-policies.edn so individual connector
   registrations can reference a preset (`:default`, `:none`, ...) instead
   of inlining the same map in every interface.clj."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private retry-policies-resource
  "config/connector/retry-policies.edn")

(def retry-policies
  "Map of preset-key → retry-policy-map, loaded from EDN at namespace load."
  (-> retry-policies-resource
      io/resource
      slurp
      edn/read-string))

(defn retry-policy
  "Return the retry policy preset for `policy-key`.

   Throws ex-info when the key is unknown so misconfiguration is caught at
   namespace load time, not on the first request."
  [policy-key]
  (or (get retry-policies policy-key)
      (throw (ex-info "Unknown connector retry policy preset"
                      {:policy-key policy-key
                       :known-keys (set (keys retry-policies))}))))
