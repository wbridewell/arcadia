(ns
  ^{:doc "Supports formatting text and images for display components."}
  arcadia.display.formatting
  (:require clojure.walk
            clojure.string
            clojure.java.io
            [arcadia.utility [general :as g] [image :as img] [model :as model]
             [opencv :as cv]]
            [arcadia.vision [features :as f] [segments :as seg]]
            [arcadia.display [decompose-data :refer [decompose-data simple-literal?]]
             [image-formatting :as img-format]
             [support :as support]]
            [clojure.java.shell :refer (sh)])
  (:import [javax.swing Box ImageIcon JFrame JFormattedTextField JPanel JLabel Timer]
           [java.awt GridBagConstraints GridBagLayout]
           [java.text DecimalFormat NumberFormat]
           [javax.swing.text NumberFormatter]
           java.math.RoundingMode
           javax.swing.border.EmptyBorder
           [java.awt Dimension Toolkit]
           [java.awt.datatransfer DataFlavor StringSelection Transferable]
           [java.awt.event ActionListener MouseAdapter]
           [javax.swing.text.html HTML$Tag HTML$Attribute]))

(defn- panel-height-exception
  []
  (Exception.
   (model/error-string
    (str
     "Insufficient information to determine the height of panels in the debug\n"
     "display. Please do one of the following:\n"
     "1) Specify the height directly with the :panel-height parameter.\n"
     "2) Specify the number of elements in a panel with :panel-rows, and\n"
     "   ensure that :element-type is either :text or :image.  Also, provide sufficient\n"
     "   information to determine the image-height of any images (:image-height,\n"
     "   :sensor + :image-scale, or :sensor + :image-width). Finally, if no examples\n"
     "   of panels are provided (:panels is nil), set :headers? to true or false.\n"
     "3) For each element in a panel, ensure that :element-type is either :text\n"
     "   or :image, and :flatten-elements? is false.  Also, provide sufficient\n"
     "   information to determine the image-height of any images (:image-height or\n"
     "   :sensor and :image-scale). Finally, if no examples of panels are provided\n"
     "   (:panels is nil), set :headers? to true or false.\n"))))

(defn- panel-width-exception
  []
  (Exception.
   (model/error-string
    (str
     "Insufficient information to determine the width of panels in the debug\n"
     "display. Please do one of the following:\n"
     "1) Specify the width directly with the :panel-width parameter.\n"
     "2) Ensure that :element-type is set to :image and provide sufficient\n"
     "information to determine the image-width (:image-width, :sensor +\n"
     ":image-scale, or :sensor + :image-height)."))))

(defn- cols-rows-exception
  []
  (Exception.
   (model/error-string
    (str "Insufficient information to determine the number of rows and columns of\n"
         "panels. Please do one of the following:\n"
         "1) Specify these numbers directly with the :rows and :cols parameters.\n"
         "2) Ensure that :flatten-panels? is false for all panels."))))

(def ^:private caption-key-pairings
  "List of vectors pairing caption keys with their associated element keys."
  [[:caption-word-wrap? :word-wrap?] [:caption-center? :center?]
   [:caption-color :color] [:caption-background :text-background]
   [:caption-bold? :bold?] [:caption-italic? :italic?]
   [:caption-underline? :underline?] [:caption-strike-through? :strike-through?]
   [:caption-font-family :font-family] [:caption-size :text-size]
   [:caption-indent :text-indent] [:caption-spacing :element-spacing]
   [:caption-visible? :text-visible?]
   [:caption-paragraph-spacing :paragraph-spacing]])

(def ^:private header-key-pairings
  "List of vectors pairing header keys with their associated element keys."
  [[:header-word-wrap? :word-wrap?] [:header-center? :center?]
   [:header-color :color] [:header-background :text-background]
   [:header-bold? :bold?] [:header-italic? :italic?]
   [:header-underline? :underline?] [:header-strike-through? :strike-through?]
   [:header-font-family :font-family] [:header-size :text-size]
   [:header-indent :text-indent] [:header-spacing :element-spacing]
   [:header-visible? :text-visible?]
   [:header-paragraph-spacing :paragraph-spacing]])

(def ^:private element-only-keys
  "Set of parameters that will be ignored for captions and headers."
  #{:smart-indent? :one-per-line :key-color :value-color :link-color
    :simple-literal-color :keyword-color :number-color :string-color
    :pretty-data? :excluded-keys :browsable? :visualizable? :collapsed?})

(def ^:private char-width-cache
  "Caches the character width for different text sizes." (atom {}))

(defn- apply-caption-params
  "Replaces text element params with their associated caption params, for
   caption params that are non-nil."
  [params]
  (apply assoc (g/remove-keys params element-only-keys)
         (interleave
          (map second caption-key-pairings)
          (map (fn [[caption-key element-key]]
                 (if (some? (caption-key params))
                   (caption-key params)
                   (element-key params)))
               caption-key-pairings))))

(defn- apply-header-params
  "Replaces text element params with their associated header params, for
   header params that are non-nil."
  [params]
  (apply assoc (g/remove-keys params element-only-keys)
         (interleave
          (map second header-key-pairings)
          (map (fn [[header-key element-key]]
                 (if (some? (header-key params))
                   (header-key params)
                   (element-key params)))
               header-key-pairings))))

(defn- setup-pretty-data-params
  "Changes :font-family to \"monospace\", changes :smart-indent? to true, and
   sets up color-coding for several value types, unless those types already
   have color-coding set up for them."
  [params]
  (cond-> (merge params {:font-family "monospace" :smart-indent? true
                         :pretty-points? false})
          (nil? (:string-color params))
          (assoc :string-color (java.awt.Color. 0 179 0))
          (nil? (:value-color params))
          (assoc :value-color java.awt.Color/blue)))

(defn- setup-pretty-data-params-for-caption
  "For captions, just change the :font-family to \"monospace\" for pretty data,
   so that the caption will align the element."
  [params]
  (assoc params :caption-font-family "monospace"))

(defn- load-file-at
  "Takes a string of the form \"filepath:linenumber\" where filepath is relative
   to the top-level arcadia directory, and attempts to open the file and place
   the cursor at the specified line number."
  [file]
  (try
    (sh "atom" file)
    (catch Exception e
      (println (str "Unable to open file. Please make sure you have the Atom editor "
                    "installed. If the editor is installed, you may need to select "
                    "\"Install Shell Commands\" from the Atom menu.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utility Fns

(defn- display-one-per-line?
  "Should the items in data (which is some kind of collection) be displayed one
   per line?"
  [data params]
  (some #(or (and (seq? data) (seq? %))
             (and (set? data) (set? %))
             (and (map? data) (map? %))
             (and (vector? data) (vector? %)))
        (:one-per-line params)))

(defn- add-color
  "Add the specified color to a string, via an html tag."
  [my-string color]
  (if color
    (format "<span style=\"color:rgb(%d,%d,%d)\">%s</span>"
            (.getRed color) (.getGreen color)
            (.getBlue color)
            my-string)
    my-string))

(defn- link
  "Add a hyperlink containing the index for a particular piece of data. Also
   changes the string's color to :link-color unless colorize? is false."
  ([s index params]
   (link s index params true))
  ([s index params colorize?]
   (if (or (:browsable? params) (:visualizable? params))
     (format "<a href=\"%d\"><span style=\"text-decoration:none\">%s</span></a>"
             index (add-color s (or (and colorize? (:browsable? params)
                                         (:link-color params))
                                    (:color params)
                                    java.awt.Color/black)))
     s)))

(defn- flatten-str
  "Flatten the arguments before applying str to them."
  [& args]
  (apply str (flatten args)))

(defn- strip-html
  "Removes any html tags from a string."
  [s]
  (when s
    (-> s
        (clojure.string/replace #"<p.*?>" "\n")
        (clojure.string/replace #"<.*?>" "")
        (clojure.string/replace "&lt;" "<")
        (clojure.string/replace "&gt;" ">")
        (clojure.string/replace #"&nbsp;|&#32;" " "))))

(defn- sanitize-string
  "Make a simplified version of a string that can be copied to the clipboard."
  [s index caption?]
  (when s
    (cond-> (strip-html s)
            (> index 0)
            (-> (clojure.string/replace "<< .. " "")
                (clojure.string/replace "\n      " "\n"))
            ; caption?
            ; (#(subs % 0 (.lastIndexOf % ":")))
            )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Indexing data to support browsing

(defn- make-data-registry
  "Constructs an atom that will index each level of data and record parent-child
   relationships."
  [topmost-data]
  (atom [[topmost-data nil]]))

(defn- register-data!
  "Add data with the specified parent index to the registry."
  [data parent-index registry]
  (swap! registry conj [data parent-index])
  (dec (count @registry)))

(defn- get-data!
  "Gets the [data parent-index] in the registry associated with this index, and
   resets the registry to remove this item and everything after it."
  [index registry]
  (let [results (get @registry index)]
    (swap! registry subvec 0 index)
    results))

(defn- get-data
  "Gets the [data parent-index] in the registry associated with this index."
  [index registry]
  (get @registry index))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Java class stuff

(defn- setup-background-timer
  "Creates a timer to change a comp's background color back to its original value
   after 40 ms."
  [comp params]
  (let [listener
        (proxy [ActionListener] []
               (actionPerformed
                [ae]
                (.setBackground comp (:text-background params))
                (.repaint comp)))
        timer (Timer. 0 listener)]
    (doto timer
          (.setRepeats false)
          (.setCoalesce true)
          (.setInitialDelay 40)
          (.start))))

;;Magic to find the hyperlink associated with any MouseEvent. Taken from
;;https://stackoverflow.com/questions/12932089/handling-hyperlink-right-clicks-on-a-jtextpane
#_(defn- getEventIndex-for-JEditorPane
 "Given a MouseEvent, returns a data index from a hyperlink at the MouseEvent's
  location, or else nil."
  [e]
  (let [editor (.getSource e)
        pos (.. editor getUI (viewToModel editor (.getPoint e)))]
    (when (pos? pos)
      (when-let [link (.. editor getDocument (getCharacterElement pos)
                          getAttributes
                          (getAttribute (. HTML$Tag A)))]
        (Integer/valueOf
         (.getAttribute link (. HTML$Attribute HREF)))))))

(defn- getEventIndex-for-JLabel
 "Given a MouseEvent, returns a data index from a hyperlink at the MouseEvent's
  location, or else nil."
  [e comp]
  (let [text (.. comp getAccessibleContext getAccessibleText)
        index (.getIndexAtPoint text (.getPoint e))]
    (when (>= index 0)
      (when-let [link (.. text (getCharacterAttribute index)
                          (getAttribute (. HTML$Tag A)))]
        (Integer/valueOf
         (.getAttribute link (. HTML$Attribute HREF)))))))

(defn- getImgSrc-for-JLabel
 "Given a MouseEvent, returns a data index from a hyperlink at the MouseEvent's
  location, or else nil."
  [e comp]
  (let [text (.. comp getAccessibleContext getAccessibleText)
        index (.getIndexAtPoint text (.getPoint e))]
    (when (>= index 0)
      (let [src (.. text (getCharacterAttribute index)
                          (getAttribute (. HTML$Attribute SRC)))
            [full-match prior-match match]
            (and src (re-matches #"(.+)/(.*).png" src))]
        match))))

(defn- data->image
  "Given some piece of data and a debug-data structure, generate an image that
   depicts the data, if possible."
  [data debug-data params]
  (let [data (or (:arguments data) data)
        [px py] (support/get-point data)
        img (img-format/resolve-image
             data debug-data (assoc params :element-annotations nil
                                    :image-scale nil :image-width nil))
        scale (:visualization-scale params)]
    (cond
      img img

      (and (:region data) (or (:mask data) (:image data)))
      (img-format/resolve-image
       (or (seg/zeros data) java.awt.Color/black) debug-data
       (assoc params :image-width nil :image-height nil :image-scale scale)
              [[data]])

      (and px py)
      (some-> debug-data support/debug-data->state :image
              (img-format/resolve-image
               debug-data
               (assoc params :image-width nil :image-height nil :image-scale scale)
               [[[px py] :color (:visualization-color params) :shape nil
                 :glyph-region nil]]))

      (or (= (type data) java.awt.Rectangle)
          (= (type data) org.opencv.core.Rect)
          (:x data) (:y data) (:region data))
      (some-> debug-data support/debug-data->state :image
              (img-format/resolve-image
               debug-data
               (assoc params :image-width nil :image-height nil :image-scale scale)
               [[data :color (:visualization-color params) :shape nil
                 :glyph-region nil]])))))

(declare make-string)
(declare get-text-comp)

(defn- text-comp-listener
  "Create a listener for a text component that can support (1) copying the
   component's text into a clipboard when the user double-clicks the component,
   (2) browsing through data when the use clicks on a hyperlink, (3) visualizing
   data when the user control-clicks on a link and holds down the mouse, and (4)
   opening the text editor to the line that created this component when the user
   shift-clicks on it. This function must take several arguments so that there's
   sufficient information to create a new text component when the use clicks a link."
  ([comp text data-registry element-metadata debug-data caption? header? params]
   (text-comp-listener comp text data-registry element-metadata debug-data
                       caption? header? params (atom nil) (atom nil)))
  ([comp text data-registry element-metadata debug-data caption? header? params
    frame-buffer index-buffer]
   (proxy [MouseAdapter] []
          (mouseReleased
           [e]
           ;;Stop displaying an image based on a conrol-click
           (when @frame-buffer
             (.dispose @frame-buffer)
             (reset! frame-buffer nil))

           ;;Click on a link to browse to its associated data
           (when-let [index (and (< (.getClickCount e) 2) (not (.isPopupTrigger e))
                                 (not (.isShiftDown e)) (not (.isControlDown e))
                                 (:browsable? params)
                                 (getEventIndex-for-JLabel e comp))]
             (when (= index @index-buffer)
               (let [[new-comp new-text]
                     (get-text-comp nil caption? header? element-metadata debug-data
                                    params
                                    :data-registry data-registry :index index
                                    :return-text? true)]
                 (doto comp
                       (.setText new-text)
                       (.setPreferredSize (.getPreferredSize new-comp))
                       (.removeMouseListener this)
                       (.addMouseListener
                        (text-comp-listener
                         comp
                         (sanitize-string new-text index caption?)
                         data-registry element-metadata debug-data caption?
                         header? params frame-buffer index-buffer))
                       (.repaint)))))
           (when (.isShiftDown e)
             (g/when-let* [file (:file element-metadata)
                           line (:line element-metadata)]
                          (load-file-at (str "src/" file ":" line))))
           (reset! index-buffer nil))

          (mouseClicked
           [e]
           ;;Double click to copy the contents into the clipboard
           (when (and (= (.getClickCount e) 2) (not (.isPopupTrigger e))
                      (not (.isShiftDown e)))
             (.. Toolkit (getDefaultToolkit) (getSystemClipboard)
                 (setContents (StringSelection. (str (or text " ")))
                              nil))
             (.setBackground comp java.awt.Color/black)
             (.repaint comp)
             (setup-background-timer comp params)))
          (mousePressed
           [e]
           ;;Detect a mouse down on a link (continued under mouseReleased)
           (if-let [index (and (= (.getClickCount e) 1) (not (.isPopupTrigger e))
                               (not (.isControlDown e))
                               (getEventIndex-for-JLabel e comp))]
             (reset! index-buffer index)
             (reset! index-buffer nil))

           ;;Control click on a link and hold the mouse to bring up a temporary
           ;;image of its associated ata
           (when-let [index (and (= (.getClickCount e) 1) (.isPopupTrigger e)
                                 (:visualizable? params)
                                 (getEventIndex-for-JLabel e comp))]
             (let [[data _] (get-data index data-registry)
                   img (data->image data debug-data params)]
               (when img
                 (let [frame (JFrame.)]
                   (.add frame (-> img (ImageIcon.) (JLabel.)))
                   (.setLocation frame (.getXOnScreen e) (.getYOnScreen e))
                   (.setUndecorated frame true)
                   (.pack frame)
                   (.setVisible frame true)
                   (reset! frame-buffer frame)))))))))

(defn- icon
  "Creates html code to display the icon with the specified name."
  [name params]
  (format "<img src=\"file:///%s\" width=\"%d\" height=\"%d\"\">"
          (.getPath (clojure.java.io/resource (format "icons/%s.png" name)))
          (:icon-width params) (:icon-width params)))

(defn- icon-space
  "Creates html code to add a space between two icons."
  [params]
  (format "<img src=\"file:///%s\" width=\"%d\" height=\"%d\"\">"
          (.getPath (clojure.java.io/resource "icons/nothing.png"))
          (int (/ (:icon-width params) 2)) (:icon-width params)))

(defn- update-pause-comp
  "Updates a component that acts as a pause/play button. Sets it to indicate the
   current pause/play state, or makes it blank if the stop button has been pressed."
  [comp debug-data]
  (let [params (-> comp meta :params)
        text (cond
               (-> debug-data deref :stopped? deref)
               (str (icon "nothing" params) (icon-space params)
                    (icon "nothing" params) (icon-space params) (icon "nothing" params))

               (-> debug-data deref :paused? deref)
               (str (icon "play" params) (icon-space params)
                    (icon "step" params) (icon-space params) (icon "stop" params))

               :else ;;Currently playing
               (str (icon "pause" params) (icon-space params) (icon "nothing" params)
                    (icon-space params) (icon "stop" params)))
        [_ formatted-text]
        (get-text-comp text false false nil nil params :return-text? true)]
    (.setText comp formatted-text)))

(defn- pause-comp-listener
  "Create a listener for a pause component that can support (1) clicking on
   the text to pause/play the model,
   and (2) shift-clicking on the component to open the text editor to the line
   that created this component. element-metadata contains atoms describing the
   state of the component."
  [comp element-metadata debug-data params]
  (proxy [MouseAdapter] []
         (mouseReleased
          [e]
          (when (.isShiftDown e)
            (g/when-let* [file (:file element-metadata)
                          line (:line element-metadata)]
                         (load-file-at (str "src/" file ":" line))))

          (when-let [img (and (< (.getClickCount e) 2) (not (.isPopupTrigger e))
                              (not (.isShiftDown e)) (not (.isControlDown e))
                              (getImgSrc-for-JLabel e comp))]
            (cond
              (or (= img "pause") (= img "play"))
              (-> debug-data deref :paused? (swap! not))
              (= img "step")
              (-> debug-data deref :stepped? (reset! true))
              (= img "stop")
              (-> debug-data deref :stopped? (reset! true)))

            (some-> debug-data deref :thread deref (.interrupt))
            (when (not= img "step")
              (update-pause-comp comp debug-data))))))

(defn- breakpoint-comp-listener
  "Create a listener for a breakpoint component that can support (1) clicking on
   the text to move beyond the breakpoint (if the breakpoint is still active),
   and (2) shift-clicking on the component to open the text editor to the line
   that created this component. element-metadata contains atoms describing the
   state of the breakpoint. steps-field is the text field indicating how many
   steps to take forward when the user clicks on the text."
  [element-metadata debug-data label steps-field params]
   (proxy [MouseAdapter] []
          (mouseReleased
           [e]
           (when (.isShiftDown e)
             (g/when-let* [file (:file element-metadata)
                           line (:line element-metadata)]
                          (load-file-at (str "src/" file ":" line))))

           (when (and (not (.isShiftDown e)) (not (.isPopupTrigger e))
                      (some-> element-metadata :active? deref))
             (reset! (:active? element-metadata) false)
             (.setText label
                       (second
                        (get-text-comp "Continue steps: " false false nil nil
                                       params :return-text? true)))
             (.setEditable steps-field false)
             (reset! (:break-steps @debug-data)
                     (-> steps-field (.getText)
                         (clojure.string/replace #"," "")
                         Integer/parseInt))
             (reset! (:halted? @debug-data) false)))))

(defn- copyable-image
  "Takes a BufferedImage and makes it copyable (so that it can be placed in the
   Clipboard)."
  [img]
  (proxy [Transferable] []
         (getTransferDataFlavors
          []
          (into-array [DataFlavor/imageFlavor]))
         (isDataFlavorSupported
          [flavor]
          (= flavor DataFlavor/imageFlavor))
         (getTransferData
          [flavor]
          (when (= flavor DataFlavor/imageFlavor)
            img))))

(defn- setup-image-timer
  "Creates a timer to change a comp's image back to its original value
   after 60 ms."
  [comp old-icon]
  (let [listener
        (proxy [ActionListener] []
               (actionPerformed
                [ae]
                (.setIcon comp old-icon)
                (.repaint comp)))
        timer (Timer. 0 listener)]
    (doto timer
          (.setRepeats false)
          (.setCoalesce true)
          (.setInitialDelay 60)
          (.start))))

(defn- image-comp-listener
  "Creates a listener that (1) copies some an image into the Clipboard when the
   user double-clicks on a component and (2) opens the text editor to the line
   that created this component when the user shift-clicks on it."
  [image element-metadata comp]
  (proxy [MouseAdapter] []
         (mouseClicked
          [e]
          (when-let [matrix (when (.isControlDown e)
                              (some-> element-metadata :element f/matrix
                                      (#(when (cv/general-mat-type %) %))))]
            (g/when-let* [[w h] (cv/size matrix)
                          x (some-> (.getX e) (- (:x-indent element-metadata))
                                    (/ (.getWidth image))
                                    (#(when (and (>= % 0) (<= % 1)) %))
                                     (* w) int)
                          ;;Not sure why we need to subtract an additional 3 for
                          ;;the y-dimension, but it seems to be consistent across
                          ;;parameter changes.
                          y (some-> (.getY e) (- 3 (:y-indent element-metadata))
                                    (/ (.getHeight image))
                                    (#(when (and (>= % 0) (<= % 1)) %))
                                    (* h) int)]
              (println)
              (println (format "**Value at (%d,%d) in image of size (%d,%d)**"
                               x y w h))
              (println (cv/get-value matrix x y))
              (println)))
          (when (.isShiftDown e)
            (g/when-let* [file (:file element-metadata)
                          line (:line element-metadata)]
                         (load-file-at (str "src/" file ":" line)))))
         (mousePressed
          [e]
          (when (and (= (.getClickCount e) 2) (not (.isShiftDown e)))
            (.. Toolkit (getDefaultToolkit) (getSystemClipboard)
                (setContents (copyable-image image) nil))
            (let [icon (.getIcon comp)
                  mat (cv/new-java-mat [(.getIconWidth icon) (.getIconHeight icon)]
                                       cv/CV_8UC3 0)]
              (.setIcon comp (ImageIcon. (img/mat-to-bufferedimage mat)))
              (setup-image-timer comp icon))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-preferred-width
  "Set a comp to the specified width."
  [comp width]
  (.setPreferredSize comp (Dimension. width (.height (.getPreferredSize comp)))))

(defn- get-content-width
  "Returns [width exact?], where width is the expected width of the space
   occupied by text or an image and exact? is true if exactly that amount of
   space should be occupied."
  [caption? header? params]
  (let [indent (or (and (not (:center? params)) (:indent params)) 0)]
    (cond
      caption?
      (if (:caption-width params)
        [(- (:caption-width params) indent) true]
        [(- (:panel-width params) indent) false])
      header?
      [(- (:panel-width params) indent) true]
      :else
      [(- (:panel-width params) (or (:caption-width params) 0) indent)
       (or (:center? params) (:word-wrap? params))])))

(defn format-number
  "Takes a number and the desired max number of digits after the decimal and formats
   the number so that it will have at most that many digits. Precision can be
   negative, e.g., -1 means round to the nearest tens, -2 means round to the
   nearest hundreds, etc. If num is a string, then attempt to format any
   numbers within that string.
   If the optional argument preserve-type? is true, then if the number is a float,
   output it with a single digit after the decimal point, even if that digit is
   0."
  ([num precision]
   (format-number num precision false))
  ([num precision preserve-type?]
   (cond
     (nil? precision)
     (str num)

     (string? num)
     (cond->
      (clojure.string/replace
       num #"(?<![0-9\.])(([1-9][0-9]*)|0)\.[0-9]+(?![0-9]|\.[0-9])" ;;Find all doubles
       #(-> % first Double/valueOf (format-number precision)))
      (neg? precision)
      (clojure.string/replace
       #"(?<![0-9\.])(([1-9][0-9]*)|0)(?![0-9]|\.[0-9])" ;;Find all integers
       #(-> % first Integer/valueOf (format-number precision))))

     (or (Double/isNaN num) (Double/isInfinite num))
     (str num)

     :else
     (-> num double Double/toString (BigDecimal.)
         (.setScale precision RoundingMode/HALF_UP) (.doubleValue)
         (->> (.format (DecimalFormat.
                        (flatten-str "#" (when (pos? precision) ".")
                                     (repeat precision "#")))))
         (cond->
          (and preserve-type? (float? num))
          ((fn [s] (if (not (.contains s "."))
                     (str s ".0")
                     s))))))))

(defn- format-string
  "Format a string before displaying it. This is particularly useful for
   handling newlines within the string. Spaces is the number of spaces on the
   line before the beginning of the string."
  [data spaces color? params]
  (-> (clojure.string/replace data #"\n[ ]*" "\n")
      (clojure.string/split #"\n")
      (->> (map #(format-number % (:precision params))))
      (cond->> color?
               (map #(add-color
                      % (or (:string-color params) (:simple-literal-color params)))))
      (->> (interpose (flatten-str "\n" (repeat spaces " ")))
           (apply str))))

(defn- get-char-width
  "Returns the pixel width of a single monospace character, given a particular
   text size."
  [text-size]
  (or (@char-width-cache text-size)
      (let [compA
            (JLabel.
             (format "<html><div style='font-family: monospace; font-size: %sem'>%s</div></html>"
                     (format-number text-size 1) "A"))
            compAB
                  (JLabel.
                   (format "<html><div style='font-family: monospace; font-size: %sem'>%s</div></html>"
                           (format-number text-size 1) "AB"))
            width
            (- (.getWidth (.getPreferredSize compAB))
               (.getWidth (.getPreferredSize compA)))]
        (swap! char-width-cache assoc text-size width)
        width)))

(defn- get-spaces
  "Given a string full-s and the number of characters that appear on a line
   before that string begins, return the number of characters that appear on a
   line before that string ends."
  [full-s spaces]
  (let [s (strip-html full-s)]
    (if-let [index (clojure.string/last-index-of s "\n")]
      (- (count s) index 1)
      (+ (count s) spaces))))

(declare format-data)

(defn- make-string-for-seq
  "Helper function for format-decomposition. Given a sequence of items, return a
   string in which the items are word-wrapped and indented correctly. pre-spaces
   are the number of characters so far on the current line, whereas max-spaces
   is the max number of characters per line."
  ([items parent registry pre-spaces max-spaces depth params]
   (make-string-for-seq items parent registry pre-spaces pre-spaces max-spaces
                         false depth params ""))
  ([items parent registry curr-spaces pre-spaces max-spaces newline? depth params
    new-string]
   (if (empty? items)
     (subs new-string 1)
     (let [next (first items)
           next-string (format-data next parent registry pre-spaces max-spaces
                                    depth params)
           next-spaces (get-spaces next-string curr-spaces)
           multiline?
           (or (not (or (simple-literal? next)
                        (and (> depth (:max-depth params)) (= (get next-string 0) \<))
                        (and (map-entry? next) (every? simple-literal? next))))
               (.contains next-string "\n"))]
       (if (or newline?
               (and (nil? newline?)
                    (or multiline? (>= next-spaces max-spaces))))
         (make-string-for-seq
          (rest items) parent registry (+ 1 (get-spaces next-string pre-spaces))
          pre-spaces max-spaces (or multiline? nil) depth params
          (flatten-str new-string "\n" (repeat pre-spaces " ") next-string))
         (make-string-for-seq
          (rest items) parent registry (+ 1 next-spaces) pre-spaces max-spaces
          nil depth params (str new-string " " next-string)))))))

(defn- resolve-items
  "Takes the appropriate number of items from a decomposition, given the
   :max-collection-size in parameters."
  [items params]
  (concat
         (take (:max-collection-size params) items)
         (when (->> params :max-collection-size (nthrest items) seq)
                  ['...])))

(defn- format-decomposition
  "Helper function for make-string. Given a decomposition with the specified
   index in the registry, return a properly indented and (potentially) word-wrapped
   string describing the items in the decomposition."
  [{prefix :pre postfix :post items :items name :name
    collapse-children? :collapse-children?} index registry pre-spaces
   max-spaces one-per-line? depth params]
  (let [pre-spaces (+ pre-spaces (count prefix) (count name))
        max-spaces (- max-spaces (count postfix))
        depth (if collapse-children?
                (:max-depth params) depth)]
    (cond
      (empty? items)
      (or name (str prefix postfix))

      (and (:collapsed? params) (zero? index))
      (link (format "&lt;&lt;%s&gt;&gt;" (or name (str prefix postfix)))
            1 params)

      (> depth (:max-depth params))
      (link (format "&lt;&lt;%s&gt;&gt;" (or name (str prefix postfix)))
            index params)

      (not (:smart-indent? params))
      (flatten-str
       (link (str name prefix) index params (pos? depth))
       (interpose " "
                  (map #(format-data % index registry pre-spaces max-spaces
                                     (inc depth) params)
                       (resolve-items items params)))
       (link postfix index params (pos? depth)))

      one-per-line?
      (flatten-str
       (link (str name prefix) index params (pos? depth))
       (interpose (flatten-str "\n" (repeat pre-spaces " "))
                  (map #(format-data % index registry pre-spaces max-spaces
                                     (inc depth) params)
                       (resolve-items items params)))
       (link postfix index params (pos? depth)))

      :else
      (flatten-str
       (link (str name prefix) index params (pos? depth))
       (make-string-for-seq
        (resolve-items items params) index registry pre-spaces max-spaces
        (inc depth) params)
       (link postfix index params (pos? depth))))))

(defn- format-data
  "Generates a formatted string to display the various items appearing in a
   data structure. max-spaces is the maximum characters per line, whereas
   spaces is the number of characters on the current line before whatever we
   want to display next (whatever is in data). spaces should be 0 when this
   function is called initially. parent is the index for the larger data structure
   in which this data is nested, and depth is the current nesting level."
  [data parent registry spaces max-spaces depth params]
  (let [decomp (decompose-data data params)
        [px py] (support/get-point data)
        index (and decomp (register-data! data parent registry))]
    ;;If our topmost data item is collapsed, then register a second copy of
    ;;the topmost item that will not be collapsed.
    (when (and (:collapsed? params) index (zero? index))
      (register-data! data 0 registry))
    (cond
      (number? data)
      (add-color (format-number data (:precision params) (:pretty-data? params))
                 (or (:number-color params) (:simple-literal-color params)))

      (keyword? data)
      (add-color
       (str data) (or (:keyword-color params) (:simple-literal-color params)))

      (and (string? data) (zero? depth))
      (format-string data spaces false params)

      (and (nil? data) (zero? depth))
      " "

      (string? data)
      (format-string (str "\"" data "\"") (+ spaces 1) true params)

      (and (instance? clojure.lang.LazySeq data) (empty? data))
      (format-data '() parent registry spaces max-spaces depth params)

      (instance? java.lang.Character data)
      (add-color (str "\\" data) (:simple-literal-color params))

      (simple-literal? data)
      (add-color (if (nil? data) "nil" (str data)) (:simple-literal-color params))

      (map-entry? data)
      (let [[k v] data
            key-string
            (add-color (str "<span style=\"font-weight:bold\">"
                            (format-data k parent registry spaces max-spaces
                                         depth params)
                            "</span>")
                       (and (simple-literal? k) (:key-color params)))]
        (str key-string " "
             (add-color
              (format-data v parent registry
                           (+ 1 (get-spaces key-string spaces)) max-spaces
                           depth params)
              (and (simple-literal? v) (:value-color params)))))

      (and (:pretty-points? params) px py)
      (link (str "(" (format-data px -1 nil 0 max-spaces 0 params) ",&nbsp;"
                 (format-data py -1 nil 0 max-spaces 0 params) ")")
            index params false)

      decomp
      (format-decomposition decomp index registry spaces max-spaces
                            (display-one-per-line? data params)
                            depth params)

      (var? data)
      (str data)

      (instance? java.lang.Object data)
      (g/type-string data)

      :else
      (str data))))

(defn- convert-string-to-html
  "Converts a string to html, formats it, and enables word-wrap if desired. Changes
   whitespace to be renderable as html."
  [text width params]
  (str
   "<html><div style='"
   ;;Apparently width / 1.3 is the magic formula to set the html width correctly
   (when width
     (format "width: %dpx;" (int (/ width 1.3))))
   (when (:color params)
     (format "color: rgb(%d,%d,%d);"
             (.getRed (:color params)) (.getGreen (:color params))
             (.getBlue (:color params))))
   (when (:text-size params)
     (format "font-size: %sem;" (-> params :text-size (format-number 2))))
   (when (:font-family params)
     (format "font-family: %s;" (:font-family params)))
   (when (:bold? params)
     "font-weight: bold;")
   (when (:italic? params)
     "font-style: italic;")
   (when (or (:underline? params) (:strike-through? params))
     (format "text-decoration:%s%s;"
             (if (:underline? params) " underline" "")
             (if (:strike-through? params) " line-through" "")))
   (when (:center? params)
     "text-align: center;")
   "'>"
   (-> (str (or text " "))
       (clojure.string/replace
        " " (if (and width (not (:word-wrap? params)))
              "&nbsp;" "&#32;"))
       (clojure.string/replace
        "\n" (format "<p style=\"margin-top:%d\">"
                (:paragraph-spacing params)))
       (clojure.string/replace "\t" "&emsp;"))
   "</div><html>"))

(defn- make-string
  "Given some data structure (specified by index, which is a location in the
   data-registry), generate an appropriately formatted string to display
   it. max-spaces is the maximum number of characters that can appear on one line,
   and width is the desired width of one line."
  [index max-spaces width caption? data-registry params]
  (let [max-spaces (if (pos? max-spaces)
                     max-spaces Integer/MAX_VALUE)
        [data parent-index] (get-data! index data-registry)
        spaces (if parent-index
                 6 0)]
    (cond-> (format-data data parent-index data-registry spaces max-spaces 0 params)
            parent-index
            (->> (str (link "&lt;&lt;" 0 params) " "
                      (link ".." parent-index params) " "))
            ; caption?
            ; (str ":")
            true
            (convert-string-to-html width params))))

(defn get-pause-comp
  "Creates a special component for displaying a Pause/Play button. Returns the component
   in a form that includes metadata for tracking whether we're currently paused."
  [element-metadata debug-data params]
  (let [total-metadata (assoc element-metadata :debug-data debug-data :params params)
        new-label
        (proxy [JLabel clojure.lang.IObj] []
               (meta [] total-metadata))]
    (update-pause-comp new-label debug-data)
    (.setOpaque new-label true)
    (.addMouseListener new-label
                       (pause-comp-listener new-label element-metadata debug-data params))
    (.setBorder
     new-label
     (EmptyBorder. (int (Math/floor (/ (:element-spacing params) 2))) (:indent params)
                   (int (Math/ceil (/ (:element-spacing params) 2))) 0))
    new-label))

(defn get-breakpoint-comp
  "Creates a special component for displaying a breakpoint. Returns the component
   in a form that includes metadata for tracking whether the breakpoint is currently
   active."
  [element-metadata debug-data params]
  (let [total-metadata (assoc element-metadata :debug-data debug-data :params params)
        new-jpanel
        (proxy [JPanel clojure.lang.IObj] []
               (meta [] total-metadata))

        formatter (NumberFormatter. (NumberFormat/getIntegerInstance))
        new-c (GridBagConstraints.)
        label (JLabel.)
        steps (JFormattedTextField. formatter)
        active? (or (and (-> element-metadata :new? deref)
                         (-> debug-data deref :break-steps (swap! dec) (<= 0)))
                    (and (-> element-metadata :active? deref)
                         (-> debug-data deref :halted? deref)))
        text (if active?
               (add-color "Continue steps: " (:link-color params))
               "Continue steps: ")
        [_ formatted-text]
        (get-text-comp text false false nil nil params :return-text? true)]

    (-> element-metadata :new? (reset! false))
    (-> element-metadata :active? (reset! active?))
    (when active?
      (-> debug-data deref :halted? (reset! true)))

    (.setBorder new-jpanel
                (EmptyBorder. (int (Math/floor (/ (:element-spacing params) 2)))
                              (:indent params)
                              (int (Math/ceil (/ (:element-spacing params) 2))) 0))
    (.setText label formatted-text)
    (.setText steps "1")
    (.setEditable steps active?)
    (.setAllowsInvalid formatter false)
    (.setMinimum formatter 1)
    (.addMouseListener label
                       (breakpoint-comp-listener element-metadata debug-data
                                                 label steps params))
    (.setPreferredSize
     steps (Dimension. 120 (.height (.getPreferredSize label))))
    (.setBackground new-jpanel nil)

    ;;Layout is: Label TextField Glue-to-push-everything-else-left
    (.setLayout new-jpanel (GridBagLayout.))
    (set! (.anchor new-c) GridBagConstraints/FIRST_LINE_START)
    (set! (.gridx new-c) 0)
    (set! (.gridy new-c) 0)
    (.add new-jpanel label new-c)
    (set! (.gridx new-c) 1)
    (.add new-jpanel steps new-c)
    (set! (.gridx new-c) 2)
    (set! (.weightx new-c) 1)
    (set! (.anchor new-c) GridBagConstraints/LAST_LINE_END)
    (.add new-jpanel (Box/createVerticalGlue) new-c)
    new-jpanel))

(defn get-text-comp
  "Given some text which might be a caption, a header, or neither, construct the
   component that will display the text. Optionally, the text can be specified
   via an index in a pre-existing data-registry. Returns the component, or if
   return-text? is true, returns [component final-text]."
  [original-text caption? header? element-metadata debug-data base-params
   & {:keys [data-registry index return-text?]
     :or {data-registry (make-data-registry original-text)
          index 0 return-text? false}}]
  (let [comp (JLabel.)
        params
        (cond-> base-params
                (and caption? (:pretty-data? base-params))
                setup-pretty-data-params-for-caption
                caption?
                apply-caption-params
                header?
                apply-header-params
                (and (not caption?) (not header?) (:pretty-data? base-params))
                setup-pretty-data-params)
        params (cond-> params (:center? params) (assoc :indent 0))
        [real-width strict?] (get-content-width caption? header? params)
        width (when strict? real-width)
        char-width (get-char-width (or (:text-size params) 1))
        max-chars (if (pos? char-width) (int (/ real-width char-width)) 0)
        final-text (make-string index max-chars width caption?
                                data-registry params)]
    (doto comp
          (.setBorder (EmptyBorder. (int (Math/floor (/ (:element-spacing params) 2)))
                                    (:indent params)
                                    (int (Math/ceil (/ (:element-spacing params) 2))) 0))
          (.setText final-text)
          (.setOpaque true)
          (.setBackground (:text-background params))
          (#(if width
              (set-preferred-width
               % (+ width (:indent params)))
              %))
          (.addMouseListener
           (text-comp-listener
            comp (sanitize-string final-text index caption?)
            data-registry element-metadata debug-data caption? header? params)))
    (if return-text?
      [comp final-text]
      comp)))

(defn get-image-comp
  "Given an image and the display parameters, constructs a JLabel to display
   the image, with the appropriate formatting. When divisor is provided, we'll be
   displaying multiple images per line, so each one's width must be divided by
   this amount. When image is provided, there is no need to call resolve-image
   on element to generate the image."
  [element element-metadata debug-data params & {:keys [divisor image] :or {divisor 1}}]
  (let [[_ expected-height] (img-format/resolve-image-dimensions debug-data params)
        expected-height (or expected-height 0)
        indent (or (and (not (:center? params)) (:indent params)) 0)
        spacing (or (:element-spacing params) 0)
        img (or image (img-format/resolve-image element debug-data params))
        label (JLabel.)
        w (and img (.getWidth img))
        h (and img (.getHeight img))
        width (int (/ (first (get-content-width false false params))
                      divisor))
        y-indent (int (+ (Math/floor (/ spacing 2))
                         (-> expected-height (- h) (/ 2) Math/floor (max 0))))
        x-indent
        (if (:center? params)
          (-> width (- w) (/ 2) Math/floor int (max 0))
          indent)
        element-metadata (assoc element-metadata :element element
                                :x-indent x-indent :y-indent y-indent)]
    (if img
      (doto label
            (.setIcon (ImageIcon. img))
            (.setBorder (EmptyBorder.
                         y-indent x-indent
                         ; (int (Math/ceil (/ spacing 2)))
                         (int (+ (Math/ceil (/ spacing 2))
                                 (-> expected-height (- h) (/ 2) Math/ceil (max 0))))
                         (if (:center? params)
                           (-> width (- w) (/ 2) Math/ceil int (max 0))
                           (max 0 (- width w)))))
            (.setVerticalAlignment JLabel/CENTER)
            (.addMouseListener (image-comp-listener img element-metadata label)))
      (doto label
            (.setBorder (EmptyBorder.
                         (+ (int (Math/floor (/ spacing 2))) expected-height)
                         (+ width indent)
                         (Math/ceil (/ spacing 2)) 0))))))

(defn get-element-comp
  "Creates the appropriate swing component for displaying the element, either as
   text or as an image."
  [element element-metadata debug-data params]
  (or (when-let [imgs (and (not= (:element-type params) :text)
                           (seq? element)
                           (map #(img-format/resolve-image % debug-data params)
                                element))]
        (when (every? some? imgs)
          (map #(get-image-comp element element-metadata debug-data params :image %
                                :divisor (count imgs)) imgs)))

      (when-let [img (and (not= (:element-type params) :text)
                          (img-format/resolve-image element debug-data params))]
        (get-image-comp element element-metadata debug-data params :image img))

      (when (-> element-metadata :break-point?)
        (get-breakpoint-comp element-metadata debug-data params))

      (when (-> element-metadata :pause-button?)
        (get-pause-comp element-metadata debug-data params))

      (when (not= (:element-type params) :image)
        (get-text-comp element false false element-metadata debug-data params))))

(defn get-text-comp-height
  "Given a set of parameters, returns the height a comp displaying the specified
   text (if text is a string) or the specified number of element-rows (if text
   is not a string)."
  [text caption? header? params]
  (let [text
        (if (string? text)
          text
          (str "A" (apply str (repeat (dec (or (:element-rows params) 1)) "\n")) "B"))
        comp (get-text-comp text caption? header?
                            nil (support/initialize-debug-data params) params)]
    (.height (.getPreferredSize comp))))

(defn- get-image-comp-height
  "Returns the expected height of an image component, given the current parameters."
  [params]
  (if-let [[_ height] (img-format/resolve-image-dimensions params)]
    (+ height (:element-spacing params))
    (throw (panel-height-exception))))

(defn- get-element-height
  "Returns the expected height of the component that will display an element. The
   input should be a hashmap created by calling initialize-element."
  [{caption :caption element :element params :params :as all}]
  (if (:flatten-elements? params)
    (throw (panel-height-exception))
    (max (if (contains? all :caption)
           (get-text-comp-height caption true false params) 0)
         (or (and (= (:element-type params) :text)
                  (get-text-comp-height element false false params))
             (and (= (:element-type params) :image)
                  (get-image-comp-height params))
             (throw (panel-height-exception))))))

(defn- get-panel-height
  "Returns the expected height of a panel. The input should be a hashmap
   created by calling initialize-panel."
  [{header :header header-expected? :header-expected? panel :panel
    params :params :as all}]
  (let [base (if (or (contains? all :header) header-expected?)
               (get-text-comp-height header false true params) 0)]
    (or (:panel-height params)
        (when (and (contains? all :header-expected?)
                   (not (boolean? header-expected?)))
          (throw (panel-height-exception)))
        (when-let [rows (:panel-rows params)]
          (or (and (= (:element-type params) :text)
                   (+ base (* rows (get-text-comp-height nil false false params))))
              (and (= (:element-type params) :image)
                   (+ base (* rows (get-image-comp-height params))))
              (throw (panel-height-exception))))

        (+ base
           (reduce + (map get-element-height (:elements params)))
           (let [rows (max (:extra-panel-rows params)
                           (if (empty? (:elements params)) 1 0))]
             (or (and (zero? rows) 0)
                 (and (= (:element-type params) :text)
                      (* rows (get-text-comp-height nil false false params)))
                 (and (= (:element-type params) :image)
                      (* rows (get-image-comp-height params)))
                 (throw (panel-height-exception))))))))

(defn get-max-panel-height
  "Returns the max expected height among all panels, given the parameters."
  [params]
  (let [params (support/resolve-parameters params)]
    (or (:panel-height params)
        (apply max (map get-panel-height (:panels params))))))

(defn get-max-panel-width
  "Returns the max expected width among all panels, given the parameters."
  [params]
  (apply max
         (map (fn [{params :params}]
                (or (:panel-width params)
                    (when-let [[width height] (img-format/resolve-image-dimensions params)]
                      (when (= (:element-type params) :image)
                        (+ width
                           (if (:center? params) 0 (:indent params)))))
                    (throw (panel-width-exception))))
              (:panels (support/resolve-parameters params)))))

(defn get-cols-and-rows
  "Determines the number of rows and columns for the display."
  [params]
  (let [params (support/resolve-parameters params)
        rows (:rows params)
        cols (:cols params)
        n (count (:panels params))
        grid-cols (max 1 (or cols (Math/ceil (Math/sqrt n))))]
    (if (and (some #(-> % :params :flatten-panels?) (:panels params))
             (not (and cols rows)))
      (throw (cols-rows-exception))
      {:cols (int grid-cols)
       :rows (int (max 1 (or rows (Math/ceil (/ n grid-cols)))))})))

(defn get-text
  "Takes some data and returns the appropriate string for displaying the data.
   This string will be similar to the one produced in get-text-comp, except with
   all the html tags removed."
  [data params]
  (strip-html (make-string 0 0 0 false (make-data-registry data) params)))
