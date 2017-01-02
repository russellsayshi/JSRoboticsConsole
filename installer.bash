<<<<<<< HEAD
#==========================================================
#    YOU CAN RUN THIS FILE WITHOUT CLONING. JUST
#    COPY + PASTE INTO A TEXT EDITOR AND RUN ON
#    A UNIX-LIKE SYSTEM.
#==========================================================

bold () {
    echo -ne "\e[1m"
}

normal () {
    echo -ne "\e[0m"
}

if [ -f "JSClient/runme.bash" ]; then
    echo "=================================================="
    echo "Looks like you've already successfully installed"
    echo "the system. Would you like to perform a reinstall?"
    bold
    echo "    WARNING: all your JS will be deleted."
    normal
    echo "If you are trying to run the system, just run"
    echo "    JSClient/runme.bash"
    echo "=================================================="
    echo "enter Y to reinstall, anything else to cancel."
    read response_one
    if [[ "$response_one" == "Y" ]]; then
        echo "Okay. Removing..."
        rm -rf JSClient
    else
        echo "Cancelling."
        exit 10
    fi
fi

bold
echo "Searching for Java..."
normal

if type -p java; then
    echo Found java executable in PATH
    _java=java
elif [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]];  then
    echo Found java executable in JAVA_HOME     
    _java="$JAVA_HOME/bin/java"
    PATH="$JAVA_HOME/bin/:$PATH"
    exit 1
else
    echo "No java found."
    exit 1
fi

vercomp () {
    if [[ $1 == $2 ]]
    then
        return 0
    fi
    local IFS=.
    local i ver1=($1) ver2=($2)
    # fill empty fields in ver1 with zeros
    for ((i=${#ver1[@]}; i<${#ver2[@]}; i++))
    do
        ver1[i]=0
    done
    for ((i=0; i<${#ver1[@]}; i++))
    do
        if [[ -z ${ver2[i]} ]]
        then
            # fill empty fields in ver2 with zeros
            ver2[i]=0
        fi
        if ((10#${ver1[i]} > 10#${ver2[i]}))
        then
            return 1
        fi
        if ((10#${ver1[i]} < 10#${ver2[i]}))
        then
            return 2
        fi
    done
    return 0
}
OLDPATH=""
checkjavaversion () {
if [[ "$_java" ]]; then
    version=$("$_java" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -f1 -d"_")
    echo Your version "$version"
    EXPECTED="1.8.0"
    vercomp "$version" "$EXPECTED"
    RESULT="$?"
    which javac
    JAVACRESULT="$?"
    #echo "returned $RESULT"
    if [[ $RESULT -ne 2 ]] && [[ $JAVACRESULT -eq 0 ]]; then
        echo "JDK found."
        echo "Your version ($version) is >= $EXPECTED. Continuing..."
    else
        if [[ $RESULT -eq 2 ]]; then
            echo "======================================================"
            bold
            echo "Your version ($version) is less than $EXPECTED."
            normal
            echo "Please update Java."
        fi
        if [[ $JAVACRESULT -ne 0 ]]; then
            echo "======================================================"
            bold
            echo "javac not found."
            normal
        fi
        echo "======================================================"
        echo "If you would like to use a different JDK path,"
        echo -n "please enter the absolute path to the "
        bold
        echo -n "JDK's"
        normal
        echo " bin folder"
        echo "now."
        newpath=""
        read newpath
        if [[ "$newpath" ]]; then
            WHICH="$(which which)"
            OLDPATH="$PATH"
            PATH="$newpath"
            $WHICH java
            if [[ $? -ne 0 ]]; then
                echo "Java not found at that location."
                exit 4
            fi
            $WHICH javac
            if [[ $? -ne 0 ]]; then
                echo "Javac not found at that location."
                exit 5
            fi
            _java="$(which java)"
            PATH="$PATH:$OLDPATH"
            echo "Located java at $_java."
            checkjavaversion
        else
            echo "Goodbye."
            exit 2
        fi
    fi
fi
}
checkjavaversion

echo "Ensuring utilities exist..."
which unzip
if [[ $? -ne 0 ]]; then
    #unzip doesn't exist
    bold
    echo "Please install unzip."
    normal
    which pacman
    if [[ $? -eq 0 ]]; then
        sudo pacman -S unzip
    fi
    which "apt-get"
    if [[ $? -eq 0 ]]; then
        sudo apt-get install unzip
    fi

    which unzip
    if [[ $? -ne 0 ]]; then
        echo Unzip not found.
        exit 3
    fi
fi
which wget
if [[ $? -ne 0 ]]; then
    #wget doesn't exist
    bold
    echo "Please install wget."
    normal
    which pacman
    if [[ $? -eq 0 ]]; then
        sudo pacman -S wget
    fi
    which "apt-get"
    if [[ $? -eq 0 ]]; then
        sudo apt-get install wget
    fi

    which wget
    if [[ $? -ne 0 ]]; then
        echo Wget not found.
        exit 3
    fi
fi
which git
if [[ $? -ne 0 ]]; then
    #git doesn't exist
    bold
    echo "Please install git."
    normal
    which pacman
    if [[ $? -eq 0 ]]; then
        sudo pacman -S git
    fi
    which "apt-get"
    if [[ $? -eq 0 ]]; then
        sudo apt-get install git
    fi

    which git
    if [[ $? -ne 0 ]]; then
        echo Git not found.
        exit 3
    fi
fi

#set up space
mkdir JSClient
cd JSClient
if [ -f ".progress" ]; then
    echo "======================================================"
    echo "It seems like you've tried to install this before in"
    echo "this location. Enter Y to delete and start over, or"
    echo "anything else to cancel."
    read response
    if [[ "$response" == "Y" ]]; then
        echo "Deleting..."
        rm -rf *
    else
        echo "Goodbye."
        exit 9
    fi
fi
touch .progress
mkdir rsta
cd rsta

echo Downloading rsyntaxtextarea...
wget "https://sourceforge.net/projects/rsyntaxtextarea/files/latest/download?source=typ_redirect" -O rsta.zip
unzip rsta.zip
chmod +x gradlew
./gradlew assembleDist
if [[ $? -ne 0 ]]; then
    echo "Failed to build."
    exit 6
fi
mv build/libs/*.jar rsta.jar
cd ..
mv rsta/rsta.jar rsta.jar
rm -rf rsta

git clone "https://github.com/russellsayshi/JSRoboticsConsole.git"
if [[ $? -ne 0 ]]; then
    echo "Failed to clone."
    exit 7
fi
cd JSRoboticsConsole
cd Client
mv ../../rsta.jar .
"$_java"c -cp rsta.jar:. Client.java
bold
echo "Success"
normal
echo '#!/bin/bash' > ../../runme.bash
echo 'cd "$(dirname "$0")"' >> ../../runme.bash
echo "cd JSRoboticsConsole/Client; $_java -cp rsta.jar:. Client" >> "../../runme.bash"
chmod +x ../../runme.bash
echo "==========================================================="
echo "                         IMPORTANT                         "
echo "==========================================================="
echo "Created file \"runme.bash\" in location JSClient/runme.bash"
echo "that allows re-running this file. Do not run this install"
echo "script again."
echo "==========================================================="
"$_java" -cp rsta.jar:. Client
=======
#!/bin/bash
#==========================================================
#    YOU CAN RUN THIS FILE WITHOUT CLONING. JUST
#    COPY + PASTE INTO A TEXT EDITOR AND RUN ON
#    A UNIX-LIKE SYSTEM.
#==========================================================

bold () {
    echo -ne "\e[1m"
}

normal () {
    echo -ne "\e[0m"
}

if [ -f "JSClient/runme.bash" ]; then
    echo "=================================================="
    echo "Looks like you've already successfully installed"
    echo "the system. Would you like to perform a reinstall?"
    bold
    echo "    WARNING: all your JS will be deleted."
    normal
    echo "If you are trying to run the system, just run"
    echo "    JSClient/runme.bash"
    echo "=================================================="
    echo "enter Y to reinstall, anything else to cancel."
    read response_one
    if [[ "$response_one" == "Y" ]]; then
        echo "Okay. Removing..."
        rm -rf JSClient
    else
        echo "Cancelling."
        exit 10
    fi
fi

bold
echo "Searching for Java..."
normal

if type -p java; then
    echo Found java executable in PATH
    _java=java
elif [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]];  then
    echo Found java executable in JAVA_HOME     
    _java="$JAVA_HOME/bin/java"
    PATH="$JAVA_HOME/bin/:$PATH"
    exit 1
else
    echo "No java found."
    exit 1
fi

vercomp () {
    if [[ $1 == $2 ]]
    then
        return 0
    fi
    local IFS=.
    local i ver1=($1) ver2=($2)
    # fill empty fields in ver1 with zeros
    for ((i=${#ver1[@]}; i<${#ver2[@]}; i++))
    do
        ver1[i]=0
    done
    for ((i=0; i<${#ver1[@]}; i++))
    do
        if [[ -z ${ver2[i]} ]]
        then
            # fill empty fields in ver2 with zeros
            ver2[i]=0
        fi
        if ((10#${ver1[i]} > 10#${ver2[i]}))
        then
            return 1
        fi
        if ((10#${ver1[i]} < 10#${ver2[i]}))
        then
            return 2
        fi
    done
    return 0
}
OLDPATH=""
checkjavaversion () {
if [[ "$_java" ]]; then
    version=$("$_java" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -f1 -d"_")
    echo Your version "$version"
    EXPECTED="1.8.0"
    vercomp "$version" "$EXPECTED"
    RESULT="$?"
    which javac
    JAVACRESULT="$?"
    #echo "returned $RESULT"
    if [[ $RESULT -ne 2 ]] && [[ $JAVACRESULT -eq 0 ]]; then
        echo "JDK found."
        echo "Your version ($version) is >= $EXPECTED. Continuing..."
    else
        if [[ $RESULT -eq 2 ]]; then
            echo "======================================================"
            bold
            echo "Your version ($version) is less than $EXPECTED."
            normal
            echo "Please update Java."
        fi
        if [[ $JAVACRESULT -ne 0 ]]; then
            echo "======================================================"
            bold
            echo "javac not found."
            normal
        fi
        echo "======================================================"
        echo "If you would like to use a different JDK path,"
        echo -n "please enter the absolute path to the "
        bold
        echo -n "JDK's"
        normal
        echo " bin folder"
        echo "now."
        newpath=""
        read newpath
        if [[ "$newpath" ]]; then
            WHICH="$(which which)"
            OLDPATH="$PATH"
            PATH="$newpath"
            $WHICH java
            if [[ $? -ne 0 ]]; then
                echo "Java not found at that location."
                exit 4
            fi
            $WHICH javac
            if [[ $? -ne 0 ]]; then
                echo "Javac not found at that location."
                exit 5
            fi
            _java="$(which java)"
            PATH="$PATH:$OLDPATH"
            echo "Located java at $_java."
            checkjavaversion
        else
            echo "Goodbye."
            exit 2
        fi
    fi
fi
}
checkjavaversion

echo "Ensuring utilities exist..."
which unzip
if [[ $? -ne 0 ]]; then
    #unzip doesn't exist
    bold
    echo "Please install unzip."
    normal
    which pacman
    if [[ $? -eq 0 ]]; then
        sudo pacman -S unzip
    fi
    which "apt-get"
    if [[ $? -eq 0 ]]; then
        sudo apt-get install unzip
    fi

    which unzip
    if [[ $? -ne 0 ]]; then
        echo Unzip not found.
        exit 3
    fi
fi
which wget
if [[ $? -ne 0 ]]; then
    #wget doesn't exist
    bold
    echo "Please install wget."
    normal
    which pacman
    if [[ $? -eq 0 ]]; then
        sudo pacman -S wget
    fi
    which "apt-get"
    if [[ $? -eq 0 ]]; then
        sudo apt-get install wget
    fi

    which wget
    if [[ $? -ne 0 ]]; then
        echo Wget not found.
        exit 3
    fi
fi
which git
if [[ $? -ne 0 ]]; then
    #git doesn't exist
    bold
    echo "Please install git."
    normal
    which pacman
    if [[ $? -eq 0 ]]; then
        sudo pacman -S git
    fi
    which "apt-get"
    if [[ $? -eq 0 ]]; then
        sudo apt-get install git
    fi

    which git
    if [[ $? -ne 0 ]]; then
        echo Git not found.
        exit 3
    fi
fi

#set up space
mkdir JSClient
cd JSClient
if [ -f ".progress" ]; then
    echo "======================================================"
    echo "It seems like you've tried to install this before in"
    echo "this location. Enter Y to delete and start over, or"
    echo "anything else to cancel."
    read response
    if [[ "$response" == "Y" ]]; then
        echo "Deleting..."
        rm -rf *
    else
        echo "Goodbye."
        exit 9
    fi
fi
touch .progress
mkdir rsta
cd rsta

echo Downloading rsyntaxtextarea...
wget "https://sourceforge.net/projects/rsyntaxtextarea/files/latest/download?source=typ_redirect" -O rsta.zip
unzip rsta.zip
chmod +x gradlew
./gradlew assembleDist
if [[ $? -ne 0 ]]; then
    echo "Failed to build."
    exit 6
fi
mv build/libs/*.jar rsta.jar
cd ..
mv rsta/rsta.jar rsta.jar
rm -rf rsta

git clone "https://github.com/russellsayshi/JSRoboticsConsole.git"
if [[ $? -ne 0 ]]; then
    echo "Failed to clone."
    exit 7
fi
cd JSRoboticsConsole
cd Client
mv ../../rsta.jar .
"$_java"c -cp rsta.jar:. Client.java
bold
echo "Success"
normal
echo '#!/bin/bash' > ../../runme.bash
echo 'cd "$(dirname "$0")"' >> ../../runme.bash
echo "cd JSRoboticsConsole/Client; $_java -cp rsta.jar:. Client" >> "../../runme.bash"
chmod +x ../../runme.bash
echo "==========================================================="
echo "                         IMPORTANT                         "
echo "==========================================================="
echo "Created file \"runme.bash\" in location JSClient/runme.bash"
echo "that allows re-running this file. Do not run this install"
echo "script again."
echo "==========================================================="
"$_java" -cp rsta.jar:. Client
>>>>>>> e5d17d2ece0373efa5ef439705185c27eeac0707
