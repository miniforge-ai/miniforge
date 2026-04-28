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

(ns build
  (:require
   [ai.miniforge.bb-proc.interface :as proc]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(defn- print-command-output!
  [{:keys [out err]}]
  (when-not (str/blank? out)
    (println out))
  (when-not (str/blank? err)
    (binding [*out* *err*]
      (println err))))

(defn- build-command
  [& args]
  (into [(proc/clojure-command) "-T:build"] args))

(defn- run-build-command!
  [command failure-message]
  (println "Command:" (str/join " " command))
  (let [{:keys [exit] :as result} (apply p/sh {:out :string :err :string} command)]
    (print-command-output! result)
    (when-not (zero? exit)
      (println failure-message exit)
      (System/exit exit))))

(defn cli []
  (println "🔨 Building miniforge CLI uberjar...")
  (let [command (build-command "bb-uberjar" ":project" "miniforge")]
    (run-build-command! command "❌ Build failed with exit code:")))

(defn kernel []
  (println "🔨 Building miniforge-core CLI uberjar...")
  (let [command (build-command "bb-uberjar" ":project" "miniforge-core")]
    (run-build-command! command "❌ Build failed with exit code:")))

(defn tui []
  (let [jar-file      "dist/miniforge-tui.jar"
        jlink-dir     "dist/miniforge-tui-runtime"
        dist-dir      "dist/miniforge-tui"
        java-home     (get (System/getenv) "JAVA_HOME"
                           "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
        uber-file-arg (str "\"" jar-file "\"")]
    (println "🔨 Building miniforge-tui JVM uberjar...")
    ;; Step 1: Build JVM uberjar
    (let [command (build-command "uberjar"
                                 ":project" "miniforge-tui"
                                 ":uber-file" uber-file-arg)
          {:keys [exit] :as result} (apply p/sh {:out :string :err :string} command)]
      (println "Command:" (str/join " " command))
      (print-command-output! result)
      (when-not (zero? exit)
        (println "❌ Uberjar build failed")
        (System/exit exit)))

    (if-not (fs/exists? jar-file)
      (do (println "❌" jar-file "not found")
          (System/exit 1))
      (println "✅ Uberjar:" jar-file "(" (fs/size jar-file) "bytes)"))

    ;; Step 2: Create jlink minimal JVM runtime
    (println "🔗 Creating jlink runtime...")
    (when (fs/exists? jlink-dir)
      (fs/delete-tree jlink-dir))
    (let [jlink-bin (str java-home "/bin/jlink")
          {:keys [exit err]}
          (p/sh {:out :string :err :string}
                jlink-bin
                "--add-modules" "java.base,java.desktop,java.logging,java.management,java.sql,java.naming,java.xml"
                "--strip-debug"
                "--no-man-pages"
                "--no-header-files"
                "--compress" "zip-6"
                "--output" jlink-dir)]
      (when-not (zero? exit)
        (println "❌ jlink failed:" err)
        (System/exit exit)))
    (println "✅ jlink runtime:" jlink-dir)

    ;; Step 3: Create distribution directory
    (println "📦 Packaging distribution...")
    (when (fs/exists? dist-dir)
      (fs/delete-tree dist-dir))
    (fs/create-dirs (str dist-dir "/bin"))
    (fs/create-dirs (str dist-dir "/lib"))

    ;; Copy jar
    (fs/copy jar-file (str dist-dir "/lib/miniforge-tui.jar"))

    ;; Copy jlink runtime
    (fs/copy-tree jlink-dir (str dist-dir "/jvm"))

    ;; Create launcher script
    (spit (str dist-dir "/bin/miniforge-tui")
          (str "#!/usr/bin/env bash\n"
               "# miniforge-tui launcher (jlink-bundled JVM)\n"
               "SCRIPT_DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n"
               "BASE_DIR=\"$(cd \"$SCRIPT_DIR/..\" && pwd)\"\n"
               "exec \"$BASE_DIR/jvm/bin/java\" \\\n"
               "  -cp \"$BASE_DIR/lib/miniforge-tui.jar\" \\\n"
               "  clojure.main -m ai.miniforge.cli.main \\\n"
               "  tui \"$@\"\n"))
    (p/shell "chmod" "+x" (str dist-dir "/bin/miniforge-tui"))

    ;; Clean intermediate jlink dir
    (fs/delete-tree jlink-dir)

    (println "✅ Distribution ready:" dist-dir)
    (println "   Run with:" (str dist-dir "/bin/miniforge-tui"))))

(defn compile-all []
  (println "⚙️  Compiling all code for syntax check...")
  (let [command (build-command "compile-all")]
    (run-build-command! command "❌ Compilation failed with exit code:")))
