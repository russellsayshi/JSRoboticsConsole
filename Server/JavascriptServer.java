package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Created by Russell Coleman, November 2016.
 * This OpMode should be placed according to your team's rules.
 * Make sure to download Rhino and add it as a library & dependency under gradle.
 */

@TeleOp(name = "js-server", group = "test")
public class JavascriptServer extends OpMode {
    private enum DataType {
        FULL_SCRIPT,
        ONE_LINE,
        MOTOR_POWER,
        CLEAR_CONTEXT,
        START,
        STOP,
        INIT,
        NONE
    }
    private enum DataOutputType {
        LINE_OUTPUT,
        ERROR,
        SINGLE_ERROR,
        PRINT,
        NONE
    }
    private DataType[] dataTypeValues = DataType.values();
    private DataType intToDataType(int val) {
        if(val < 0) {
            return DataType.NONE;
        } else if(val > dataTypeValues.length) {
            return DataType.NONE;
        } else {
            return dataTypeValues[val];
        }
    }

    private void log(String s) {
        System.out.println(s);
    }

    private class ThreadSafeData<E> {
        private final Object lock = new Object();
        private volatile E value;

        public void setValue(E value) {
            synchronized(lock) {
                this.value = value;
            }
        }

        public E getValue() {
            synchronized(lock) {
                return value;
            }
        }
    }

    private static volatile Thread serverThread;
    private static volatile Thread scriptThread;
    private static volatile ServerThread serverThreadObj;
    private static volatile ScriptThread scriptThreadObj;
    private static final double VERSION_NUMBER = 1.3;
    private static final Object lock = new Object();
    private ThreadSafeData<Boolean> isLooping = new ThreadSafeData<>();

    private void deleteOtherThread() {
        synchronized(lock) {
            if(serverThread != null) {
                if (serverThread.isAlive()) {
                    serverThread.interrupt();
                    serverThreadObj.closeSocket();
                }
                serverThread = null;
            }
            if(scriptThread != null) {
                if(scriptThread.isAlive()) {
                    scriptThread.interrupt();
                }
                scriptThread = null;
            }
        }
    }
    private void createOtherThread() {
        synchronized(lock) {
            deleteOtherThread();
            scriptThreadObj = new ScriptThread();
            scriptThread = new Thread(scriptThreadObj);
            scriptThread.start();
            serverThreadObj = new ServerThread(scriptThreadObj);
            serverThread = new Thread(serverThreadObj);
            serverThread.start();
        }
    }

    private class ScriptThread implements Runnable {
        private Context cx;
        private ScriptableObject scope;
        private final Object scriptLock = new Object();
        private final Object queueLock = new Object();
        private ThreadSafeData<Queue<FutureTask<Object>>> messages = new ThreadSafeData<>();

        @Override
        public void run() {
            messages.setValue(new ConcurrentLinkedQueue<FutureTask<Object>>());
            createContext();

            while(!Thread.currentThread().isInterrupted()) {
                synchronized(queueLock) {
                    while(messages.getValue().size() == 0 && !Thread.currentThread().isInterrupted()) {
                        try {
                            queueLock.wait();
                        } catch(InterruptedException ie) {
                            return;
                        }
                    }
                    if(Thread.currentThread().isInterrupted()) return;

                    //we have an item in the queue
                    FutureTask<Object> tm;
                    synchronized(queueLock) {
                        tm = messages.getValue().remove();
                    }

                    synchronized(scriptLock) {
                        tm.run();
                    }
                }
            }

            synchronized(scriptLock) {
                Context.exit();
            }
        }

        public void addTask(FutureTask<Object> task) {
            synchronized(queueLock) {
                messages.getValue().add(task);
                queueLock.notify();
            }
        }

        public void performTask(Callable<Object> callable) {
            FutureTask<Object> ft = new FutureTask<Object>(callable);
            addTask(ft);
        }

        public String callFunction(String name) {
            synchronized(scriptLock) {
                if(scope == null) {
                    return "Null scope.";
                }
                Object obj = null;
                try {
                    obj = scope.get(name, scope);
                    Function fct = (Function)obj;
                    if(fct == null) {
                        return "Function not found: " + name;
                    }
                    fct.call(cx, scope, scope, new Object[] {});
                } catch(ClassCastException cce) {
                    return "Function " + name + " is of type " + obj;
                }
                return null;
            }
        }

        public Object evaluateString(String str, String name) {
            if(cx == null) {
                return null;
            }
            return cx.evaluateString(scope, str, name, 1, null);
        }

        //do not run me if a context already exists
        private void createContext() {
            synchronized(scriptLock) {
                cx = Context.enter();
                cx.setOptimizationLevel(-1); //make compatible with Android

                scope = cx.initStandardObjects();
                ScriptableObject.putProperty(scope, "gamepad1", Context.javaToJS(gamepad1, scope));
                ScriptableObject.putProperty(scope, "gamepad2", Context.javaToJS(gamepad2, scope));
                ScriptableObject.putProperty(scope, "hardwareMap", Context.javaToJS(hardwareMap, scope));
                ScriptableObject.putProperty(scope, "server", Context.javaToJS(new ClientCodeUtilities(), scope));
            }
        }

        public void clearContext() {
            Context.exit();
            createContext();
        }
    }
    
    public class ClientCodeUtilities {
        public void print(String msg) {
            try {
                JavascriptServer.this.serverThreadObj.writeStringToClient(DataOutputType.PRINT, msg);
            } catch(IOException ioe) {
                log(ioe.getMessage());
            }
        }
    }

    private class ServerThread implements Runnable {
        private ServerSocket socket;
        private Socket clientSockets;
        private DataInputStream inFromClient; //no other threads should ever touch this
        private DataOutputStream outToClient; //ditto above
        private final Object socketLock = new Object();
        private final Object writeLock = new Object();
        private ScriptThread script;

        public ServerThread(ScriptThread script) {
            this.script = script;
        }

        @Override
        public void run() {
            while(!Thread.currentThread().isInterrupted()) {
                try {
                    createServer();
                    synchronized(writeLock) {
                        outToClient.writeDouble(VERSION_NUMBER); //handshake
                    }
                    while(!Thread.currentThread().isInterrupted()) {
                        //server loop
                        DataType type = intToDataType(inFromClient.readInt());
                        if(type == DataType.NONE) {
                            log("Invalid data. Ending connection.");
                            break; //invalid data coming through
                        }
                        log("Type in: " + type);
                        String bufferStr = null;
                        switch(type) {
                            case MOTOR_POWER:
                                bufferStr = inFromClient.readUTF();
                                double power = inFromClient.readDouble();
                                DcMotor motor = hardwareMap.dcMotor.get(bufferStr);
                                if(motor == null) {
                                    log("Motor not found: " + bufferStr);
                                    writeErrorString("Motor not found: " + bufferStr);
                                } else {
                                    motor.setPower(power);
                                }
                                break;
                            case FULL_SCRIPT:
                                bufferStr = inFromClient.readUTF();
                                final String fs1 = bufferStr;
                                script.performTask(new Callable<Object>() {
                                    @Override
                                    public Object call() {
                                        try {
                                            script.evaluateString(fs1, "<cmd>");
                                        } catch(RhinoException re) {
                                            try {
                                                writeErrorToClient(re);
                                            } catch(IOException ioe) {
                                                JavascriptServer.this.serverThread.interrupt();
                                            }
                                            log(re.getMessage());
                                        }
                                        return null;
                                    }
                                });
                                break;
                            case ONE_LINE:
                                bufferStr = inFromClient.readUTF();
                                final String fs2 = bufferStr;
                                script.performTask(new Callable<Object>() {
                                    @Override
                                    public Object call() {
                                        try {
                                            String strOut = String.valueOf(
                                                    script.evaluateString(fs2, "<script>"));
                                            try {
                                                writeStringToClient(DataOutputType.LINE_OUTPUT, strOut);
                                            } catch(IOException ioe) {
                                                JavascriptServer.this.serverThread.interrupt();
                                            }
                                        } catch(RhinoException re) {
                                            try {
                                                writeErrorToClient(re);
                                            } catch(IOException ioe) {
                                                JavascriptServer.this.serverThread.interrupt();
                                            }
                                            log(re.getMessage());
                                        }
                                        return null;
                                    }
                                });
                                break;
                            case CLEAR_CONTEXT:
                                script.performTask(new Callable<Object>() {
                                    @Override
                                    public Object call() {
                                        script.clearContext();
                                        return null;
                                    }
                                });
                                break;
                            case START:
                                if(!callFunction("start")) {
                                    writeErrorString("Unable to call \"start\".");
                                }
                                isLooping.setValue(true);
                                break;
                            case STOP:
                                isLooping.setValue(false);
                                if(!callFunction("stop")) {
                                    writeErrorString("Unable to call \"stop\".");
                                }
                                break;
                            case INIT:
                                isLooping.setValue(false);
                                if(!callFunction("init")) {
                                    writeErrorString("Unable to call \"init\".");
                                    //System.out.println(queuedErrors);
                                }
                                break;
                        }
                    }
                } catch(IOException ioe) {
                    //do nothing
                } finally {
                    closeSocket();
                }
            }
        }
        
        public void writeErrorString(String error) {
            try {
                writeStringToClient(DataOutputType.SINGLE_ERROR, error);
            } catch(IOException ioe) {
                log("Couldn't write error: " + ioe.getMessage());
            }
        }

        public void writeErrorToClient(RhinoException error) throws IOException {
            synchronized(writeLock) {
                outToClient.writeInt(DataOutputType.ERROR.ordinal());
                outToClient.writeUTF(error.getMessage());
                outToClient.writeUTF(error.getScriptStackTrace());
                outToClient.writeInt(error.lineNumber());
                outToClient.writeInt(error.columnNumber());
            }
        }

        public void writeStringToClient(DataOutputType type, String str) throws IOException {
            synchronized(writeLock) {
                outToClient.writeInt(type.ordinal());
                outToClient.writeUTF(str);
            }
        }

        public void closeSocket() {
            synchronized(socketLock) {
                if(socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch(IOException ioe2) {
                        //we're done
                    }
                }
            }
        }

        public boolean callFunction(final String name) {
            FutureTask<Object> ft = new FutureTask<Object>(
                    new Callable<Object>() {
                        @Override
                        public Object call() {
                            return JavascriptServer.this.scriptThreadObj.callFunction(name);
                        }
                    }
            );
            script.addTask(ft);
            try {
                String err = (String)ft.get();
                if(err == null) {
                    //success
                    return true;
                } else {
                    writeErrorString(err);
                    return false;
                }
            } catch(InterruptedException|ExecutionException ie) {
                writeErrorString(ie.getMessage());
                return false;
            }
        }

        private void createServer() throws IOException {
            synchronized(socketLock) {
                socket = new ServerSocket(6789);
                log("Created server. Waiting for connection...");
                Socket client = socket.accept();
                log("Found client.");
                clientSockets = client;
                inFromClient = new DataInputStream(client.getInputStream());
                outToClient = new DataOutputStream(client.getOutputStream());
            }
        }
    }

    @Override
    public void init() {
        isLooping.setValue(false);
    }

    @Override
    public void start() {
        createOtherThread();
    }

    @Override
    public void stop() {
        isLooping.setValue(false);
        deleteOtherThread();
    }

    private void smallDelay() {
        try {
            Thread.sleep(100);
        } catch(InterruptedException ie) {
            log("Interrupted.");
        }
    }

    @Override
    public void loop() {
        if(isLooping.getValue() && serverThreadObj != null) {
            if(!serverThreadObj.callFunction("loop")) {
                System.out.println("Unable to loop!");
                smallDelay();
            }
        } else {
            smallDelay();
        }
    }
}
