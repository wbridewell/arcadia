(ns arcadia.component.feedback
  "When actions are attended to, output a feedback signal to stop requesting the action.

  Focus Responsive
     * action - some action that's being executed in the environment

  Default Behavior
   None.

  Produces
    * action-feedback- an interlingua indicating that an action-command was sent to the environment"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.sensor.core :refer [poll]]))

(def ^:parameter ^:required sensor "a sensor that provides action input (required)" nil)

(defn- feedback [cmd]
  {:name "action-feedback"
   :arguments {:action-command cmd}
   :type "feedback"
   :world nil})

(defrecord Feedback [buffer sensor] Component
  (receive-focus
   [component focus content]
   (->> sensor
        poll
        (map feedback)
        (reset! (:buffer component))))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method Feedback [comp ^java.io.Writer w]
  (.write w (format "Feedback{}")))

(defn start [& {:as args}]
  (->Feedback (atom nil) (:sensor (merge-parameters args))))
