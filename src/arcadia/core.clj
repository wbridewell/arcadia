(ns arcadia.core
  (:require
    (arcadia.architecture [registry :as reg]
                          [focus-selector :as focus-selector])
    (arcadia.simulator.environment [core :as env])
    (arcadia.sensor [core :as sense])
    (arcadia.utility [model :as model]
                     [general :as general]
                     [evaluation :as evaluation]
                     [data :as data]
                     [recording :as recording]
                     [display :as display])))

(defn print-content [registry & {:keys [focus? content?]
                                 :or {focus? true
                                      content? true}}]
  (println "CYCLE" (reg/cycle-num registry)
           (if focus?
             (str "FOCUS: [" (:name (reg/focus registry)) " "
                  (str (type (:source (reg/focus registry)))) "]")
             "")
           (if content?
             (str "--- CONTENT:" (pr-str (sort (map :name (reg/content registry)))))
             "")))

(defn- display-environment-messages?
  "Returns true if the registry includes a display.environment-messages component."
  [registry]
  (-> registry reg/component-registry :display.environment-messages boolean))

(defn- display-environment-message
  "Updates the environment message display with the specified message."
  [message env-output]
  (if (contains? env-output :increment-message-count?)
    (display/element-for
     :display.environment-messages message
     :increment-update-number? (:increment-message-count? env-output))
    (display/element-for :display.environment-messages message)))

;; pick the components that you want.
(defn initialize-sensors
  "Registers the sensors for a model. Uses a model's setup instructions returned by
  sensor-setup."
  [registry model-ns environment]
  (let [setup (model/initialize ((ns-resolve model-ns 'sensor-setup)))]
    (reg/register-sensors
     registry
     (model/component-names setup)
     #(model/start setup % environment))))

(defn initialize-components
  "Registers the components for a model. Uses a model's setup instructions
  returned by component-setup, but these instructions can be overwritten by
  anything in the new-setup argument."
  [registry model-ns new-setup disable-displays?]
  (reg/with-registry registry
   (let [setup (model/initialize
                (model/setup ((ns-resolve model-ns 'component-setup)) new-setup)
                disable-displays?)]
     (reg/register-components
      registry
      (model/component-names setup)
      #(model/start setup %)))))


;; linking ARCADIA's actions to an environment requires
;; passing (environment specific) action commands to that
;; environment. we grab all of these in a cycle (if any) and
;; forward them along to be processed.
;;
;; the action-command must be some form of data that the
;; environment can process.
;;
;; EDIT: the effects of actions that occur over time can be delayed until
;; completion by setting :completed? to false in the arguments list. this is
;; useful for mental actions, like subvocalization, that could delay or
;; prevent actions within the environment.
(defn environment-actions [ac]
  (doall
    (map (comp :action-command :arguments)
         (filter #(and (= (:type %) "environment-action")
                       (-> % :arguments :completed? false? not))
                 ac))))

(defn- initialize
  "Initializes the architecture, components, sensors, and model. Returns the
  registry for holding model state."
  [model-ns env component-setup disable-displays? old-registry]
  (if old-registry
    (let [reg (reg/swap-environment old-registry env)]
      (doseq [sensor (reg/sensors old-registry)]
        (sense/swap-environment sensor env))
      reg)
    (let [reg (-> (reg/make-registry env)
                  (initialize-sensors model-ns env)
                  (initialize-components model-ns component-setup disable-displays?)
                  (reg/set-strategy
                   (ns-resolve model-ns 'select-focus)))]
      reg)))

(defn- step
  "Run ARCADIA for a single cycle."
  [registry]
  (let [new-registry (-> registry
                         reg/advance-cycle
                         reg/broadcast-focus
                         reg/collect-results
                         focus-selector/select-focus
                         reg/check-if-paused)]

    (print-content new-registry :focus? true :content? true)
    (general/print-element (reg/focus new-registry))
    new-registry))

(defn- env-step
  "Take one step through the environment, and return the results."
  [registry env-actions?]
  (env/step (reg/env registry)
            (when env-actions? (environment-actions (reg/content registry)))))

(defn- finish
  "Close down and finalize output after finishing a model run."
  [registry env output-map recording-state data-state return-state?]
  (env/close env)
  (when recording-state (recording/finish-recording recording-state))
  (let [output-map
        (evaluation/finalize-output-map
         (evaluation/update-output-map output-map registry) registry)]
  (cond
    return-state?
    {:environment env :recording-state recording-state :data-state data-state
     :registry registry
     :output-map output-map}
    (seq output-map)
    output-map)))

(defn startup
  "Runs an ARCADIA model. Takes the following required arguments:
   [model-ns environment-ns env-parameters max-cycles]
   If max-cycles is nil, the model will run until the environment indicates it is done.

   Takes the following optional keyword arguments:
   component-setup: call to the function arcadia.utility.model/setup which
                    specifies that certain components should be added or
                    removed or have their parameters overwritten
   output-map: A hashmap that specifies what information should be found in the
               output-map returned by this function. Within the hashmap, keys
               should be paired with macros from arcadia.utility.evaluation.
               These macros determine the values that will be assigned to the keys in the output.
   old-registry: If this is non-nil, then an existing model run, encoded in this
                 registry, will continue on the present environment, instead of
                 creating a new registry for a new model run.
   record?: If this is true, the model run will be recorded to image/video files.
   recording-parameters: Hashmap of optional parameter values for recording.
   save-data?: If this is true, results from this run will be saved to a datafile.
   data-parameters: Hashmap of optional parameter values for saving data.
   reset-windows?: If this is true, close all JFrames from the previous run.
   disable-displays?: If this is true, disable all components that implement the
                      Display protocol.
   return-state?: If this is true, then the function returns a hasmap with the
                  keys :registry, :recording-state, :data-state, :output-map, and
                  :environment."
  [model-ns env-ns env-parameters max-cycles
   & {:keys [component-setup output-map old-registry record? recording-parameters
             save-data? data-parameters reset-windows? disable-displays? return-state?]
      :or {reset-windows? true}}]
  (when reset-windows?
    (doseq [w (java.awt.Window/getWindows)] (.dispose w))
    (System/gc))
  (let [env ((ns-resolve env-ns 'configure) env-parameters)
        init-registry (initialize model-ns env component-setup disable-displays?
                                  old-registry)
        env-messages? (display-environment-messages? init-registry)]

    (reg/with-bindings-for-run
     init-registry
     (env-step init-registry false)
     (loop [output-map output-map
            old-recording-state (when record? (recording/start recording-parameters))
            old-data-state (when save-data? (data/start data-parameters))
            old-registry init-registry]

       (let [registry (step old-registry)
             {done? :done? data :data message :message :as env-output}
             (env-step registry true)
             _ (when (and message env-messages?)
                 (display-environment-message message env-output))
             data-state (some->> old-data-state (data/save data registry))
             recording-state (some->> old-recording-state
                                      (recording/record (reg/frames registry)))]

         (if (or (and max-cycles (>= (reg/cycle-num registry) max-cycles))
                 (reg/stopped? registry) done?)
           (finish registry env output-map recording-state data-state return-state?)
           (recur (evaluation/update-output-map output-map registry)
                  recording-state data-state registry)))))))

(defn- step-times
  "Step through n processing cycles"
  [registry n]
  ((apply comp (repeat n step))
   registry))

(defn- debug-usage []
  (println "Available commands:")
  (println ":step, :s               :- step the model through one cycle")
  (println "(:step n), (:s n)       :- step the model through n cycles (where n > 0)")
  (println ":quit, :q, :exit, :e    :- stop model execution")
  (println "focus                   :- the current focus of attention")
  (println "content                 :- the current set of accessible content")
  (println "environment             :- the environment in which ARCADIA is running")
  (println "<component-name>        :- ARCADIA's current instance of the Component")
  (println "<sensor-name>           :- ARCADIA's current instance of the Sensor")
  (println ":h, :help               :- print a help message"))

(defn- debug-repl
  "The main REPL loop of the ARCADIA debugger, capable of running Clojure functions,
  stepping through an ARCADIA model, and providing the user command-line access to
  ARCADIA's focus, content, components, sensors, and environment at every cycle."
  [init-registry]
  ;; print a help message
  (println)
  (println "Welcome to the ARCADIA debugger!")
  (debug-usage)
  (loop [registry init-registry]
    ;; save ARCADIA's state into user variables
    (intern 'user 'focus (reg/focus registry))
    (intern 'user 'content (reg/content registry))
    (intern 'user 'environment (reg/env registry))
    (doseq [[kw obj] (concat (reg/component-registry registry)
                             (reg/sensor-registry registry))]
      (intern 'user (symbol (name kw)) obj))
    (print "ARCADIA=> ") (flush)

    (let [command (read)]
      (cond
        ;; print stack traces of any exception that was thrown
        (instance? java.lang.Exception command)
        (do (.printStackTrace command)
          (recur registry))

        ;; print the help statement
        (or (= command :h) (= command :help)
            (and (list? command) (keyword? (first command)) (= (count command) 1)
                 (or (= (first command) :h) (= (first command) :help))))
        (do (debug-usage) (recur registry))

        ;; quit the debug REPL
        (or (= command :q) (= command :quit)
            (= command :e) (= command :exit)
            (and (list? command) (keyword? (first command)) (= (count command) 1)
                 (or (= (first command) :q) (= (first command) :quit)
                     (= (first command) :e) (= (first command) :exit))))
        nil

        ;; step ARCADIA one cycle
        (or (= command :s) (= command :step)
            (and (list? command) (= (count command) 1)
                 (or (= (first command) :s) (= (first command) :step))))
        (recur (step registry))

        ;; step ARCADIA several cycles
        (and (list? command) (= (count command) 2)
             (or (= (first command) :s) (= (first command) :step)))
        (recur (step-times registry (second command)))

        ;; execute a regular Clojure expression
        :else
        (do
          (try
            (println (eval command))
            (catch Exception e (.printStackTrace e)))
          (recur registry))))))

(defn debug
  "Run ARCADIA for a number of cycles, then step through cycles one-by-one according to commands by the user.
  Clojure commands can also be run between cycles to inspect ARCADIA's state.
  Available commands:
      :quit, :q, :exit, :e    :- stop model execution
      :step, :s               :- steps the model through one cycle
      (:step n), (:s n)       :- steps the model through n cycles (where n > 0)
      :h, :help               :- print a help message
  The current focus of attention and accessible content are bound to the vars #'user/focus and #'user/content.
  The environment is bound to the var #'user/environment.
  Additionally, a var is defined in the user namespace for every component and sensor registered in ARCADIA."
  [model-ns env-ns environment-arguments cycles & {:keys [component-setup]}]
  (doseq [w (java.awt.Window/getWindows)] (.dispose w))
  (let [env (apply (ns-resolve env-ns 'configure) [environment-arguments])
        ;;run the model for a number of cycles
        init-registry (initialize model-ns env component-setup false nil)
        registry (reg/with-bindings-for-run init-registry
                     (step-times init-registry cycles))]

    ;; now step through the model cycle-by-cycle, taking commands from the user
    (reg/with-bindings-for-run registry
      (debug-repl registry))
    ;; clean up
    (arcadia.simulator.environment.core/close env)
    nil))
