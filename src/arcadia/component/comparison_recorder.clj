(ns arcadia.component.comparison-recorder
  "This component responds to feature comparisons across objects by requesting
  that the comparison result be stored in working memory.

  Focus Responsive
    * comparison, name & instance, type

  Issues a request to memorize the comparison.

  Default Behavior
  None.

  Produces
   * memorize
       includes an :element argument that contains the result of the
       comparison for later reference."
  (:require [arcadia.component.core :refer [Component]]))


(defn record-comparison [element source]
  {:name "memorize"
   :arguments {:element element}
   :type "action"
   :world nil
   :source source})

(defrecord ComparisonRecorder [buffer]
  Component
  (receive-focus
   [component focus content]
   ;; NOTE:
   ;; this is the sort of thing that we might want to hang around for a few
   ;; cycles. perhaps some components should broadcast repeatedly for awhile
   ;; before dropping their buffered content?
   (if (and (= (:name focus) "comparison")
            (= (:type focus) "instance"))
     ;(do (spit "debug.txt" (str "COMPARISON RESULT:"  (-> focus :arguments :longer) "\n") :append true)
     ;(do (spit "enumeration-data.txt" (str (-> focus :arguments :longer) ", ") :append true)
     (do (println "COMPARISON RESULT:" (-> focus :arguments :longer))
      (reset! (:buffer component) (record-comparison focus component)))
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method ComparisonRecorder [comp ^java.io.Writer w]
  (.write w (format "ComparisonRecorder{}")))

(defn start []
  (->ComparisonRecorder (atom nil)))
