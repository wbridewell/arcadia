(ns
  ^{:doc "Macros for setting up components."}
  arcadia.utility.model
  (:require [arcadia.utility.general :as g]))

(def component-alias-namespace
  "The namespace in which component aliases are specified."
  'arcadia.models.core)

(def display-namespace
  "The namespace in which the Display protocol is found."
  'arcadia.component.core)

(def parameter-configuration-namespace
  "The namespace in which parameter configurations for components are specified."
  'arcadia.component.parameter-configurations)

(def ^:private ^:dynamic *component-alias*
  "The alias of the component currently being started."
  nil)

(defn error-string
  "If we are currently in the process of starting a component, this function
   will update an error string with a prefix given the component's alias."
  [message]
  (if *component-alias*
    (str "Error starting component or sensor with alias " *component-alias* "\n"
         message)
    message))

(defn component-name->namespace
  "Finds the namespace for a component-name, or throws an error if none exists."
  [comp-name]
  (or ((symbol comp-name) (ns-aliases component-alias-namespace))
      (some-> (ns-publics parameter-configuration-namespace)
              ((symbol comp-name)) var-get :namespace find-ns)
      (throw (IllegalArgumentException.
              (str "Component " comp-name " cannot be found in "
                   component-alias-namespace " or "
                   parameter-configuration-namespace ".")))))

(defn component-name->parameters
  "Returns the code to get any  recorded parameter configuration for the component name."
  [comp-name]
  (some->> (ns-publics parameter-configuration-namespace)
          ((symbol comp-name))
          ; (format "(-> %s var-get :parameters)") symbol))
          var-get :parameters))

(defn component-names
  "Returns a list of the component names, given a data structure created by
  calling the function returned by setup."
  [components]
  (keys components))

(defn start
  "Calls a component's start function with the parameters specified by the data
  structure created by setup. If an extra-arg is specified, insert it as the
  first argument to the function call."
  ([components comp-name]
   (let [item (comp-name components)
         args (when (seq (:params item))
                (interleave (keys (:params item)) (vals (:params item))))]

     (when (and (:fn item) (not (:disabled? item)))
       (binding [*component-alias* comp-name]
                (apply (:fn item) args)))))

  ([components comp-name extra-arg]
   (let [item (comp-name components)
         args (when (seq (:params item))
                (interleave (keys (:params item)) (vals (:params item))))]

     (when (and (:fn item) (not (:disabled? item)))
       (binding [*component-alias* comp-name]
                (apply (:fn item) (cons extra-arg args)))))))

(defmacro setup
  "Sets up the components that will be used by a model. You should use the
  macros setup, add, enable, and disable as its arguments. Note that each of
  these macros returns a 0-arity function that will be called at runtime."
  [& args]
  `(fn []
     (reduce arcadia.utility.general/nested-merge
             (map #(%)
                  (filter fn? (list ~@args))))))

(defmacro add
  "Adds a component to the list of components that will be used by a model."
  ([comp-name]
   `(add ~comp-name {} nil))
  ([comp-name params]
   `(add ~comp-name ~params nil))
  ([comp-name params alias]
   `(fn [] (hash-map
            (or ~alias (keyword (quote ~comp-name)))
            (hash-map
             :fn ~(ns-resolve (component-name->namespace comp-name)
                              'start)
             :ns ~(component-name->namespace comp-name)
             :params
             (merge
              (arcadia.utility.model/component-name->parameters (quote ~comp-name))
              ~params)
             )))))

(defmacro multi-add
  "Adds one or more components to the list of components that will be used in a
   model, without specifying parameters or aliases for them."
  [& comp-names]
  `(setup
    ~@(map (fn [comp-name] `(add ~comp-name {} nil)) comp-names)))

(defmacro enable
  "Specifies that a component should be enabled in a model."
  [comp-name]
  `(fn [] (hash-map
           (keyword (quote ~comp-name))
           (hash-map
            :disabled? false))))

(defmacro disable
  "Specifies that a component should be disabled in a model."
  [comp-name]
  `(fn [] (hash-map
           (keyword (quote ~comp-name))
           (hash-map
            :disabled? true))))

(defn update-parameters
  "Provides an alternate mean to add for updating parameters in one or more
   components. Because this is a function and not a macro, it can be resolved
   at run time. Takes a list of vectors of the form [component-name parameter value]."
  [params]
  (fn []
    (reduce arcadia.utility.general/nested-merge
            (map (fn [[comp-name param-name val]]
                   (hash-map
                    (and (component-name->namespace comp-name)
                         (keyword comp-name))
                    (hash-map :params (hash-map param-name val))))
                 params))))

(defn initialize
  "The macros setup/add/enable/disable create a function that must then be called
   to create the hashmap that describes the components or sensors in a model.
   initialize calls the function and then removes any display components, if
   desired."
  ([setup] (initialize setup false))

  ([setup disable-display-components?]
   (let [hmap (setup)
         display-type (-> display-namespace (ns-resolve 'Display) symbol eval)]
     (if disable-display-components?
       (g/remove-vals
        hmap
        (fn [comp]
          (g/find-first
           #(and (= (type %) java.lang.Class) (extends? display-type %))
           (-> comp :ns ns-map vals))))
       hmap))))
