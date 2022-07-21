
Setting up an ARCADIA development environment for Linux
=======================================================

(NOTE: Use these steps if you're setting up ARCADIA specifically on Linux. These steps worked for Ubuntu on the Windows Subsystem for Linux, so your mileage may vary. Substitute in the appropriate package management software for apt, and bear in mind that opencv may be installed elsewhere on your system.)

Clojure is the primary language for ARCADIA development, although components can be written in any language that is accessible through a wrapper in the Java Native Interface. Creating a Clojure development environment is fairly straightforward.

Installing software
-------------------

1.  Install the latest version of the Java Developers Kit.  
    1.  `sudo apt update`
    2.  `sudo apt install openjdk-14-jdk`
    3.  (To see the versions currently available, try: `apt search openjdk`)
2.  Install [Leiningen](http://leiningen.org).
    1.  `sudo apt install leiningen`
3.  Install the [Git](http://git-scm.com) version control software.
    1.  Can use apt, but this is likely already installed.
4.  Install the [Maven](http://maven.apache.org) tool for Java project management.
    1.  `sudo apt install maven`
5.  Retrieve the base ARCADIA workspace from the project's Git repository.
    1.  See detailed instructions below which also include details on getting OpenCV.
6.  Install the [Tesseract OCR](https://code.google.com/p/tesseract-ocr/) system and the [Tess4J](http://tess4j.sourceforge.net) Java wrapper.  
    1.  Tess4J should be automatically added to your repository by Leiningen when you start your Clojure REPL. Check your project.clj file for the line `[net.sourceforge.tess4j/tess4j "5.2.1"]`.
7.  To use the gym-minigrid environment, install the Python packages.
    1. pip install gym==0.21.0 gym-minigrid==1.0.3
    2. there was a change to the render API in gym 0.25.0, so earlier versions are currently needed for compatibility

Setting up the git repository
-----------------------------

1.  Change to the directory that will contain your Clojure projects (ensure that the pathname does not include any spaces).
2.  git clone https://github.com/wbridewell/arcadia.git
        

Setting up OpenCV / Updating OpenCV
-----------------------------------

The repository includes a Leiningen project tree, a project file, and source code for ARCADIA. However, you will be required to build install certain development libraries and add files to your local repository. Although there are efforts to incorporate [CMU Sphinx](http://cmusphinx.sourceforge.net) and [ZeroMQ](http://zeromq.org), the principle requirement at this time is [OpenCV](http://docs.opencv.org).

The following should be done in your arcadia directory. (**Important**: These instructions are for version 4.2.0. Change this number to the version you have installed when following them.)

1.  `sudo apt update`
    
2.  Unless you have already done so, install Apache Ant
    
    `sudo apt install ant`
    
3.  Uninstall your previous version of opencv, if desired.  
    `sudo apt remove (previous opencv name)`
4.  Install opencv for java  
    `apt search libopencv` (to see current versions)  
    `sudo apt install libopencv4.2-jni/focal`  
    `sudo apt install libopencv4.2-java/focal`
5.  `cp /usr/share/java/opencv-420.jar ./opencv.jar`
6.  `cp /usr/lib/jni/libopencv_java420.so .`
7.  Create the native code JAR for OpenCV
    1.  If you don't have a previous version
        
        `mkdir -p native/linux/x86_64` 
        
        `mkdir -p target/native/linux/x86_64`
    2.  if you do
        
        `mv [libopencv_java420.so](http://libopencv_java420.so) native/linux/x86_64/`
        
        `cp native/linux/x86_64/[libopencv_java420.so](http://libopencv_java420.so) target/native/linux/x86_64/` 
        
        (This step is supposed to happen automatically, but you may need to do it manually.)
8.  `jar -cMf opencv-native.jar native`
    
9.  Deploy the JAR files using Maven
    1.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv -Dversion=arcadia -Dpackaging=jar -Dfile=opencv.jar -Durl=file:repo`
        
    2.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv-native -Dversion=arcadia -Dpackaging=jar -Dfile=opencv-native.jar -Durl=file:repo`
        
10.  Ensure that your project.clj file contains a reference to the OpenCV libraries under `:dependencies`.
    
        1.  `[local/opencv "arcadia"]`
            
        2.  `[local/opencv-native "arcadia"]`
        
11.  `lein deps`
    
12.  `lein repl`
    1. To use python libraries, run with `lein with-profile python repl`
    
13.  `(refresh)`
