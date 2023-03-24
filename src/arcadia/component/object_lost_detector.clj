(ns arcadia.component.object-lost-detector
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component]]))

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

(defn- no-matches
  [component]
  {:name "object-lost"
   :arguments {:descriptor @(:descriptor component)}
   :type "instance"
   :world nil})

(defrecord ObjectLostDetector [buffer descriptor] Component
  (receive-focus
   [component focus content]
   ;(debug-text "OLD" @(:descriptor component))
   ;; update the tracked object when you enter a new context
   (when-let [new-parameters (:arguments (d/first-element content :name "update-lost-detector" :type "automation" :world nil))]
     (when (contains? new-parameters :descriptor)
       (reset! (:descriptor component) (-> new-parameters :descriptor))))

   (if (and @(:descriptor component)
            (d/none-match (d/extend-descriptor @(:descriptor component) :tracked? true) content))
     (reset! (:buffer component) (no-matches component))
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   (list @buffer)))

(defmethod print-method ObjectLostDetector [comp ^java.io.Writer w]
  (.write w (format "ObjectLostDetector{}")))

(defn start []
  (->ObjectLostDetector (atom nil) (atom nil)))
