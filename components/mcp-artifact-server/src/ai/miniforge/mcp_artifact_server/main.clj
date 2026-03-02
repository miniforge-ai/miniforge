(ns ai.miniforge.mcp-artifact-server.main
  "Entry point for running the MCP artifact server via `bb -m`.

   Usage:
     bb -cp components/mcp-artifact-server/src -m ai.miniforge.mcp-artifact-server.main --artifact-dir /tmp/dir"
  (:require [ai.miniforge.mcp-artifact-server.server :as server]))

(defn- parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (case (first args)
        "--artifact-dir" (recur (drop 2 args)
                                (assoc opts :artifact-dir (second args)))
        (recur (rest args) opts)))))

(defn -main [& args]
  (let [opts (parse-args args)
        artifact-dir (:artifact-dir opts)]
    (when-not artifact-dir
      (binding [*out* *err*]
        (println "ERROR: --artifact-dir is required"))
      (System/exit 1))
    (server/run-server artifact-dir)))
