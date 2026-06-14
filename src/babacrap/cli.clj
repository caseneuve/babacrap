(ns babacrap.cli
  (:require [babacrap.core :as crap]
            [babacrap.detangle :as detangle]
            [babacrap.mutation :as mutation]
            [babacrap.util :as util]
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

(defn top-level-help? [args]
  (or (empty? args)
      (= ["help"] (vec args))
      (and (= 1 (count args))
           (help-arg? (first args)))))

(defn unknown-text [cmd]
  (str "Unknown subcommand: " cmd \newline \newline (usage)))

(defn run-subcommand [cmd args]
  (case cmd
    "crap" (crap/run-result args)
    "mutate" (mutation/run-result args)
    "detangle" (detangle/run-result args)))

(defn run-result [args]
  (cond
    (top-level-help? args)
    {:exit 0 :out (usage)}

    :else
    (let [cmd (first args)]
      (if (contains? subcommands cmd)
        (run-subcommand cmd (vec (rest args)))
        {:exit 2 :err (unknown-text cmd)}))))

;; -- Side effects --

(defn run [args]
  (let [{:keys [exit] :as result} (run-result args)]
    (util/emit-result result)
    exit))

;; -- CLI entry point --

(defn -main [& args]
  (util/exit-nonzero! (run args)))
