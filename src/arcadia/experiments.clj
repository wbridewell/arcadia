(ns ^{:doc "This code supports running large-scale experiments involving multiple
            arcadia model runs over multiple stimuli."}

  arcadia.experiments
  (:require [arcadia.utility [general :as g] [model :as model]
             [recording :as recording] [data :as data]]
            [arcadia.core :as core]
            [arcadia.architecture.registry :as reg]
            [arcadia.component.core :as comp]
            [clojure.java.io :as io]
            arcadia.models.core
            clojure.string
            [clojure.set :as set]))

;; NOTE: the linter does not like how we handle adding components to the models.
;; this declaration is useful anyway because it states up front which components
;; are being used in this file.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helper Fns for startup-sequence

(defn- get-file-number-from-end
  "Obtains a number from a filename for sorting purposes. Assumes names will
  *********_Number.*** (e.g., \"Search_23.mp4\")"
  [fname]
  (let [[_ _ number]
        (re-matches #"(.*)([0-9]+)[.](.{3})" fname)]
    (when number
      (Integer/parseInt number))))

(defn- get-file-number-from-start
  "Obtains a number from a filename for sorting purposes. Assumes names will
  be in the form of Number_******.***"
  [fname]
  (let [[_ _ number]
        (re-matches #"(.*)/([0-9]+)([^/]*)[.](.{3})" fname)]
    (when number
      (Integer/parseInt number))))

(defn- setup-env-params-list
  "Takes env-params, a hash-map of parameters; env-params-sequence, an optional
   list of hash-maps to iterate over; and directory-path, an optional directory
   where stimulus files can be found. Returns a list of hash-maps describing the
   environment params to be used for each run of the model."
  [env-params env-params-sequence directory-path file-extensions sort-files-by
   skip-files shuffle?]
  (cond
    env-params-sequence
    (-> env-params-sequence
        (cond-> shuffle? shuffle)
        (->> (map #(merge env-params %))))

    directory-path
    (-> directory-path io/file file-seq rest
        (->> (map #(.getAbsolutePath %))
             (filter #(file-extensions (apply str (take-last 3 %)))))
        (cond->
         (= sort-files-by :end)
         (->> (map #(vector % (get-file-number-from-end %)))
              (remove #(nil? (second %)))
              (sort-by second <)
              (map first))
         (= sort-files-by :start)
         (->> (map #(vector % (get-file-number-from-start %)))
              (remove #(nil? (second %)))
              (sort-by second <)
              (map first)))
        (->> (drop skip-files))
        (cond-> shuffle? shuffle)
        (->> (map #(assoc env-params :file-path %))))

    :else
    (list env-params)))

(defn- get-env-param-keys
  "Given an env-params-sequence, return a list of all the keys found across
   all the params."
  [env-params-sequence]
  (when (seq env-params-sequence)
    (->> env-params-sequence (map keys) (map set) (apply set/union) seq)))

(def ^:private get-filename
  "Output function that extracts the name of a movie or image file from an
   absolute path string."
  (data/o# (when-let [fname (:file-path %)]
             (let [[_ _ name]
                   (re-matches #"(.*)/([^/]*)[.](.{3})" fname)]
               name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helper Fns

(defn- model->name
  "Takes a symbol representing an arcadia model's namespace and returns with just
   the name of the model."
  [model]
  (let [strs (clojure.string/split (str model) #"\.")]
    (last strs)))

(defn- get-sub-directories
  "Returns the names of all immediate sub-directories in a directory. Input
   should be a directory name in the resources directory."
  [directory]
  (->> directory io/resource (.getPath) io/file (.listFiles)
       (filter #(.isDirectory %))
       (map #(.getName %))))

(defn- compile-parameter-space
  "Takes a list of vectors of the form [one-or-more-items list-of-values].
   Returns all permutations of the parameter values. Each permutation is a list
   of the form
   ([one-or-more-items value]
    [one-or-more-items value]
    ...)."
  [items]
  (if (seq items)
    (loop [rem (-> items first last reverse)
           settings nil]
      (if (first rem)
        (recur (rest rem)
               (concat (map #(cons (conj (-> items first butlast vec) (first rem)) %)
                            (compile-parameter-space (rest items)))
                       settings))
        settings))
    (list nil)))

(defn- get-parameters
  "After compile-parameter-space has computed a list of parameter permutations,
   this function can be called on one such permutation to retrieve all items
   of the form [ky zero-or-more-items value], returning a list of items of the
   form [one-or-more-items value] or, value."
  [ky params]
  (let [params (filter #(= (first %) ky) params)]
    (seq (map #(if (= (count %) 2)
                 (second %)
                 (rest %))
              params))))

(defn- get-parameter
  "After compile-parameter-space has computed a list of parameter permutations,
   this function can be called on one such permutation to retrieve one item
   of the form
   [ky zero-or-more-items value]"
  [ky params]
  (first (get-parameters ky params)))

(defn- dynamic-variables->bindings
  "Takes a list of dynamic variable assignments from one of the permutations
   generated by compile-parameter-space. Each item in the list will be of form
   [namespace var-name var-value]
   or
   [var-name var-value] In this second case, assumes the var-name is from the
   model's namespace.
   Produces a hashmap that supports dynamic binding, in the form
   {resolved-var-name var-value, ...}"
  [model params]
  (apply hash-map
         (interleave
          (map (fn [[arg1 arg2 arg3]]
                 (if arg3 (ns-resolve arg1 arg2) (ns-resolve model arg1)))
               params)
          (map last params))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Top-level fns

(defn startup-sequence
  "Runs a model over one or a sequence of environment configurations. The sequence
   of environment configurations can be specified in either of two ways:
   1) By specifying a :directory-path, in which case there will be an environment
      for each file of the appropriate type in that directory.
   2) By specifying :env-parameter-sequence, a sequence of hashmaps describining
      environment parameters, in which case there will be an environment for each
      item in the sequence.
   This function can either run one ARCADIA model run for each environment
   configuration or run a single ARCADIA model run across all the environment
   configurations, depending on the value of the :one-run-per-environment?
   keyword argument.

   This function can be called similarlty to arcadia.core/startup. It takes the
   same required arguments:
   [model-ns environment-ns env-parameters max-cycles]

   Can take any of the keyword arguments that arcadia.core/startup takes, along
   with the following keyword arguments:
   directory-path: Indicates that the model should be run on each file in the
      directory with the appropriate extension. An environment configuration will
      be created with {:file-path file} for each file.
   file-extensions: A set of strings specifying the file extensions in directory-path
      to consider.
   sort-files-by: If this is non-nil, it indicates how files in :directory-path should
      be sorted. Options include:
      :start Sort by the number found at the start of each filename.
      :end Sort by the number found at the end of each filename.
   skip-files: After sorting the files in :directory-path, skip this many files
      before beginning. Default is 0.
   env-parameter-sequence: As an alternative to specifying a :directory-path, this
      can be used to provide a list of hash-maps with environment parameters.
      Each map in the list, in sequence, will be merged with the required argument
      env-parameters to get an environment configuration.
   shuffle?: If this is true, then randomize the order of the environment configurations.
   one-run-per-environment?: If this true, then initiate a new ARCADIA model run
      for each environment configuration. Otherwise, conduct a single model run
      across all environments. Default is true.
   suppress-text?: If this is true, then prevent any printlns from appearing in the
      repl during a model run.

  Returns a hashmap with the keys :registry, :data-state, and :recording-state."
  [model-ns env-ns env-params cycles
   & {:keys [;;Some core/startup args...
             record? recording-parameters data-parameters component-setup
             ;;And the new args not found in core/startup...
             file-extensions sort-files-by skip-files suppress-text? shuffle?
             one-run-per-environment? directory-path env-parameter-sequence]
      :or {file-extensions #{"mp4" "m4v" "mov" "jpg" "png"} skip-files 0
           one-run-per-environment? true}
      :as startup-params}]
  (doseq [w (java.awt.Window/getWindows)] (.dispose w))
  (let [recording-parameters
        (assoc recording-parameters :require-force-to-finish? true)
        startup-params (dissoc startup-params :recording-parameters :data-parameters)
        env-param-keys (get-env-param-keys env-parameter-sequence)]
    (loop [env-params-rem
           (setup-env-params-list env-params env-parameter-sequence directory-path
                                  file-extensions sort-files-by skip-files shuffle?)
           registry nil
           recording-state nil
           data-state  (cond-> (data/start data-parameters)
                               directory-path
                               (data/add-single-initial-output [:filename get-filename]))
           cycles-rem cycles
           em-display nil]
      (if (and (seq env-params-rem) (or (nil? cycles-rem) (pos? cycles-rem))
               (not (some-> registry reg/stopped?)))
        (let [_ (when one-run-per-environment?
                  (doseq [w (java.awt.Window/getWindows)]
                    (when (not (some-> em-display comp/frame (= w)))
                      (.dispose w))))
              new-data-state
              (cond-> data-state
                      env-param-keys
                      (data/add-initial-output
                       (map #(vector % (get (first env-params-rem) %)) env-param-keys)))
              {new-registry :registry :as results}
              (g/suppress-text-output-if
               suppress-text?
               (apply
                core/startup
                model-ns env-ns
                (first env-params-rem) cycles-rem
                :recording-parameters
                (assoc recording-parameters :previous-state recording-state)
                :data-parameters
                (assoc data-parameters :previous-state new-data-state)
                (-> startup-params
                    (assoc :reset-windows? false :return-state? true)
                    (cond-> (not one-run-per-environment?)
                            (assoc :old-registry registry))
                    (assoc :component-setup
                           (if em-display
                             (model/setup
                              component-setup
                              #_{:clj-kondo/ignore [:unresolved-symbol]}
                              (model/add display.environment-messages
                                         {:previous-state em-display}))
                             component-setup))
                    seq (->> (apply concat)))))]
          (recur (rest env-params-rem)
                 new-registry
                 (:recording-state results)
                 (:data-state results)
                 (when (and one-run-per-environment? cycles-rem)
                   (- cycles-rem (reg/cycle-num new-registry)))
                 (-> new-registry reg/component-registry :display.environment-messages)))
        (do (when (and record? (recording/began-recording? recording-state))
              (recording/finish-recording recording-state true))
          {:registry registry :recording-state recording-state :data-state data-state})))))

(defn startup-experiment
  "Conducts an experiment by running one or more models in batch mode and
   gathering the results in a single datafile. The user can specify several different
   parameter ranges to consider, and this function will call startup-sequence
   for every possible combination of those parameter ranges. Can be called similarly
   to core/startup and experiment/startup-sequence, except it does not take the
   max number of cycles as an argument. The required arguments are only
   [model-ns environment-ns env-parameters].

   Can take any of the keyword arguments that startup-sequence takes, along with
   the following keyword arguments:
   version: A version name or number that will be saved to the datafile.
   model-range: A list of model-namespaces to consider in the experiment. If this is
     non-nil, then the required argument model-ns will be ignored.
   env-range: A list of env-namespaces to consider in the experiment. If this is
     nil-nil, then the required argument environment-ns will be ignored.
   env-parameter-ranges: A list of environment parameter ranges to consider. Each
     item in the list should take the form
     [:parameter-name (list-of-parameter-values-to-try)]
   comp-parameter-ranges: A list of component parameter ranges to consider. Each
     item in the list should take the form
     ['component-name :parameter-name (list-of-parameter-values-to-try)]
   dynamic-variable-ranges: A list of dynamic variable ranges to consider. Each
     item in the list should take the form
     ['namespace 'dynamic-variable-name (list-of-values-to-try)]
     or
     ['dynamic-variable-name (list-of-values-to-try)]
     In the latter case, the model's namespace is used.
   sub-directory-range: If :directory-path is specified, then this provides a list
     of subdirectories within that directory to consider. If this value is :all,
     then all subdirectories will be considered.
   iterations: Repeat every permutation of the various ranges this many times. The
     default is 1.

   Returns a hash-map with the keys :registry, :data-state, and :recording-state,
   based on the final call to startup-sequence."
  [model-ns env-ns env-params
   & {:keys [component-setup data-parameters recording-parameters save-data?
             version iterations directory-path
             env-parameter-ranges comp-parameter-ranges dynamic-variable-ranges
             model-range env-range sub-directory-range]
      :or {version 1 iterations 1 save-data? true}
      :as startup-params}]
  (loop [param-space
         (compile-parameter-space
          (concat (when (seq model-range)
                    [:model model-range])
                  (when (seq env-range)
                    [:env env-range])
                  (map #(cons :env-parameter %) env-parameter-ranges)
                  (map #(cons :comp-parameter %) comp-parameter-ranges)
                  (map #(cons :dynamic %) dynamic-variable-ranges)
                  (when (and directory-path sub-directory-range)
                    (if (= sub-directory-range :all)
                      [[:sub-directory (get-sub-directories directory-path)]]
                      [[:sub-directory sub-directory-range]]))
                  (when (> iterations 1)
                    [[:iteration (range 1 (inc iterations))]])))
         ; output-file (new-filename
         ;              (or (:output-file data-parameters)
         ;                  (str directory "/results_" version ".csv")))
         run 1
         recording-file (or (:recording-file recording-parameters) (g/date+time-string))
         prev-results nil]
    (if-let [params (when (not (some-> prev-results :registry reg/stopped?))
                           (first param-space))]
      (let [model (or (get-parameter :model params) model-ns)
            env (or (get-parameter :env params) env-ns)
            subdir (get-parameter :sub-directory params)
            iteration (get-parameter :iteration params)
            env-parameters (get-parameters :env-parameter params)
            comp-parameters (get-parameters :comp-parameter params)
            dynamic-variables (get-parameters :dynamic params)
            new-data-state
            (data/add-initial-output
             (or (some-> prev-results :data-state)
                 (data/start data-parameters))
             (concat (when version [[:version version]])
                     [[:run run]]
                     (when (seq model-range) [[:model (model->name model)]])
                     (when (seq env-range) [[:environment (model->name env)]])
                     (map #(take-last 2 %) env-parameters)
                     (map #(take-last 2 %) comp-parameters)
                     (map #(take-last 2 %) dynamic-variables)
                     (when subdir [[:sub-directory subdir]])
                     (when iteration [[:i iteration]])
                     (when directory-path [[:filename get-filename]])))]
        (println :PARAMETERS params)
        (recur
         (rest param-space)
         (inc run)
         recording-file
         (with-bindings (dynamic-variables->bindings model dynamic-variables)
           (apply
            startup-sequence
            model env
            (reduce (fn [hmap [ky val]] (assoc hmap ky val))
                    env-params env-parameters)
            nil
            :save-data? save-data?
            :data-parameters
            (assoc data-parameters :previous-state new-data-state)
            :recording-parameters
            (assoc recording-parameters :recording-file (str recording-file "_" run))
            :directory-path
            (if (and subdir directory-path)
              (str directory-path "/" subdir)
              directory-path)
            :component-setup
            (model/setup
             component-setup
             (model/update-parameters comp-parameters))
            (-> startup-params
                (dissoc :data-parameters :recording-parameters
                        :component-setup :directory-path :save-data)
                seq (->> (apply concat)))))))
      prev-results)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Example of an experiment.

(defn mot-extrap-experiment
  "Presents an example for running a full-scale simulation across a set of videos.
  Note: For this to work, you must have a directory resources/MOT_Brief/Extrap/1 with
  MOT videos inside it."
  [record?]
  (let [dir "MOT_Brief/Extrap"
        directory-path (.getPath (clojure.java.io/resource dir))
        description "Demo"
        filename->info
        (fn [fname]
          (let [[_ trial targets speed pred old1 old2]
                (re-matches #"(.+)_(.+)_(.+)_(.+)_([0-1])([0-1])" fname)]
            {:trial trial
             :targets targets
             :speed speed
             :pred pred
             :answer-1 (if (= old1 "1") "old" "new")
             :answer-2 (if (= old2 "1") "old" "new")}))]

    (startup-experiment
     'arcadia.models.mot-extrap 'arcadia.simulator.environment.stimulus-player
     {:max-actions 2
      :minimum-cycles-before-action 10
      :max-cycles 250 :buffer-frames 4
      :viewing-width 15.375}
     :directory-path directory-path
     :sort-files-by :start
     :one-run-per-environment? true
     :disable-displays? false
     :suppress-text? true
     :record? record?
     :recording-parameters {:recording-file (str dir "/" description)}
     :data-parameters
     {:initial-output [[:description description]]
      :output
      (list [:trial (data/o# (-> % :filename filename->info :trial))]
            [:speed (data/o# (-> % :filename filename->info :speed))]
            [:targets (data/o# (-> % :filename filename->info :targets))]
            [:predictable (data/o# (-> % :filename filename->info :pred))]
            [:answer-1 (data/o# (-> % :filename filename->info :answer-1))]
            [:answer-2 (data/o# (-> % :filename filename->info :answer-2))]
            [:correct-1 (data/o# (if (= (:answer-1 %) (:response-1 %)) 1 0))]
            [:correct-2 (data/o# (if (= (:answer-2 %) (:response-2 %)) 1 0))]
            [:correct (data/o# (if (and (= (:correct-1 %) 1) (= (:correct-2 %) 1))
                                 1 0))])}
     :sub-directory-range [1 2 3] ;;Virtual participants
     :comp-parameter-ranges '([object-locator :noise-center (0.1 0.2 0.3)]
                              [object-locator :noise-width (0.15 0.2)]
                              [highlighter.crowding :crowding-dist (2.6 2.8 3.0)])))
  nil)
