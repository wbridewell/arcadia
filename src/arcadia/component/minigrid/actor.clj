(ns arcadia.component.minigrid.actor
  "Actions, or more accurately, requests to initiate an action, do not operate
  directly on the environment. Instead, once they are focused on, they must be
  translated into a format that can be processed by the environment. Here, for
  instance, the request to push a button is turned into an element that has
  an action-command (a button name) that ARCADIA then passes to the environment
  to initiate the effects.

  This component is used in task switching environments where the response is
  to push a button based on features of stimuli, but it could in principle be
  used in different environments for different kinds of tasks.

  Focus Responsive
    * push-button, name & action, type

  Converts an action request into an environment action for push-button.

  Default Behavior
  None

  Produces
   * minigrid-env-action (environment-action)
       includes an :action-command that is interpretable by the current
       simulation environment."
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component]]))

(defn- make-env-action [e source]
  (when e
    {:name "minigrid-env-action"
     :arguments {:action-command (-> e :arguments :action-command)}
     :world nil
     :source source
     :type "environment-action"}))

(defrecord MinigridActor [buffer]
  Component
  (receive-focus
   [component focus content]
   (if (d/element-matches? focus :name "minigrid-action" :type "action" :world nil)
     (reset! (:buffer component) (make-env-action focus component))
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method MinigridActor [comp ^java.io.Writer w]
  (.write w (format "MinigridActor{}")))

(defn start []
  (->MinigridActor (atom nil)))
