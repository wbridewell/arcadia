(ns
  ^{:doc "Tracks the state of a model run. A registry is a hashmap which stores
  registered components (in a hashmap mapping names to components), registered
  sensors (in a hashmap mapping names to sensors), the current focus, the set
  of elements in the current accessible content, an attentional strategy,
  the cycle number, and a list of components that perform logging."}
  arcadia.architecture.registry
  (:require [arcadia.component.core :as component]
            [arcadia.utility.general :as g] 
            [arcadia.utility.swing :as swing]
            [clojure.data.generators :as dgen]
            [clojure.set]))

;; the component and sensor registries are implemented as maps between
;; names and component/sensor instances.

(def ^:dynamic ^:private *registry*
  "Can be bound to a particular registry within a limited scope. This is useful
  because for some of the functions below, if no registry is specified, then the
  registry bound to *registry* will be used." nil)

(def ^:dynamic ^:private *logger-registry*
  "Gets bound to the list of components that can log information passed by
  other components."
  nil)

(defn make-registry "Creates a registry for holding state during a model run."
  [environment deterministic?]
  {:environment environment
   :deterministic? deterministic?
   :focus nil
   :content nil
   :strategy nil
   :component-registry nil
   :sensor-registry nil
   :cycle -1 ;;Should increment before each call to broadcast-focus
   :display-support 
   {:logger-registry nil :accessor-registry nil
    :paused? (atom false) :pause-fns (atom nil) :stopped? (atom false)
    :stepped? (atom false) :thread (atom nil)}})

(defmacro with-registry
  "Sets *registry* to a particular registry just for the scope of this code."
  [registry & code]
  `(binding [*registry* ~registry]
            ~@code))

(defmacro with-bindings-for-run
  "Sets up any bindings that should be available throughout a model run."
  [registry randomizer & code]
  `(binding [*logger-registry* (-> ~registry :display-support :logger-registry)
             dgen/*rnd* ~randomizer]
            ~@code))

(defn get-bindings-for-run
  "Returns the dynamic bindings set up for this run. Should not be called directly."
  []
  [*logger-registry* dgen/*rnd*])

(defmacro invoke-with-bindings
  "Maintains the current logger bindings while entering the GUI thread."
  [& code]
  `(let [[registry# randomizer#] (arcadia.architecture.registry/get-bindings-for-run)]
     (swing/invoke-now
      (binding [*logger-registry* registry#
                dgen/*rnd* randomizer#]
               ~@code))))

(defn env
  "Returns the environment for a registry."
  [registry]
  (:environment registry))

(defn swap-environment
  "Changes the environment for a registry."
  [registry new-env]
  (assoc registry :environment new-env))

(defn components
  "Returns the components for a registry."
  [registry]
  (vals (:component-registry registry)))

(defn component-registry
  "Returns the hashmap of names to components for a registry."
  [registry]
  (:component-registry registry))

(defn sensors
  "Returns the sensors for a registry."
  [registry]
  (vals (:sensor-registry registry)))

(defn sensor-registry
  "Returns the hashmap of names to sensors for a registry."
  [registry]
  (:sensor-registry registry))

(defn strategy
  "Returns the strategy for a registry."
  [registry]
  (:strategy registry))

(defn frames
  "Returns the java frames from all components satisfying the Display protocal."
  [registry]
  (map component/frame (filter #(satisfies? component/Display %)
                               (components registry))))

(defn control-atoms
  "Returns a hash-map with :stopped?, :paused?, :stepped?, and :thread, the atoms
   that handle controlling the model run."
  ([]
   (control-atoms *registry*))
  ([registry]
   (select-keys (:display-support registry) [:stopped? :paused? :pause-fns :stepped? :thread])))

(defn stopped?
  "Returns true if a previous call to check-if-stopped indicated the model run
   should stop."
  [registry]
  (-> registry :display-support :stopped? deref))

(defn content
  "Returns the content for a registry."
  ([]
   (when *registry*
     (content *registry*)))
  ([registry]
   (:content registry)))

(defn focus
  "Returns the focus for a registry."
  ([]
   (when *registry*
     (focus *registry*)))
  ([registry]
   (:focus registry)))

(defn cycle-num
  "Get the current cycle number."
  [registry]
  (:cycle registry))

(defn get-sensor
  "Retrieve a sensor by name."
  ([name]
   (when *registry*
     (get-sensor name *registry*)))
  ([name registry]
   (get (:sensor-registry registry) name)))

(defn set-focus
  "Sets the focus of attention to the specified element."
  [registry focus]
  (assoc registry :focus focus))

(defn set-content
  "Sets the accessible contents to the contents of the given set."
  [registry content]
  (assoc registry :content content))

(defn set-strategy
  "Sets the attentional strategy within the registry."
  [registry strategy]
  (assoc registry :strategy strategy))

(defn advance-cycle
  "Increments the cycle counter and updates all the logger and accessor components."
  [registry]
  (let [support (:display-support registry)]
    (doseq [component (vals (:accessor-registry support))]
      (component/update-registry! component registry))
    (doseq [component (vals (:logger-registry support))]
      (component/reset-logger! component (:focus registry) (:content registry)))
    (doseq [component (vals (:logger-registry support))]
      (component/update-logger! component)))

  (update registry :cycle inc))

(defn pause
  "Set the registry's :paused? flag to true."
  [{{paused? :paused? pause-fns :pause-fns} :display-support :as registry}]
  (when (not @paused?)
    (reset! paused? true)
    (->> pause-fns deref (map #(apply % nil)) doall))
  registry)

(defn check-if-paused
  "Checks whether the GUI thread has set the :paused? atom to true. If we are paused,
   wait until we unpause, or continue if the GUI thread has set the :stopped? or
   :stepped? atom to true. If :stepped? is true, set it to false after progressing."
  [{{stopped? :stopped? paused? :paused? stepped? :stepped? thread :thread} :display-support
    :as registry}]
  ;;If we are paused, then we will store the current thread so that the GUI
  ;;thread can interrupt us as soon as a button is pressed.
  (when (and @paused? (not @stopped?) (not @stepped?))
    (print "PAUSED ")
    (flush)
    (try
      (reset! thread (Thread/currentThread))
      (loop [output (concat (list "." "." ".")
                            (cycle (list "\b\b  " "\b\b. " "\b\b..")))]
        (when (and @paused? (not @stopped?) (not @stepped?))
          (Thread/sleep 250)
          (printf (first output))
          (flush)
          (recur (rest output))))
      (reset! thread nil)
      (catch InterruptedException e (reset! thread nil)))
    (println))
  (reset! stepped? false)
  registry)

(defn register-components
  "Sets up the components in the registry."
  [registry component-names start-fn]
  (let [component-registry
        (-> (g/seq-valfun->map component-names start-fn)
            (g/filter-vals some?))]
    (-> registry 
        (assoc :component-registry component-registry)
        (assoc-in [:display-support :logger-registry] 
                (g/filter-vals component-registry #(satisfies? component/Logger %)))
        (assoc-in [:display-support :accessor-registry]
                (g/filter-vals component-registry #(satisfies? component/Registry-Accessor %))))))

(defn register-sensors
  "Sets up the sensors within the registry."
  [registry sensor-names start-fn]
  (assoc registry :sensor-registry
         (-> (g/seq-valfun->map sensor-names start-fn)
             (g/filter-vals some?))))

(defn broadcast-focus
  "Make the focus of attention and accessible content available to all components."
  [registry]
  (doseq [x (components registry)]
    (component/receive-focus x (:focus registry) (:content registry)))
  registry)

(defn- update-source
  "Given the set of interlingua elements produced by a component, update their :source 
   to the specified alias."
  [elements alias]
  (->> elements (remove nil?) (map #(vary-meta % assoc :source (symbol alias)))))

(defn collect-results
  "Calls deliver-result on each component and collects the results."
  [{component-registry :component-registry :as registry}]
  (->> component-registry keys
       (map #(-> % component-registry component/deliver-result (update-source %)))
       (reduce concat)
       (map #(vary-meta % assoc :cycle (cycle-num registry)))
       ;; if not deterministic, this randomizes the order of the elements in content
       (#(if (:deterministic? registry) % (set %)))
       (assoc registry :content)))

(defn broadcast-to-logger
  "Adds the information to the log of the component with the specified alias."
  [alias information]
  (if-let [component (get *logger-registry* alias)]
    (component/log-information! component information)
    (println "UNABLE TO FIND LOGGER WITH ALIAS" alias)))
