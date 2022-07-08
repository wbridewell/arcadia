(ns arcadia.component.element-preserver
  "Some components need the results of their computation to
  repeatedly be broadcasted into accessible content until
  the element is attended to. Rather than have that mechanism
  be implemented in every such component, this component repeatedly
  broadcasts each element that fits the given predicate until
  that element is attended to.

  Usually, it should suffice to provide a predicate which simply
  tests for name equality, or for the element's source component.

  Focus Responsive
   * Any element being attended to will no longer be broadcasted

  Default Behavior
   * Re-broadcasts any element which is true when applied
     to the provided predicate"
  (:require [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter ^:required predicate "a predicate that applies to interlingua elements (required)" nil)

(defn- update-element [element old new]
  (if (and (= (:name element) "memory-update")
           (= (:old (:arguments element)) old))
    (assoc-in element [:arguments :old] new)
    element))

(defrecord ElementPreserver [buffer predicate] Component
  (receive-focus
   [component focus content]
   ;; Each cycle, report anything from the last cycle
   ;; for which the predicate function is true
   (reset! (:buffer component)
           (filter #(and (predicate %) (not= % focus)) content))

   ;; update all of the objects internal to the elements
   (doseq [equality (filter #(and (= (:name %) "memory-equality")
                                  (-> % :arguments :old seq))
                            content)]
     (reset! (:buffer component)
             (map #(update-element % (-> equality :arguments :old)
                                   (-> equality :arguments :new))
                  @(:buffer component)))))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method ElementPreserver [comp ^java.io.Writer w]
  (.write w (format "ElementPreserver{}")))

(defn start [& {:as args}]
  (->ElementPreserver (atom {}) (:predicate (merge-parameters args))))
