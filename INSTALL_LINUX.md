
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
    1.  Tess4J should be automatically added to your repository by Leiningen when you start your Clojure REPL. Check your project.clj file for the line `[net.sourceforge.tess4j/tess4j "4.4.1"]`.
7.  Install [jbox2d](https://github.com/jbox2d/jbox2d) for your platform.

Setting up the git repository
-----------------------------

1.  Change to the directory that will contain your Clojure projects (ensure that the pathname does not include any spaces).
2.  `lein new arcadia --force`
    
3.  `cd arcadia`
    
4.  Setup the git repository. This is a little different because we're using an existing directory.
    1.  `git init`
        
    2.  `git remote add origin [https://your.name@bitbucket.di2e.net/scm/arcadia/arcadia.git](https://your.name@bitbucket.di2e.net/scm/arcadia/arcadia.git)`
        
    3.  `git fetch`
        
    4.  `rm [README.md](http://README.md) project.clj src/arcadia/core.clj LICENSE .gitignore`
        
    5.  `git checkout -t origin/master`
        

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
    
13.  `(load "arcadia/core")`
    

Setting up jbox2d
-----------------

The current version of arcadia requires that you also have jbox2d installed.

1.  Download [jbox2d](https://github.com/jbox2d/jbox2d)
    
    1.  `git clone [https://github.com/jbox2d/jbox2d.git](https://github.com/jbox2d/jbox2d.git)`
2.  Navigate to wherever you placed jbox2d and into the jbox2d-library
3.  Fix the file pom.xml at this location
    1.  Add `<source>1.8</source>`, followed by `<target>1.8></target>` just above "`<excludes>`..."
4.  In a terminal, navigate to jbox2d-library and run 
    
    1.  `mvn install`
        
5.  Copy the file found in `jbox2d-library/target` to your arcadia directory.
    
6.  Navigate to your arcadia directory in a terminal.
    
7.  `mv jbox2d-library-2.3.1-SNAPSHOT.jar jbox2d.jar`
    
8.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=jbox2d -Dversion=2.3.1 -Dpackaging=jar -Dfile=jbox2d.jar -Durl=file:repo`