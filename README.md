# JSRoboticsConsole
Javascript console made to ease development on the FIRST Robotics platform, paired with the JS Opmod
NOTICE: THIS IS MADE SPECIFICALLY FOR THE FIRST ROBOTICS FTC COMPETITION.

## Rationale
It can take about a minute to upload any code changes from Android Studio to the robot, and that's with the new instant run features, and assuming none of your motors disconnect when it resets. I'm putting this solution out for other impatient people like me. Here is what it looks like:

![screenshot](https://cloud.githubusercontent.com/assets/2501746/20613817/3a8472f6-b28d-11e6-93f8-7748a8757832.PNG)

## Installation
### Server (phone)
All of the files in the Server directory have comments to explain themselves. Just place them in the appropriate parts of your team's TeamCode directory.

### Client (computer)
#### Automatic method (Linux only)
Download and run `installer.bash` in the root directory of this project. The script clones the project itself, so it is not necessary to clone yourself. Simply copy + paste `installer.bash` somewhere onto your system, and run it.

#### Manual method
As for client, download RSyntaxTextArea from wherever you want, then compile & run the Java files under Client with the RSyntaxTextArea jar in the classpath (it should look something like `javac -cp RSyntaxTextArea.jar;. Client.java && java -cp RSyntaxTextArea.jar;. Client`). It'll ask you for the server hostname when you first run it, enter the IP of the phone running the JavascriptServer opmode (remember they must be on the same WIFI network AND you must have pressed play on the phone) and then it should connect.

That's all there is to it.

## Usage
### Left panel
The left panel allows computer-controlled motors. Click `New`, enter the name of the motor, and it will create a slider that allows for easy testing. If you add a lot of motors that you want to use again, click `Save` and you can save the motor config to a file (later readable with the `Load` button).
### Center panel
The center panel is the main JS code. Write whatever. Refer to the Rhino documentation for accessing Java classes from JS, but generally it goes something like `Packages.org.company.whatever.Class` if you want to access the class `Class` in the package `org.company.whatever`. `gamepad1`, `gamepad2`, and `hardwareMap` are some provided variables that can be accessed from name alone.
### Bottom panel
The bottom panel (aka text area) allows you to enter some code, and then immediately see its output. For example, writing `hardwareMap.dcMotor.get("left_back").getPower()` might yield something like `0.0`.
### Right panel
The right panel is split into two parts: the console and the action buttons. The console just displays output, it's self explanatory. The action buttons allow you to send the centerpanel Javascipt to the robot, and then initialize, and start the opmode. It also can clear all variables, etc.
#### Important
Just writing Javascript won't put it on the robot. You should write the JS in the center panel, then choose `Send`, `Init`, and `Start` in the right action buttons to get it going.

## Issues & Fixes
### Gamepad doesn't work
Try pressing `Clear vars`.
### Cannot connect
Ensure:

1. The robot controller phone connected is to the same WIFI network as the computer.
2. Someone pressed the play button on the robot controller.
3. TCP connections are not blocked over your network.
