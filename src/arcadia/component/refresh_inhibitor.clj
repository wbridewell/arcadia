(ns arcadia.component.refresh-inhibitor
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component]]))

(defn- inhibit [element source]
  {:name "inhibition"
   :arguments {:element element}
   :world nil
   :type "instance"
   :source source})

(defrecord RefreshInhibitor [buffer] Component
  (receive-focus
   [component focus content]
   ;; if an element in working-memory has just been attended to,
   ;;  inhibit it so we don't refresh it again
   (cond
     (= (:world focus) "working-memory")
     (reset! (:buffer component) (inhibit focus component))

     (and (or (= (:name focus) "refresh")
              (= (:name focus) "vocalize")
              (= (:name focus) "subvocalize"))
          (-> focus :arguments :completed?)
          (-> focus :arguments :reason (not= "preventative"))  ;; don't inhibit preventative refresh
          (= (-> focus :arguments :element :world) "working-memory"))
     (reset! (:buffer component)
             (inhibit (-> focus :arguments :element) component))

     :else
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method RefreshInhibitor [comp ^java.io.Writer w]
  (.write w (format "RefreshInhibitor{}")))

(defn start []
  (->RefreshInhibitor (atom nil)))
