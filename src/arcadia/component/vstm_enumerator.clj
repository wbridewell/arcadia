;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; vstm-enumerator -- component that produces an element containing a numerosity representation
;; based on the number of enumeration-relevant objects in VSTM.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns arcadia.component.vstm-enumerator
  "This component is part of ARCADIA's subitizing functionality.

  Focus Responsive
    * n/a

  Default Behavior
  Produces vstm-enumeration when either vSTM is at capacity, or when
    task-report signals are detected.

  Produces
  vstm-enumeration - contains an exact numerosity associated with
    the number of sortal elements encoded in vSTM.

  Displays
  n/a"
  (:require [arcadia.utility.objects :as obj]
            [arcadia.vision.regions :as reg]
            [arcadia.component.core :refer [Component merge-parameters]]))

(def min-area 50)
(def max-area 10000)

(def ^:parameter vstm-size "number of elements in vSTM" 5)

(defn- task-object?
  ([obj descriptors]
   (let [seg-area (-> obj :arguments :region reg/area)
         shape-desc (:shape-description (:arguments obj))
         size-desc (:size-description (:arguments obj))]
       ;(and (> seg-area min-area) (< seg-area max-area))
       ;(and (= size-desc "small") (= shape-desc "rectangle"))
       ; (and (= size-desc "large") (= shape-desc "circle"))
     (if (empty? descriptors)
       true
       (and (some #(= shape-desc %) descriptors)
            (some #(= size-desc %) descriptors)))))
  ([obj]
   (task-object? obj [])))

(defn- mask-object? [obj content]
  (when obj
    (> (reg/area (obj/get-region obj content)) 100000)))

(defn generate-enumeration [number component]
  {:name "vstm-enumeration"
   :arguments {:count number}
   :world nil
   :source component
   :type "instance"})

;; task-specific filters

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- number-candidates [content]
  (let [fixation-candidates (filter #(and (= (:name %) "fixation")
                                          (= (:reason (:arguments %)) "enumeration")) content)
        object-location-candidates []] ;(filter #(and (= (:name %) "object-location")) content)

    (+ (count fixation-candidates) (count object-location-candidates))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord VSTMEnumerator [buffer vstm-size]
  Component
  (receive-focus
    [component focus content]
    (let [objects (obj/get-vstm-objects content)
          enumeration-subset (first (filter #(= (:name %) "enumeration-subset") content))
          descriptors (if enumeration-subset (:descriptors (:arguments enumeration-subset)) [])
          task-objects (filter #(and (= (:name %) "object")
                                     (task-object? % descriptors)
                                     (= (:world %) "vstm")) content)
          counter (first (filter #(and (= (:name %) "lexeme")
                                       (= (:world %) "phonological-buffer")) content))]

      ;; produce a subitized count if vstm is full or matches number of candidate fixations
      (cond
        (and (some #(mask-object? % content) objects) (not= (:name focus) "object"))
        (reset! (:buffer component) (generate-enumeration (count task-objects) component))

        (and (nil? counter)
             (not (or (= (:name focus) "memorize")
                      (= (:name focus) "object")))
             (> (count task-objects) 0)
             (>= (count task-objects)
                 (min (- vstm-size 1)
                      (max 1 (number-candidates content)))))
        (reset! (:buffer component) (generate-enumeration (count task-objects) component))

        (and (nil? counter)
             (not (or (= (:name focus) "memorize")
                      (= (:name focus) "object")))
             (> (count task-objects) 0)
             (>= (count objects)
                 (min (- vstm-size 1) (max 1 (number-candidates content)))))
        (reset! (:buffer component) (generate-enumeration (count task-objects) component))

        :else
        (reset! (:buffer component) nil))))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method VSTMEnumerator [comp ^java.io.Writer w]
  (.write w (format "VSTMEnumerator{}")))

(defn start
  [& {:as args}]
  (VSTMEnumerator. (atom nil) (:vstm-size (merge-parameters args))))
