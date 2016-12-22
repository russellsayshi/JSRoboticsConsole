import java.net.*;
import java.io.*;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.event.*;
import java.awt.*;
import java.util.function.*;
import org.fife.ui.rsyntaxtextarea.*;
import org.fife.ui.rtextarea.*;
import java.util.*;

public class Client {
    private JFrame frame;
    private JTextPane console;
    private JTextField singleLine;
    private RSyntaxTextArea rta;
    private ServerManager sm;
    private Thread smThread;
    private ButtonHandler buttonHandler;
    private JPanel motorContainer;
    private JPanel leftPane;
    private JSplitPane jsp;
    private JButton startStopBtn;
    private Map<String, MotorComponent> motors = new HashMap<>();
    private static final double EXPECTED_SERVER_VERSION = 1.3;
    private static final String MOTOR_SAVE_EXTENSION = ".mcsave";
    private static final String NORMAL_SAVE_EXTENSION = ".robojs";
    public Client() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                buttonHandler = new ButtonHandler();
                frame = new JFrame();
                initializeGUI();
                try {
                    sm = new ServerManager(JOptionPane.showInputDialog("Enter hostname of server"), 6789);
                } catch(IOException ioe) {
                    JOptionPane.showMessageDialog(null, "Unable to connect to server.");
                    ioe.printStackTrace();
                    System.exit(1);
                }
                smThread = new Thread(sm);
                smThread.start();
            }
        });
    }
    
    public static void main(String[] args) {
        try {
            //Set System L&F
            UIManager.setLookAndFeel(
                UIManager.getSystemLookAndFeelClassName());
        } 
        catch (UnsupportedLookAndFeelException|
               ClassNotFoundException|
               InstantiationException|
               IllegalAccessException e) {
           //idc
        }
        new Client();
    }
    
    private class ButtonHandler implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent ae) {
            String command = ae.getActionCommand();
            if(command.equals("run")) {
                //run a single line
                sm.sendSingleLine(singleLine.getText());
            } else if(command.equals("new_motor")) {
                String motorName = JOptionPane.showInputDialog("Motor name?");
                if(motorName != null) {
                    addMotor(motorName);
                }
            } else if(command.equals("save_motor")) {
                String file = JOptionPane.showInputDialog("File name?");
                if(file != null) {
                    saveMotors(file + MOTOR_SAVE_EXTENSION);
                }
            } else if(command.equals("load_motor")) {
                String file = JOptionPane.showInputDialog("File name?");
                if(file != null) {
                    removeAllMotors();
                    forEachInFile(file + MOTOR_SAVE_EXTENSION, (line) -> {
                        MotorComponent mc = MotorComponent.buildFromString(line, Client.this::constructMotorComponent);
                        addExistingMotor(mc);
                    });
                }
            } else if(command.equals("save_main")) {
                String file = JOptionPane.showInputDialog("File name?");
                if(file != null) {
                    writeToFile(file + NORMAL_SAVE_EXTENSION, rta.getText());
                }
            } else if(command.equals("load_main")) {
                String file = JOptionPane.showInputDialog("File name?");
                if(file != null) {
                    rta.setText("");
                    forEachInFile(file + NORMAL_SAVE_EXTENSION, (line) -> {
                        rta.append(line + "\n");
                    });
                }
            } else if(command.equals("init")) {
                setStartButton();
                sm.sendInit();
            } else if(command.equals("start")) {
                setStopButton();
                sm.sendStart();
            } else if(command.equals("stop")) {
                setStartButton();
                sm.sendStop();
            } else if(command.equals("clear_cx")) {
                sm.sendClearCX();
            } else if(command.equals("send")) {
                //send entire file
                sm.sendFile(rta.getText());
            } else {
                System.err.println("Unrecognized button action command: " + command);
            }
        }
    }
    
    public void saveMotors(String filename) {
        try {
            PrintWriter out = new PrintWriter(filename, "UTF-8");
            Collection<MotorComponent> values = motors.values();
            for(MotorComponent mc : values) {
                out.println(mc.getStringRepr());
            }
            out.close();
        } catch(IOException ioe) {
            JOptionPane.showMessageDialog(null, ioe.getMessage());
        }
    }
    
    public MotorComponent constructMotorComponent(String name) {
        return new MotorComponent(name, (power) -> {
            //send motor data
            sm.sendMotorData(name, power);
        }, () -> {
            //on delete
            removeMotor(name);
        });
    }
    
    private void addExistingMotor(MotorComponent mc) {
        motorContainer.add(mc);
        if(motors.containsKey(mc.getName())) {
            removeMotor(mc.getName());
        }
        motors.put(mc.getName(), mc);
        updateMotorPanel();
    }
    
    public void addMotor(String name) {
        MotorComponent mc = constructMotorComponent(name);
        addExistingMotor(mc);
    }
    
    public void removeAllMotors() {
        Collection<MotorComponent> values = motors.values();
        for(MotorComponent mc : values) {
            removeMotor(mc.getName());
        }
    }
    
    public void removeMotor(String name) {
        MotorComponent mc = motors.remove(name);
        motorContainer.remove(mc);
        updateMotorPanel();
    }
    
    private void updateMotorPanel() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                leftPane.invalidate();
                leftPane.revalidate();
                leftPane.repaint();
            }
        });
    }

    private enum DataOutputType {
        FULL_SCRIPT,
        ONE_LINE,
        MOTOR_POWER,
        CLEAR_CONTEXT,
        START,
        STOP,
        INIT,
        NONE
    }
    private enum DataType {
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

    private class ServerManager implements Runnable {
        private Socket socket;
        private DataInputStream input;
        private DataOutputStream output;
        private Object readLock = new Object();
        private Object writeLock = new Object();
        
        @Override
        public void run() {
            try {
                synchronized(readLock) {
                    double version = input.readDouble();
                    if(version == EXPECTED_SERVER_VERSION) {
                        logSuccess("Handshaked with expected server version: " + version);
                    } else {
                        logError("Not correct server version. Server has: " + version + ", expected " + EXPECTED_SERVER_VERSION  + "\nSome features may not work.");
                    }
                }
                while(!Thread.currentThread().isInterrupted()) {
                    synchronized(readLock) {
                        DataType type = intToDataType(input.readInt());
                        if(type == DataType.NONE) {
                            logError("Invalid data. Ending connection.");
                            break;
                        }
                        String message = input.readUTF();
                        switch(type) {
                            case LINE_OUTPUT:
                                log("Output: " + message);
                                break;
                            case ERROR:
                                String stackTrace = input.readUTF();
                                int lineNo = input.readInt();
                                int columnNo = input.readInt();
                                logError("Error running javascript at line " + lineNo + " column " + columnNo + "!");
                                logError(message);
                                logError(stackTrace);
                                break;
                            case SINGLE_ERROR:
                                logError(message);
                                break;
                            case PRINT:
                                logPrint(message);
                                break;
                        }
                    }
                }
            } catch(IOException ioe) {
                logError(ioe.getMessage());
            } finally {
                logInfo("Finishing up.");
                close();
                logInfo("Closing ServerManager thread.");
            }
        }
        
        public void sendSingleLine(String line) {
            synchronized(writeLock) {
                try {
                    output.writeInt(DataOutputType.ONE_LINE.ordinal());
                    output.writeUTF(line);
                } catch(IOException ioe) {
                    logError(ioe.getMessage());
                }
            }
        }
        
        public void sendFile(String file) {
            synchronized(writeLock) {
                try {
                    output.writeInt(DataOutputType.FULL_SCRIPT.ordinal());
                    output.writeUTF(file);
                } catch(IOException ioe) {
                    logError(ioe.getMessage());
                }
            }
        }
        
        private void writeOnlyDataType(DataOutputType type) {
            synchronized(writeLock) {
                try {
                    output.writeInt(type.ordinal());
                } catch(IOException ioe) {
                    logError(ioe.getMessage());
                }
            }
        }
        
        public void sendStart() {
            writeOnlyDataType(DataOutputType.START);
        }
        
        public void sendStop() {
            writeOnlyDataType(DataOutputType.STOP);
        }
        
        public void sendInit() {
            writeOnlyDataType(DataOutputType.INIT);
        }
        
        public void sendClearCX() {
            writeOnlyDataType(DataOutputType.CLEAR_CONTEXT);
        }
        
        public void sendMotorData(String motor, double power) {
            synchronized(writeLock) {
                try {
                    output.writeInt(DataOutputType.MOTOR_POWER.ordinal());
                    output.writeUTF(motor);
                    output.writeDouble(power);
                } catch(IOException ioe) {
                    logError(ioe.getMessage());
                }
            }
        }
        
        public void close() {
            if(socket == null) {
                logError("Error: client socket is null.");
            } else {
                try {
                    socket.close();
                    log("Closed.");
                } catch(IOException ioe) {
                    logError(ioe.getMessage());
                }
                socket = null;
            }
        }
        
        public ServerManager(String hostname, int port) throws IOException {
            socket = new Socket(hostname, port);
            logInfo("Socket established.");
            output = new DataOutputStream(socket.getOutputStream());
            input = new DataInputStream(socket.getInputStream());
        }
    }
    
    private void initializeGUI() {
        frame.setSize(1200, 700);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        
        initializeBottomPanel();
        initializeLeftPanel();
        initializeCenterPanel();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                jsp.setDividerLocation(0.7);
            }
        });
        
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setTitle("JS Robotics Console");
        frame.setVisible(true);
    }
    
    private void logInfo(String msg) {
        coloredLog(msg, Color.BLACK);
    }
    
    private void logError(String msg) {
        coloredLog(msg, Color.RED);
    }
    
    private void logImportant(String msg) {
        coloredLog(msg, Color.BLUE);
    }
        
    private void logSuccess(String msg) {
        coloredLog(msg, Color.GRAY);
    }
    
    private void log(String msg) {
        coloredLog(msg, Color.BLACK);
    }
    
    private static final Color printColor = new Color(69, 88, 119);
    private void logPrint(String msg) {
        coloredLog(msg, printColor);
    }
    
    private void coloredLog(String msg, Color c)
    {
        msg = msg + "\n";
        StyleContext sc = StyleContext.getDefaultStyleContext();
        AttributeSet aset = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, c);

        aset = sc.addAttribute(aset, StyleConstants.FontFamily, "Lucida Console");
        aset = sc.addAttribute(aset, StyleConstants.Alignment, StyleConstants.ALIGN_JUSTIFIED);

        int len = console.getDocument().getLength();
        console.setCaretPosition(len);
        console.setCharacterAttributes(aset, false);
        console.replaceSelection(msg);
        
        //repeat to scroll to bottom
        len = console.getDocument().getLength();
        console.setCaretPosition(len);
    }
    
    private void setStartButton() {
        startStopBtn.setText("Start");
        startStopBtn.setActionCommand("start");
    }
    private void setStopButton() {
        startStopBtn.setText("Stop");
        startStopBtn.setActionCommand("stop");
    }

    private void initializeCenterPanel() {
        rta = new RSyntaxTextArea(20, 60);
        rta.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
        rta.setCodeFoldingEnabled(true);
        rta.setText(DEFAULT_CODE);
        RTextScrollPane rtasp = new RTextScrollPane(rta);
        
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(2, 3));
        
        //create buttons
        JButton sendBtn = new JButton("Send");
        JButton initBtn = new JButton("Init");
        startStopBtn = new JButton("...");
        JButton clearBtn = new JButton("Clear vars");
        JButton loadBtn = new JButton("Load");
        JButton saveBtn = new JButton("Save");
        
        //set commands
        sendBtn.setActionCommand("send");
        initBtn.setActionCommand("init");
        setStartButton();
        clearBtn.setActionCommand("clear_cx");
        loadBtn.setActionCommand("load_main");
        saveBtn.setActionCommand("save_main");
        
        //add listeners
        sendBtn.addActionListener(buttonHandler);
        initBtn.addActionListener(buttonHandler);
        startStopBtn.addActionListener(buttonHandler);
        clearBtn.addActionListener(buttonHandler);
        loadBtn.addActionListener(buttonHandler);
        saveBtn.addActionListener(buttonHandler);
        
        //add buttons
        buttonPanel.add(sendBtn);
        buttonPanel.add(initBtn);
        buttonPanel.add(startStopBtn);
        buttonPanel.add(clearBtn);
        buttonPanel.add(loadBtn);
        buttonPanel.add(saveBtn);
        
        console = new JTextPane();
        //console.setEditable(false);
        JScrollPane consoleScroll = new JScrollPane(console);
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buttonPanel, consoleScroll);
        
        //finally create JSP and add to center
        jsp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rtasp, rightSplit);
        frame.add(jsp, BorderLayout.CENTER);
    }

    private void initializeLeftPanel() {
        JPanel left = new JPanel();
        left.setLayout(new BorderLayout());
        JPanel top = new JPanel();
        top.setLayout(new GridLayout(1, 3));
        JButton load = new JButton("Load");
        load.setActionCommand("load_motor");
        load.addActionListener(buttonHandler);
        JButton save = new JButton("Save");
        save.setActionCommand("save_motor");
        save.addActionListener(buttonHandler);
        JButton add = new JButton("New");
        add.setActionCommand("new_motor");
        add.addActionListener(buttonHandler);
        top.add(load);
        top.add(save);
        top.add(add);
        left.add(top, BorderLayout.NORTH);
        
        motorContainer = new JPanel();
        JPanel motorContainerContainer = new JPanel();
        motorContainer.setLayout(new BoxLayout(motorContainer, BoxLayout.Y_AXIS));
        motorContainerContainer.setLayout(new FlowLayout());
        motorContainerContainer.add(motorContainer);
        leftPane = left;
        
        left.add(new JScrollPane(motorContainerContainer), BorderLayout.CENTER);
        frame.add(left, BorderLayout.WEST);
    }
    
    private void initializeBottomPanel() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BorderLayout());
        JButton run = new JButton("Run");
        run.setActionCommand("run");
        run.addActionListener(buttonHandler);
        bottom.add(run, BorderLayout.EAST);
        bottom.add((singleLine = new JTextField()), BorderLayout.CENTER);
        singleLine.setActionCommand("run");
        singleLine.addActionListener(buttonHandler);
        singleLine.setFont(new Font("Lucida Console", Font.PLAIN, 14));
        frame.add(bottom, BorderLayout.SOUTH);
    }
    
    private static void forEachInFile(String filename, Consumer<String> action) {
        try {
            Scanner scan = new Scanner(new File(filename));
            while(scan.hasNextLine()) {
                action.accept(scan.nextLine());
            }
            scan.close();
        } catch(FileNotFoundException fnfe) {
            JOptionPane.showMessageDialog(null, "File not found: " + filename);
        }
    }
    
    private static void writeToFile(String filename, String content) {
        try {
            PrintWriter writer = new PrintWriter(filename, "UTF-8");
            writer.println(content);
            writer.close();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, e.getMessage());
        }
    }
    
    private static final String DEFAULT_CODE = "" + 
        "var left_back, left_front, right_back, right_front;\n\n" +
        "function init() {\n" +
        "\tleft_back = hardwareMap.dcMotor.get(\"left_back\");\n" +
        "\tleft_front = hardwareMap.dcMotor.get(\"left_front\");\n" +
        "\tright_back = hardwareMap.dcMotor.get(\"right_back\");\n" +
        "\tright_front = hardwareMap.dcMotor.get(\"right_front\");\n" +
        "}\n\n" +
        "function start() {\n" +
        "\t//TODO add something here\n" +
        "}\n" +
        "function loop() {\n" +
        "\t//TODO add something here\n" +
        "}\n\n" +
        "function stop() {\n" +
        "\t//TODO add something here\n" +
        "}\n";

}