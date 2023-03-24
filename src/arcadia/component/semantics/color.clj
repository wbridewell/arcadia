(ns arcadia.component.semantics.color
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.image :as img]
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

(def ^:parameter base-strength "the strength value of this component without any enhancement"
  0.5)

(defn- report-color
  "Suppresses a particular color from being reported when multiple valid descriptions detected"
  [valid-colors rejected-colors]
  (first (remove rejected-colors valid-colors)))

(defn- get-color
  "Returns a color string for the segment or nil if it is not recognized."
  ([obj threshold]
   (get-color (-> obj :arguments :image) (-> obj :arguments :mask) threshold))
  ([img mask threshold]
   (let [mat (cv/cvt-color img cv/COLOR_BGR2HSV)]
     (report-color (filter
                    #(-> (img/color-mask mat (get img/HSV-color-ranges %))
                         (cv/mean-value :mask mask)
                         (> (* 255 threshold)))
                    (keys img/HSV-color-ranges))
                   #{"white" "yellow"}))))

;; threshold -- proportion of pixels that should be the color to apply the label
;; set to 0.5

(defrecord ColorSemantics [buffer parameters enhancement]
  Component
  (receive-focus
    [component focus content]
   ;; start over with a new task
    (when (d/element-matches? focus :name "switch-task")
      (reset! (:enhancement component) 0))
    (when-let [new-parameters (:arguments (d/first-element content
                                                           :name "update-color-semantics"
                                                           :type "automation" :world nil))]
      (when (contains? new-parameters :enhancement)
        (reset! (:enhancement component) (-> new-parameters :enhancement))))

    (reset! (:buffer component) nil)
    (let [img (cond (d/element-matches? focus :name "object" :world nil) (-> focus :arguments :image)
                    (d/element-matches? focus :name "fixation" :world nil) (-> focus :arguments :segment :image))
          mask (cond (d/element-matches? focus :name "object" :world nil) (-> focus :arguments :mask)
                     (d/element-matches? focus :name "fixation" :world nil) (-> focus :arguments :segment :mask))]

      (when-let  [color (and img (get-color img mask 0.5))]
        (reset! (:buffer component)
                {:name "semantics"
                 :arguments {:property :color
                             :color color
                             :strength (+ (-> component :parameters :base-strength)
                                          @(:enhancement component))
                             :path :color}
                 :type "instance"
                 :world nil}))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method ColorSemantics [comp ^java.io.Writer w]
  (.write w (format "ColorSemantics{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->ColorSemantics (atom nil) p (atom 0))))