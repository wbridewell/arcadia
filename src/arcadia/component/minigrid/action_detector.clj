(ns arcadia.component.minigrid.action-detector
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]
            [arcadia.component.core :refer [Component]]))

;; Report an event when an action request is the focus of attention.
;;
;; Focus Responsive
;;   * :type "action" -- output an event that reports the action. the 
;;      report should appear on the same cycle that the corresponding 
;;      environment-action appears
;;
;; Default Behavior
;;   none
;;
;; Produces
;;  * event -- :event-name will be "action" the activity will be the :name 
;;             of the action request
;;

(defrecord MinigridActionDetector [buffer]
  Component
  (receive-focus
    [component focus content]
    (if (d/element-matches? focus :type "action" :action-command #{(mg/action-value "toggle") (mg/action-value "done")})
      (reset! (:buffer component)
              {:name "event"
               :type "instance"
               :source component
               :world nil
               :arguments {:event-name "action"
                           :action-command (mg/action-name (-> focus :arguments :action-command))
                           :activity (:name focus)}})
      (reset! (:buffer component) nil)))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method MinigridActionDetector [comp ^java.io.Writer w]
  (.write w (format "MinigridActionDetector{}")))

(defn start []
  (->MinigridActionDetector (atom nil)))