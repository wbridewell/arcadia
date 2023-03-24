(ns
  ^{:doc "Defines the protocol that every component must implement."}
  arcadia.component.core
  (:require [arcadia.sensor core]
            [arcadia.utility.parameters :as parameters]))

(def #^{:macro true} merge-parameters #'parameters/merge-parameters)

(defprotocol Component
  "Components carry out all the information processing in ARCADIA. This protocol
   defines the functions within each component that are called on each cycle.
   Each component should produce interlingua elements, which are hash-maps that
   have the following structure.
     :name, a string that names the element
     :world, a string that names the world/context/situation containing the
             element, or nil if the element is part of the perceived world.
     :type, a string that specifies the type of element (instance, class, action,
            and so on) for selection purposes.
     :arguments, a map of keyword-value pairings where the keyword specifies the
                 name of the argument. any information that is passed between
                 components can appear in the argument list. all data formats
                 are allowed, including functions."
  (receive-focus [component focus content]
    "Implementations should carry out all the computation performed by the
     component with the possible exception of transforming information
     into the interlingua representation. This function receives the focus of
     attention and the current accessible content (which includes the focus).")
  (deliver-result [component]
    "Implementations should return an ordered sequence of interlingua elements to be added to
     accessible content in the next cycle."))

(defprotocol Logger
  "Components may optionally use the Logger protocal. If they do, then information
  can be passed to them directly from other components. This direct passing of
  information bypasses ARCADIA's architecture, so it should be used only for
  debugging or other functions that don't affect ARCADIA model's operation."
  (log-information! [component information]
    "Provides the component with one piece of information.")
  (reset-logger! [component focus content]
    "Reset the logger for a new cycle, following one with the specified focus and
     content.")
  (update-logger! [component]
    "Update the logger's display, after resetting it."))

(defprotocol Display
  "Components may optionally use the Display protocal. If one does, this indicates
   that the component is meant to display information in a java frame."
  (frame [component]
         "Returns the java frame in which information is displayed."))

(defprotocol Registry-Accessor
  "Components may optionally have registry access. If a component has registry access, 
    then update-registry will be called on it each cycle to provide it with the latest 
    version of the registry. Directly passing the registry to a component bypasses ARCADIA's
    architecture, so it should be used only for debugging or other functions that don't 
    affect ARCADIA's model operation."
  (update-registry! [component registry]
    "Provides the latest version of the registry to the component."))