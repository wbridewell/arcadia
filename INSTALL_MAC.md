Setting up an ARCADIA development environment
=============================================

Clojure is the primary language for ARCADIA development, although components can be written in any language that is accessible through a wrapper in the Java Native Interface. Creating a Clojure development environment is fairly straightforward.

Installing software
=============================================

1.  Install the latest version of the Java Developers Kit.
    1.  This software is available from [Oracle](http://www.oracle.com/technetwork/java/javase/downloads/index.html) for a variety of platforms. Supposing you've downloaded it to your downloads directory on macOS...
    2.  `cd ~/Downloads`  
        `tar xf openjdk-<version>_osx-x64_bin.tar.gz`  
        `sudo mv jdk-<version>.jdk /Library/Java/JavaVirtualMachines/`
        
        Run `java -version` to make sure the latest JDK is being used. 
        
2.  Install [Leiningen](http://leiningen.org) for your platform.
    1.  If you are working on Linux, the software is likely in your package manager.
    2.  On macOS, you are encouraged to use the [Homebrew](http://brew.sh) package manager, which includes a Leiningen package.
3.  Install the [Git](http://git-scm.com) version control software.
    1.  This is readily available in package managers for Linux and macOS.
4.  Install the [Maven](http://maven.apache.org) tool for Java project management.
    1.  This is readily available in package managers for Linux and macOS.
5.  Retrieve the base ARCADIA workspace from the project's Git repository.
    1.  See detailed instructions below which also include details on getting OpenCV.
6.  Install the [Tesseract OCR](https://code.google.com/p/tesseract-ocr/) system and the [Tess4J](http://tess4j.sourceforge.net) Java wrapper. 
    1.  On macOS, you can use Homebrew to install Tesseract. On Linux, the software is likely available in your package manager.
    2.  Tess4J should be automatically added to your repository by Leiningen when you start your Clojure REPL. Check your project.clj file for the line `[net.sourceforge.tess4j/tess4j "5.2.1"]`.
    1.  Recent versions of Tess4J may have problems on macOS because the native library for tesseract is not included in the jar file. If you find this to be the case, see [Stack Overflow](https://stackoverflow.com/questions/21394537/tess4j-unsatisfied-link-error-on-mac-os-x) for the solution.
7.  To use the gym-minigrid environment, install the Python packages.
    1. `pip install gym==0.21.0 gym-minigrid==1.0.3`
    2. there was a change to the render API in gym 0.25.0, so earlier versions are currently needed for compatibility

Setting up the git repository
-----------------------------

1.  git clone https://github.com/wbridewell/arcadia.git


Setting up OpenCV / Updating OpenCV
-----------------------------------

The repository includes a Leiningen project tree, a project file, and source code for ARCADIA. However, you will be required to build install certain development libraries and add files to your local repository. Although there are efforts to incorporate [CMU Sphinx](http://cmusphinx.sourceforge.net) and [ZeroMQ](http://zeromq.org), the principle requirement at this time is [OpenCV](http://docs.opencv.org).

Source code for OpenCV is readily available, but on macOS, the Homebrew package is the easiest to prepare.

The following should be done in your arcadia directory. (**Important**: These instructions are for version 4.2.0\_3. Change this number to the version you have installed when following them.)

1.  `brew update`
    
2.  Unless you have already done so, install Apache Ant
    
    brew install ant
    
3.  `brew uninstall --force opencv`
    
4.  `brew edit opencv`
    
    1.  Change line
        
    
    `-DBUILD_opencv_java=OFF`
    
              to
    
    `-DBUILD_opencv_java=ON`
    
5.  `brew install --build-from-source opencv`
    
6.  `cp /usr/local/Cellar/opencv/4.2.0_3/share/java/opencv4/opencv-420.jar ./opencv.jar`
    
7.  `cp /usr/local/Cellar/opencv/4.2.0_3/share/java/opencv4/libopencv_java420.dylib .`
    
8.  Create the native code JAR for OpenCV
    
    1.  For macOS, if you don't have a previous version
        
        `mkdir -p native/macosx/x86\_64`
        
        `mkdir -p target/native/macosx/x86\_64`
    2.  if you do
        
        `mv libopencv_java420.dylib native/macosx/x86_64/`
        
        `cp native/macosx/x86_64/libopencv_java420.dylib target/native/macosx/x86_64/`   
        
        (This step is supposed to happen automatically, but you may need to do it manually.)`
    3.  `jar -cMf opencv-native.jar native`
        
9.  Deploy the JAR files using Maven
    1.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv -Dversion=arcadia -Dpackaging=jar -Dfile=opencv.jar -Durl=file:repo`
        
    2.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv-native -Dversion=arcadia -Dpackaging=jar -Dfile=opencv-native.jar -Durl=file:repo`
        
10.  Ensure that your project.clj file contains a reference to the OpenCV libraries under `:dependencies`.

        1.  `[local/opencv "arcadia"]`
        
        2.  `[local/opencv-native "arcadia"]`
        
11.  remove the old versions of the libraries from your local maven cache  
    `rm -r ~/.m2/repository/local/opencv*`
12.  `lein deps`
    
13.  `lein repl`
    1. To use python libraries, run with `lein with-profile python repl`
    2. If using native Apple Silicon support, run with `lein with-profile aarch64,python repl`
    
14.  `(refresh)`
