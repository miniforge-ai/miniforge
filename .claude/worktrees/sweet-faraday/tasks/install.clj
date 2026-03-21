(ns install
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [babashka.tasks :refer [run]]))

(defn tui []
  (let [dist-dir    "dist/miniforge-tui"
        bin-dir     (str (System/getProperty "user.home") "/.local/bin")
        install-dir (str (System/getProperty "user.home") "/.local/lib/miniforge-tui")
        script-target (str bin-dir "/miniforge-tui")]
    ;; Build first
    (run 'build:tui)

    (if-not (fs/exists? dist-dir)
      (do (println "❌ dist/miniforge-tui not found")
          (System/exit 1))
      (do
        (println "📦 Installing miniforge-tui to" install-dir)
        (fs/create-dirs bin-dir)

        ;; Remove old installation
        (when (fs/exists? install-dir)
          (fs/delete-tree install-dir))

        ;; Copy distribution
        (fs/copy-tree dist-dir install-dir)

        ;; Create symlink in bin
        (when (fs/exists? script-target)
          (fs/delete script-target))
        (spit script-target
              (str "#!/usr/bin/env bash\n"
                   "exec \"" install-dir "/bin/miniforge-tui\" \"$@\"\n"))
        (p/shell "chmod" "+x" script-target)

        (println "✅ Installed successfully")
        (println "   Ensure ~/.local/bin is in your PATH")
        (println "   Run 'miniforge-tui' to launch")))))

(defn local-cli []
  (let [jar-source "dist/miniforge.jar"
        bin-dir (str (System/getProperty "user.home") "/.local/bin")
        jar-target (str bin-dir "/miniforge.jar")
        script-target (str bin-dir "/mf")]
    ;; Clean old build
    (when (fs/exists? jar-source)
      (println "🗑️  Removing old build:" jar-source)
      (fs/delete jar-source))
    ;; Build fresh
    (run 'build:cli)
    ;; Install
    (if (fs/exists? jar-source)
      (do
        (println "📦 Installing miniforge CLI to" bin-dir)
        (fs/create-dirs bin-dir)
        ;; Copy the jar
        (fs/copy jar-source jar-target {:replace-existing true})
        ;; Create wrapper script
        (spit script-target
              (str "#!/usr/bin/env bash\n"
                   "exec bb --jar \"" jar-target "\" -m ai.miniforge.cli.main \"$@\"\n"))
        (p/shell "chmod" "+x" script-target)
        (println "✅ Installed successfully")
        (println "   Ensure ~/.local/bin is in your PATH")
        (println "   Run 'mf version' to verify"))
      (println "❌ dist/miniforge.jar not found. Run 'bb build:cli' first"))))
