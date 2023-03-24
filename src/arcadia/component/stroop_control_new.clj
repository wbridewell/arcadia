(ns arcadia.component.stroop-control-new
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
(def ^:parameter conflict-threshold "the threshold after noticing a response conflict" 13) ; 11

(defn- compute-control [conflict-history]
  (if (empty? conflict-history)
    1.4
    (- 1.4
       (* 1.3
          (- 1 (/ 1 (+ (Math/pow 2 (count (remove zero? (take 5 conflict-history)))))))))
    #_(- 1.4
         (* 1.39
            (/ (apply + (take 5 conflict-history))
               (min (count conflict-history) 5)))))
  #_(if (and (first conflict-history) (zero? (first conflict-history)))
      1.2
      1.0)
;1.1
  #_(if (empty? conflict-history) 1.0
        (reduce - 1 (for [x (range 1 (inc (count conflict-history)))
                          :let [ctl-contrib-x (/ 1 (Math/pow 2 x))]]
                      ctl-contrib-x))))


(defn- parameter-update [component threshold conflict?]
;  (let [response-value (-> @(:buffer component) :arguments :lexeme)]

  {:name "update-response-parameters"
   :arguments {:threshold threshold
               :conflict? conflict?
               :control-value (compute-control @(:history component))}
   :world nil
   :type "automation"})

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


(defrecord StroopControlNew [buffer history control-history parameters]
  Component
  (receive-focus
    [component focus content]
   ;; only active when there is a response
    (reset! (:buffer component) nil)

    (when (d/element-matches? focus :name "subvocalize")
      (if (d/first-element content :name "response-conflict")
      ;; there is a conflict, so update parameters for incongruency
        (do
          (reset! (:history component) (conj @(:history component) 1))
          (reset! (:control-history component) (conj @(:control-history component) 1))
          (reset! (:buffer component) (parameter-update component (-> component :parameters :conflict-threshold) true)))
      ;; there is no conflict, so reset parameters
        (do
          (reset! (:history component) (conj @(:history component) 0))
          (reset! (:control-history component) ())
          (reset! (:buffer component) (parameter-update component (-> component :parameters :base-threshold) true))))))

  (deliver-result
    [component]
    (list @buffer)))


(defmethod print-method StroopControlNew [comp ^java.io.Writer w]
  (.write w (format "StroopControlNew{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->StroopControlNew (atom nil) (atom ()) (atom ()) p)))
