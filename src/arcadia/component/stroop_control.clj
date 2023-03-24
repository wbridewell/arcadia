(ns arcadia.component.stroop-control
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

(def ^:parameter base-threshold "the baseline threshold for producing a response on the Stroop stimuli" 11)
(def ^:parameter conflict-threshold "the threshold after noticing a response conflict" 13)

(defn- parameter-update [component threshold conflict?]
  (let [response-value (-> @(:buffer component) :arguments :lexeme)]
    {:name "update-response-parameters"
     :arguments {:threshold threshold
                 :conflict? conflict?}
     :world nil 
     :type "automation"}))

;; When a response is given, this component will adjust any component parameters
;; that need to be altered as a consequence of conflict. The idea is that when a 
;; subvocalization response is output it will either appear with a conflict 
;; report or it will not. If there is no conflict, Stroop control set perceptual
;; inhibition to default levels (0) and lower the threshold for an answer (11). 
;; If there is a conflict, the effect of perceptual inhibition will decrease
;; (* 0.5), and the threshold will be raised (13).
;;
;; We could potentially adapt this component in the future to handle go/no-go
;; activity for responses based on the task and the response values giving it
;; a broader role in control. For now, it is only adjusting component parameters
;; that will alter the subsequent trial. 

(defrecord StroopControl [buffer parameters]
  Component
  (receive-focus
    [component focus content]
   ;; only active when there is a response
    (reset! (:buffer component) nil)
    (when (d/element-matches? focus :name "subvocalize")
      (if (d/first-element content :name "response-conflict")
       ;; there is a conflict, so update parameters for incongruency
        (reset! (:buffer component) (parameter-update component (-> component :parameters :conflict-threshold) true))
       ;; there is no conflict, so reset parameters
        (reset! (:buffer component) (parameter-update component (-> component :parameters :base-threshold) false)))))

  (deliver-result
    [component]
   ;(list @buffer)
    ))

(defmethod print-method StroopControl [comp ^java.io.Writer w]
  (.write w (format "StroopControl{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->StroopControl (atom nil) p)))