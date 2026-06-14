(ns babacrap.cli
  (:require [babacrap.core :as crap]
            [babacrap.detangle :as detangle]
            [babacrap.mutation :as mutation]
            [babacrap.util :as util]
            [babashka.cli :as cli]
            [clojure.string :as str]))

;; -- Pure --

(def subcommands
  {"crap" "Compute CRAP scores from complexity and coverage."
   "mutate" "Run mutation analysis."
   "detangle" "Find deterministic decomplecting investigation signals."})

(defn usage []
  (str/join
   \newline
   ["babacrap: CRAP, mutation, and detangle analysis for babashka/Clojure projects"
    ""
    "Usage: babacrap <command> [options]"
    ""
    "Commands:"
    "  crap      Compute CRAP scores from complexity and coverage."
    "  mutate    Run mutation analysis."
    "  detangle  Find deterministic decomplecting investigation signals."
    ""
    "Run `babacrap <command> --help` for command-specific options."
    ""
    "Examples:"
    "  babacrap crap --src src --test test"
    "  babacrap mutate --src src --test-command 'bb test'"
    "  babacrap detangle --src src --format edn"]))

(defn help-arg? [arg]
  (contains? #{"-h" "--help"} arg))

(defn top-level-help? [args cmds]
  (or (empty? args)
      (= ["help"] (vec cmds))
      (and (empty? cmds)
           (= 1 (count args))
           (help-arg? (first args)))))

(defn unknown-text [cmd]
  (str "Unknown subcommand: " cmd \newline \newline (usage)))

(defn run-subcommand [cmd args]
  (case cmd
    "crap" (crap/run-result args)
    "mutate" (mutation/run-result args)
    "detangle" (detangle/run-result args)))

(defn run-result [args]
  (let [{:keys [cmds] subcommand-args :args} (cli/parse-cmds args)]
    (cond
      (top-level-help? args cmds)
      {:exit 0 :out (usage)}

      (empty? cmds)
      {:exit 2 :err (str "Expected subcommand." \newline \newline (usage))}

      :else
      (let [cmd (name (first cmds))]
        (if (contains? subcommands cmd)
          (run-subcommand cmd (vec subcommand-args))
          {:exit 2 :err (unknown-text cmd)})))))

;; -- Side effects --

(defn run [args]
  (let [{:keys [exit] :as result} (run-result args)]
    (util/emit-result result)
    exit))

;; -- CLI entry point --

(defn -main [& args]
  (util/exit-nonzero! (run args)))
