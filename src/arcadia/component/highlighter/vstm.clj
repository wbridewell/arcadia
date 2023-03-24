(ns arcadia.component.highlighter.vstm
  "This component creates fixation requests for all the objects in visual
  short-term memory. This highlighter supports task specific instructions
  where participants may be required to monitor certain objects for changes
  in properties. The idea is to \"keep looking at things you've already seen\"
  because they are task relevant.

  Focus Responsive
  * object

  The component also produces a fixation request for a newly created object that
  is in the focus, so that attention can immediately return to that object.

  Default Behavior
  Request fixation at the current location of each object represented in
  visual short-term memory.

  Produces
   * fixation
       includes an :object that contains object-file associated with the
       request and a :reason that specifies this as resulting from being an
       element in \"memory\"."
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility [descriptors :as d] [objects :as obj]]))

(defn- make-fixation [object]
  {:name "fixation"
   :arguments {:object object
               :reason "memory"}
   :world nil
   :type "instance"})


(defrecord VSTMHighlighter [buffer]
  Component
  (receive-focus
    [component focus content]
    (let [objects (cond->>
                   (obj/get-vstm-objects content :tracked? true)
                    (d/element-matches? focus :name "object" :world nil :tracked? true
                                        :slot :unallocated)
                    (cons focus))]
      (reset! (:buffer component) (doall (map #(make-fixation %) objects)))))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method VSTMHighlighter [comp ^java.io.Writer w]
  (.write w (format "VSTMHighlighter{}")))

(defn start
  []
  (->VSTMHighlighter (atom nil)))
