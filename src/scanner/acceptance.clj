(ns scanner.acceptance
  (:require clojure.set
            clojure.pprint
            [incanter
             [core :as ic]
             [io :as ioc]
             [charts :as ich]])
  (:use [scanner.io :only [list-files
                           list-directories
                           save-dataset]]
        [scanner.sensitivity :only [config->string
                                    validate-root-exists
                                    calculate
                                    directory->dataset]]
        [clojure.java.io :only [file]]
        [clojure.string :only [join
                               split]]
        [clojure.java.shell :only [sh]]))

;; So many thoughts!  OK, so since we're going to do a multiple pass
;; comparison, maybe we'll need to write out the output of
;; acceptance.exe to files.  IF SO, put them in target directory,
;; under target/data/<agman-version>/<scan-name>.csv

;; In reality, keeping the data as incanter datasets is probably best
;; to avoid disk I/O and such.  We only need to keep two datasets in
;; memory at once, and just run all the statistical functions over
;; each.

;; Which brings me to: should think of the compare stage as a
;; multi-pass operation.  Somewhere, I should be able to define (or
;; allow the user to) a set of different statistical operations to run
;; over the data and get the results of each one.

;; Also, for the future a DSL for generating expected outputs of agman
;; would be nice.  But we'll punt on that one for now.  For now, the
;; "expected" file will contain a literal line-by-line comparable
;; dataset to the one produced by the scan.  This will be
;; hand-generated...

;; Also, I realize now that the independent program is the better
;; route.  But I need directory structure to take into account that
;; there could be (and will eventually) multiple versions of agman
;; under test.  Also, we may potentially want to be able to compare
;; the metrics to each other over different version of agman.

;; More thoughts: Use the information generated by agman in a
;; higher-level way.  Look at the trends more abstractly.  Initial
;; thing, back out the avg. velocity (this is dead simple).  ^x/^t =
;; v_avg

;; More generally, the DSL can approach the thing like a physics
;; problem.  If you can specify in the DSL things like acceleration
;; (which we can pretty much always assume is constant, or even
;; better, zero), then a proper description of motion over time can
;; lead to a known (or reasonable) position at arbitrary times in
;; between.  It will contain a lot of assumptions, but it's not too
;; unreasonable (hopefully!!)

;; In essence the DSL needs to be able to say, there was movement in
;; this axis, it went x distance over y seconds.  Then the DSL will
;; produce a function that can be run over the actual dataset and do
;; the comparisons (possibly passed in as function parameters) and
;; return a seq of results.

;; Motion capture idea.  In general, could use an ultrasonic sensor
;; for position (like in physics lab) and a digital encoder for
;; rotation...

(defn write-out-config [root-path offsets sensitivities]
  (let [config-file-path (str root-path "acceptance.cfg")]
    (spit config-file-path
          (config->string offsets sensitivities))
    config-file-path))

(defn dir->test-name
  "Expects a java.io.File as directory"
  [directory]
  (first
   (split
    (.. directory
        getCanonicalFile
        getName)
    #"\.d")))

(defn file->test-name
  "Expects a java.io.File as file"
  [file]
  (.. file
      getCanonicalFile
      getName))

(defn find-test-cases
  "Returns a set of string representing all the test-cases found.  Use
  test-name->dataset to get back the actual data from the test"
  [root-path]
  (let [dir-test-cases (set
                        (map dir->test-name
                             (list-directories root-path)))
        file-test-cases (set
                         (map file->test-name
                              (list-files root-path)))]
    (clojure.set/intersection dir-test-cases
                              file-test-cases)))

(defn test-name->dataset [test-name]
  (directory->dataset (str test-name ".d")))

(defn get-exe-for-version [version-dir]
  (let [os-name (System/getProperty "os.name")]
    (cond
     (= "Linux" os-name) (file version-dir "acceptance")
     (= "Windows" os-name) (file version-dir "acceptance.exe"))))

(defn find-test-executables
  "Get a list of executables present in the \"bin/\" subdir."
  [root-path]
  (map get-exe-for-version
       (list-directories (str root-path "bin/"))))

(defn string->dataset [headers string]
  (with-redefs [ic/get-input-reader
                (fn [& args] (apply clojure.java.io/reader args))]
    (ic/col-names
     (ioc/read-dataset
      (java.io.BufferedReader.
       (java.io.StringReader. string))
      :delim \space)
     headers)))

(defn run-test-case [exe config-file dataset]
  (string->dataset
   [:timestamp :acc-x :acc-y :acc-z :gyro-x :gyro-y :gyro-z]
   (:out (sh (.getCanonicalPath exe)
             config-file
             :in (with-out-str
                   (save-dataset dataset "-"
                                 :delim " "))))))

(defn read-test-description [test-case]
  (binding [*ns* (find-ns 'scanner.acceptance)]
    (try (load-file test-case)
         (catch Exception e
           (throw (Exception. (str "Error loading test description: "
                                   test-case) e))))))

(defn get-version-from-exe [exe]
  (let [path-string (.getCanonicalPath exe)
        path-components (split path-string
                               (re-pattern
                                (java.io.File/separator)))]
    (nth path-components
         (- (count path-components) 2))))

(defn normalize-dataset [ds]
  (let [base-time (ic/sel ds :rows 0 :cols :timestamp)]
    (ic/conj-cols
     (ic/$map (fn [timestamp]
                {:timestamp (- timestamp base-time)})
              :timestamp ds)
     (ic/sel ds :except-cols :timestamp))))

(defn mean [coll]
  (let [len (count coll)]
    (if (zero? len)
      0
      (/ (reduce + coll) len))))

(defn compare-test-case [actual expected])

(defn report-test-case-results [results]
  (clojure.pprint/pprint results))

(defn process-test-names [root-path test-cases]
  (map (comp (juxt identity
                   read-test-description
                   test-name->dataset)
             (partial str root-path))
       test-cases))

(defn absolute-error [expected actual]
  (if (and (number? expected)
           (number? actual))
    (ic/abs (- expected actual))
    (print-str "Expected: " expected "actual:" actual)))

(defn relative-error [expected actual]
  (let [abs-err (absolute-error expected actual)]
    (if (= 0 expected)
      abs-err
      (* 100 (/ abs-err expected)))))

(defn split-dataset [dataset start-time duration]
  (let [before-start #(< (nth % 0) start-time)
        after-end #(> (nth % 0) (+ start-time
                                   duration))
        normalized-ds (normalize-dataset dataset)
        pre-ds (ic/sel normalized-ds :filter before-start)
        test-ds (ic/sel normalized-ds :filter #(and (not (before-start %))
                                                    (not (after-end %))))
        post-ds (ic/sel normalized-ds :filter after-end)]
    [pre-ds
     test-ds
     post-ds]))

(defn gen-efn [start-time duration distance]
  (if (= distance 0)
    (fn [ts] 0)
    (let [avg-vel (/ distance duration)]
      (fn [ts] (* avg-vel (- ts
                             start-time))))))

(def column {:x-rotation :gyro-x
             :y-rotation :gyro-y
             :z-rotation :gyro-z
             :x-translation :accel-x
             :y-translation :accel-y
             :z-translation :accel-z})

(defn last-row [ds columns]
  (let [last-map (last (:rows (ic/sel ds :columns columns)))]
    (vec (map #(% last-map) columns))))

(defn rms [efn coll]
  (let [len (count coll)
        sum-squares (reduce (fn [sum [ts actual]]
                              (+ sum (ic/sq (- actual
                                               (efn ts)))))
                            0 coll)]
    (ic/sqrt (/ sum-squares len))))

(defn rms-dataset [ds efn column]
  (-> (ic/$rollup (partial rms efn) [:timestamp column] [] ds)
      ;; This is an ugly dirty hack to use rollup to return a single
      ;; value.
      :rows
      first
      :timestamp))

(defn check-expectations [ds efn device-axis]
  (when (ic/dataset? ds)
    (let [[end-ts end-actual] (last-row ds
                                        [:timestamp
                                         (column device-axis)])]
      {:RMS-error (rms-dataset ds efn (column device-axis))
       :end-expected (efn end-ts)
       :end-actual end-actual})))

(defn get-axis-descriptions [test-description]
  (vec (select-keys test-description [:x-rotation
                                      :y-rotation
                                      :z-rotation
                                      :x-translation
                                      :y-translation
                                      :z-translation])))

(defn process-test [dataset {:keys [start-time duration radius]
                             :as test-description
                             :or {:radius 0}}]
  (let [start-time (* 1000 start-time)
        duration   (* 1000 duration)
        [pre-ds test-ds post-ds] (split-dataset dataset
                                                start-time
                                                duration)]
    (for [[device-axis distance] (get-axis-descriptions test-description)]
      (let [efn (gen-efn start-time duration distance)
            pre-efn (fn [ts] 0)
            post-efn (fn [ts] (efn (+ start-time duration)))]
        {device-axis {:pre-test  (check-expectations pre-ds
                                                     pre-efn
                                                     device-axis)
                      :test      (check-expectations test-ds
                                                     efn
                                                     device-axis)
                      :post-test (check-expectations post-ds
                                                     post-efn
                                                     device-axis)}}))))

(defn -main
  "Run the acceptance test.  Takes a single argument of a folder.
  This folder should contain the requisite scans for doing a
  sensitivity calibration and any number of identically named
  directory-file pairs of the form \"<file-name>\" and
  \"<file-name>.d\" .

  Each directory should contain an image set for a single scan.  The
  file should contain the expected outputs of the agman when run
  against that scan.

  The file acceptance.cfg will be re-created every time this runs."
  [root-dir]
  (let [root-path     (validate-root-exists root-dir)
        {:keys [offsets sensitivities]} (calculate root-dir)
        config-path (write-out-config root-path offsets sensitivities)]
    ;; Generate a config for this test

    ;; Run the combinations of version X test-case.
    ;; These are the "actual" results
    ;; TODO: Produce output, in some format!
    (clojure.pprint/pprint
     (for [ ;; Iterate over test-datasets
           [test-name test-description dataset]
           (process-test-names root-path
                               (find-test-cases root-path))
           ;; and test-executables
           exe (find-test-executables root-path)]
       (let [ds (run-test-case exe config-path
                               (normalize-dataset dataset))]
         [(last (split test-name #"/"))
          (get-version-from-exe exe)
          (process-test ds test-description)])))
    (shutdown-agents)))
