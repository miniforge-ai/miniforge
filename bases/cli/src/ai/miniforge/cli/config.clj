(ns ai.miniforge.cli.config
  "Configuration management using Aero.
   
   Provides centralized configuration with environment variable overrides."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(defn load-config
  "Load configuration from config.edn with optional profile.
   
   Profile can be :dev, :test, or :prod.
   Defaults to :dev if not specified.
   
   Environment variables can override any config value via #env tags."
  ([] (load-config {}))
  ([opts]
   (let [profile (or (:profile opts) :dev)
         config-file (or (:config-file opts)
                         (io/resource "config.edn"))]
     (when config-file
       (aero/read-config config-file {:profile profile})))))

(defn get-llm-backend
  "Get LLM backend from config, with workflow override support."
  [config workflow-override]
  (or workflow-override
      (get-in config [:llm :backend])
      :claude))

(defn get-llm-timeout
  "Get LLM timeout from config."
  [config]
  (get-in config [:llm :timeout-ms] 300000))

(defn get-llm-line-timeout
  "Get LLM line timeout from config."
  [config]
  (get-in config [:llm :line-timeout-ms] 60000))
