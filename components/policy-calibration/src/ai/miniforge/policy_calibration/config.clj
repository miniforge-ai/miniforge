(ns ai.miniforge.policy-calibration.config
  "Calibration configuration as data: read + Malli-validate config.edn."
  (:require [malli.core :as m]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def Config
  [:map
   [:model        [:map [:label string?] [:backend keyword?] [:model string?]]]
   [:trials       pos-int?]
   [:runs         pos-int?]
   [:max-parallel pos-int?]
   [:gate-bar     [:map
                   [:max-clean-fp nat-int?]
                   [:min-recall   [:and number? [:>= 0] [:<= 1]]]]]
   [:pack-paths   [:vector string?]]
   [:corpus-root  string?]
   [:record-out   string?]])

(def ^:private config-resource "policy-calibration/config.edn")

(defn load-config
  "Read and validate the calibration config from the classpath. Throws
   IllegalArgumentException on a malformed config (a setup/programmer error)."
  []
  (let [cfg (edn/read-string (slurp (io/resource config-resource)))]
    (when-not (m/validate Config cfg)
      (throw (IllegalArgumentException.
              (str "invalid policy-calibration config: "
                   (pr-str (m/explain Config cfg))))))
    cfg))

(comment
  (load-config)
  :leave-this-here)
