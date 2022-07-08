(ns arcadia.component.stroop-articulator
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.descriptors :as d]))

;;
;;
;; Focus Responsive
;;
;;
;; Default Behavior
;;
;;
;; Produces
;;
;;

;; turns a color word into the action name for the stroop environment
(defn- color-action [w]
  (case w
    "red" :say-red
    "blue" :say-blue
    "green" :say-green
    :say-word))

(defrecord StroopArticulator [buffer parameters] 
  Component
  (receive-focus
   [component focus content]
   (if (d/element-matches? focus :name "subvocalize")
     (reset! (:buffer component) 
             {:name "vocalize"
              :arguments (merge (:arguments focus) {:action-command (color-action (-> focus :arguments :lexeme))})
              :source component
              :type "environment-action"})
     (reset! (:buffer component) nil)))
   
  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method StroopArticulator [comp ^java.io.Writer w]
  (.write w (format "StroopArticulator{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->StroopArticulator (atom nil) p)))