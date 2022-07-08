(ns arcadia.component.trial-responder
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

;; Works with trial starter to respond once per trial and wait for an 
;; intertrial interval to respond again.
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

(def ^:parameter respond? "predicate that takes content as an argument and determines when a response should be made"
  (fn [content] (d/some-element content :name "vstm-enumeration" :type "instance")))
(def ^:parameter response-fn "function that takes content as an argument and determines the response to be made" 
  (fn [content] :yes-button))

(defn- push-button [component response]
  {:name "push-button"
   :arguments {:button-id response}
   :type "action"
   :world nil
   :source component})

(defrecord TrialResponder [buffer flag parameters]
  Component
  (receive-focus
   [component focus content]
  
   ;; note: type is necessary here in case an environment-action is selected as focus randomly.
   ;; it would have a nil :button-id and would match to a nil buffer. just to be safe, we will also
   ;; make sure there is an element in the buffer. 
   (cond (and @(:buffer component)
              (d/element-matches? focus :name "push-button" :type "action" :button-id (-> component :buffer deref :arguments :button-id)))
          ;; if our button push is the focus, record that we pushed the button, and 
          ;; stop trying to press the button.
         (do (reset! (:flag component) true)
             (reset! (:buffer component) nil))

         (and (true? @(:flag component))
              (d/not-any-element content :name "image-segmentation" :segments seq))
          ;; if the button was pressed and now we are at the intertrial, go ahead and 
          ;; allow the button to be pressed again.
         (reset! (:flag component) false)

         (and (false? @(:flag component))
              (respond? content)
              (d/some-element content :name "image-segmentation" :segments seq))
          ;; if there are segments, and we have not decided on a button press, and 
          ;; we are ready to respond, determine the appropriate button press.
         (reset! (:buffer component) (push-button component (response-fn content)))))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method TrialResponder [comp ^java.io.Writer w]
  (.write w (format "TrialResponder{}")))

(defn start [& {:as args}]
 (let [p (merge-parameters args)]
   (->TrialResponder (atom nil) (atom false) p)))