(ns build
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(defn cli []
  (println "🔨 Building miniforge CLI uberjar...")
  (println "Command: clojure -T:build bb-uberjar :project miniforge")
  (let [{:keys [exit out err]} (p/sh {:out :string :err :string}
                                     "clojure" "-T:build" "bb-uberjar" ":project" "miniforge")]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (zero? exit)
      (println "❌ Build failed with exit code:" exit)
      (System/exit exit))))

(defn kernel []
  (println "🔨 Building workflow-kernel CLI uberjar...")
  (println "Command: clojure -T:build bb-uberjar :project workflow-kernel")
  (let [{:keys [exit out err]} (p/sh {:out :string :err :string}
                                     "clojure" "-T:build" "bb-uberjar" ":project" "workflow-kernel")]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (zero? exit)
      (println "❌ Build failed with exit code:" exit)
      (System/exit exit))))

(defn tui []
  (let [jar-file   "dist/miniforge-tui.jar"
        jlink-dir  "dist/miniforge-tui-runtime"
        dist-dir   "dist/miniforge-tui"
        java-home  (or (System/getenv "JAVA_HOME")
                       "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")]
    (println "🔨 Building miniforge-tui JVM uberjar...")
    ;; Step 1: Build JVM uberjar
    (let [{:keys [exit out err]}
          (p/sh {:out :string :err :string}
                "clojure" "-T:build" "uberjar"
                ":project" "miniforge-tui"
                ":uber-file" (str "\"" jar-file "\""))]
      (when-not (str/blank? out) (println out))
      (when-not (str/blank? err) (binding [*out* *err*] (println err)))
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
  (println "Command: clojure -T:build compile-all")
  (let [{:keys [exit out err]} (p/sh {:out :string :err :string}
                                     "clojure" "-T:build" "compile-all")]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (zero? exit)
      (println "❌ Compilation failed with exit code:" exit)
      (System/exit exit))))
