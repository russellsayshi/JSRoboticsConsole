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
import java.util.concurrent.ConcurrentHashMap;

public class Client {
    private JFrame frame;
    private JTextPane console;
    private JTextField singleLine;
    private ServerManager sm;
    private Thread smThread;
    private ButtonHandler buttonHandler;
    private JPanel mainPanel;
    private JPanel motorContainer;
    private JPanel motorPane;
    private JSplitPane jsp;
    private JButton startStopBtn;
    private Configuration config;
    private JTabbedPane jstabs;
    private Map<String, MotorComponent> motors = new ConcurrentHashMap<>();
    private Object motorLock = new Object();
    private Map<String, File> jsfiles = new HashMap<>();
    private static final double EXPECTED_SERVER_VERSION = 1.41;
    private static final String MOTOR_SAVE_EXTENSION = ".mcsave";
    private static final String MOTOR_SAVE_FOLDER = "motors";
    private static final String NORMAL_SAVE_EXTENSION = ".robojs";
    private static final String NORMAL_SAVE_FOLDER = "js";
    private static final String PROJECTS_SAVE_FOLDER = "projects";
    private static final String PREV_HOST_FILE = "prevhost.txt";
    private static final int SOCKET_TIMEOUT = 5000;
    private static final int PORT = 6789;
    private String host;
    private int MAX_CONSOLE_LENGTH = 5000;
    
    //TODO: FIX DEFAULTS. multiple writes from appearance menu
    
    public Client() {
        config = new Configuration();
        try {
            //Set look and feel
            UIManager.setLookAndFeel(config.get("lookandfeel", UIManager.getSystemLookAndFeelClassName()));
        } 
        catch (UnsupportedLookAndFeelException|
               ClassNotFoundException|
               InstantiationException|
               IllegalAccessException e) {
           e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                buttonHandler = new ButtonHandler();
                frame = new JFrame();
                initializeGUI();
                initializeFilesystem();
                
                final JDialog dlg = new JDialog(frame, "Connecting...", true);
                JProgressBar dpb = new JProgressBar(0, 500);
                dpb.setIndeterminate(true);
                dlg.add(BorderLayout.CENTER, dpb);
                dlg.add(BorderLayout.NORTH, new JLabel("Progress..."));
                dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                dlg.pack();
                dlg.setModal(true);
                dlg.setLocationRelativeTo(frame);
                dlg.setResizable(false);
                (new Thread(() -> {
                    String hostToTry = determineHostname();
                    while(true) {
                        if(hostToTry == null) {
                            System.exit(0);
                        }
                        SwingUtilities.invokeLater(() -> dlg.setVisible(true));
                        try {
                            logInfo("Attempting to connect...");
                            sm = new ServerManager(hostToTry, PORT);
                            SwingUtilities.invokeLater(() -> dlg.setVisible(false));
                            break;
                        } catch(IOException ioe) {
                            SwingUtilities.invokeLater(() -> dlg.setVisible(false));
                            logError("Failed");
                            String[] btns = {"Yes", "No"};
                            int res = JOptionPane.showOptionDialog(frame, "Unable to connect. Try another hostname?", "Connection error", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, btns, btns[1]);
                            if(res == JOptionPane.YES_OPTION) {
                                hostToTry = promptForHostname();
                                continue;
                            }
                            ioe.printStackTrace();
                            System.exit(1);
                        }
                    }
                    try {
                        SwingUtilities.invokeLater(() -> dlg.dispose());
                        //log successful hostname
                        PrintWriter pw = new PrintWriter(new FileOutputStream(PREV_HOST_FILE, false));
                        pw.println(hostToTry);
                        pw.close();
                    } catch(IOException ioe) {
                        ioe.printStackTrace();
                    }
                    host = hostToTry;
                    smThread = new Thread(sm);
                    smThread.start();
                })).start();
            }
        });
    }
    
    public String determineHostname() {
        if("true".equals(config.get("autoreconnect", "true"))) {
            //we should auto reconnect to previous host
            try {
                Scanner scan = new Scanner(new File(PREV_HOST_FILE));
                String line = scan.nextLine();
                scan.close();
                return line;
            } catch(FileNotFoundException fnfe) {
                System.out.println("No prev host file. Will create.");
            }
        }
        return promptForHostname();
    }
    
    public String promptForHostname() {
        return JOptionPane.showInputDialog(frame, "Enter hostname of server");
    }
    
    public static void main(String[] args) {
        //apple stuff
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Stack");
    
        new Client();
    }
    
    private class Configuration {
        private static final String filename = "config";
        private static final String extension = ".txt";
        private Properties properties;
        private File file;

        public Configuration() {
            properties = new Properties();
            file = new File(filename + extension);
            
            if(!ensureExistence()) {
                //file doesn't exist
                System.out.println("No configuration file found.");
                write();
                System.out.println("Written base configuration to file " + file.getName());
            } else {
                //file exists
                try {
                    FileInputStream in = new FileInputStream(file);
                    properties.load(in);
                    System.out.println("Found " + properties.stringPropertyNames().size() + " definition(s).");
                    in.close();
                } catch(FileNotFoundException fnfe) {
                    System.err.println("Could not read from properties file.");
                    fnfe.printStackTrace();
                    System.exit(14);
                } catch(SecurityException se) {
                    System.err.println("No permissions to read from config file + " + file.getName());
                    System.exit(15);
                } catch(IOException ioe) {
                    ioe.printStackTrace();
                    System.exit(17);
                }
            }
        }
        
        private void fail() {
            System.err.println("Properties file corrupted.");
            System.err.println("Delete " + file.getName() + " to continue using.");
            System.exit(16);
        }
        
        //returns if file exists. if encounters some kind of error, attempts
        //to find another filename that works
        private boolean ensureExistence() {
            if(file.exists()) {
                if(file.isDirectory()) {
                    //someone made a directory called config.txt for some inane
                    //reason. try config0.txt, then config1.txt, etc. until we
                    //find a name that works
                    System.err.println("ERROR: Config file is directory?");
                    int num = 0;
                    while(file.isDirectory()) {
                        file = new File(filename + num + extension);
                        num++;
                        if(num > 500) {
                            //too many attempts
                            System.err.println("Unable to access config.");
                            System.exit(10);
                        }
                    }
                    System.err.println("WARNING: Using path " + file.getName() + " instead.");
                    return file.exists();
                }
                return true;
            }
            return false;
        }
        
        public String get(String property, String defaul_t) {
            String res = get(property);
            if(res == null) {
                return defaul_t;
            }
            return res;
        }
        
        private String get(String property) {
            return properties.getProperty(property);
        }
        
        public void set(String property, String value) {
            properties.setProperty(property, value);
        }
        
        public void write() {
            try {
                FileOutputStream fos = new FileOutputStream(file, false);
                properties.store(fos, "Properties for JS Client version " + EXPECTED_SERVER_VERSION);
                fos.close();
            } catch(FileNotFoundException fnfe) {
                System.err.println("Could not write to properties file.");
                fnfe.printStackTrace();
                System.exit(12);
            } catch(SecurityException se) {
                System.err.println("No permissions to write to config file + " + file.getName());
                System.exit(13);
            } catch(IOException ioe) {
                ioe.printStackTrace();
                System.exit(18);
            }
            System.out.println("Wrote config definitions to file.");
        }
        
        public void setAndWrite(String property, String value) {
            set(property, value);
            write();
        }
    }
    
    public RTextArea getCurrentRTA() {
        int idx = jstabs.getSelectedIndex();
        Component c = jstabs.getComponentAt(idx);
        RTextScrollPane rtsp = (RTextScrollPane)c;
        return rtsp.getTextArea();
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
                    addMotor(motorName, true);
                }
            } else if(command.equals("save_motor")) {
                File file = saveFileHelper(MOTOR_SAVE_FOLDER, "Motors", MOTOR_SAVE_EXTENSION);
                if(file != null) {
                    saveMotors(file);
                }
            } else if(command.equals("load_motor")) {
                File file = openFileHelper(MOTOR_SAVE_FOLDER, "Motors", MOTOR_SAVE_EXTENSION);
                if(file != null) {
                    removeAllMotors(false);
                    forEachInFile(file, (line) -> {
                        MotorComponent mc = MotorComponent.buildFromString(line, Client.this::constructMotorComponent);
                        addExistingMotor(mc, false);
                    });
                    updateMotorPanel();
                }
            } else if(command.equals("save_main")) {
                saveJS();
            } else if(command.equals("load_main")) {
                openJS();
            } else if(command.equals("init")) {
                sendInit();
            } else if(command.equals("start")) {
                sendStart();
            } else if(command.equals("stop")) {
                sendStop();
            } else if(command.equals("clear_cx")) {
                sendClearVars();
            } else if(command.equals("send")) {
                //send entire file
                sendEntireFile();
            } else {
                System.err.println("Unrecognized button action command: " + command);
            }
        }
    }
    
    public void sendInit() {
        setStartButton();
        sm.sendInit();
    }
    
    public void sendStart() {
        setStopButton();
        sm.sendStart();
    }
    
    public void sendStop() {
        setStartButton();
        sm.sendStop();
    }
    
    public void sendClearVars() {
         sm.sendClearCX();
    }
    
    public void sendEntireFile() {
        sm.sendFile(getCurrentRTA().getText());
    }
    
    public void openJS() {
        File file = openFileHelper(NORMAL_SAVE_FOLDER, "Javascript", NORMAL_SAVE_EXTENSION);
        boolean keep_going = true;
        for(int i = 0; i < jstabs.getTabCount(); i++) {
            //loop through every open file
            File key = jsfiles.get(jstabs.getToolTipTextAt(i));
            if(key == null) {
                continue;
            }
            try {
                if(key.getCanonicalPath().equals(file.getCanonicalPath())) {
                    //two files are equal
                    jstabs.setSelectedIndex(i); //focus
                    keep_going = false;
                }
            } catch(IOException ioe) {
                ioe.printStackTrace();
                keep_going = false;
                JOptionPane.showMessageDialog(frame, "Unable to open.");
            }
        }
        if(file != null && keep_going) {
            RTextArea rta = addRTATab(getTabNameFromFile(file), file, file.getAbsolutePath(), "");
            forEachInFile(file, (line) -> {
                rta.append(line + "\n");
            });
        }
    }
    
    public void saveJS() {
        File file = getCurrentFile();
        if(file == null) {
            //blank file
            saveAsJS();
        } else {
            writeToFile(file, getCurrentRTA().getText());
        }
    }
    
    private File openFileHelper(String folder, String filetypeName, String extension) {
        final JFileChooser fc = new JFileChooser(new File(folder));
        fc.setFileFilter(createFileFilter(filetypeName, extension));
        int returnVal = fc.showOpenDialog(frame);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            return fc.getSelectedFile();
        }
        return null;
    }
    
    private javax.swing.filechooser.FileFilter createFileFilter(String filetypeName, String extension) {
        return new javax.swing.filechooser.FileFilter() {
            public String getDescription() {
                return filetypeName + " (*" + extension + ")";
            }
            public boolean accept(File f) {
                if (f.isDirectory()) {
                    return true;
                } else {
                    String filename = f.getName().toLowerCase();
                    return filename.endsWith(extension);
                }
            }
        };
    }
    
    private String getTabNameFromFile(File file) {
        String name = file.getName();
        if(name.endsWith(NORMAL_SAVE_EXTENSION)) {
            return name.substring(0, name.length()-NORMAL_SAVE_EXTENSION.length());
        }
        return name;
    }
    
    private void saveAsJS() {
        File file = saveFileHelper(NORMAL_SAVE_FOLDER, "Javascript", NORMAL_SAVE_EXTENSION);
        if(file == null) {
            return;
        }
        int idx = jstabs.getSelectedIndex();
        jstabs.setTitleAt(idx, getTabNameFromFile(file));
        jsfiles.remove(jstabs.getToolTipTextAt(idx));
        String tooltip = generateTooltip(file.getAbsolutePath());
        jstabs.setToolTipTextAt(idx, tooltip);
        jsfiles.put(tooltip, file);
        writeToFile(file, getCurrentRTA().getText());
    }
    
    private File getCurrentFile() {
        int idx = jstabs.getSelectedIndex();
        return jsfiles.get(jstabs.getToolTipTextAt(idx));
    }
    
    private File saveFileHelper(String folder, String filetypeName, String extension) {
        final JFileChooser fc = new JFileChooser(new File(folder));
        fc.setFileFilter(createFileFilter(filetypeName, extension));
        int returnVal = fc.showSaveDialog(frame);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            String path = fc.getSelectedFile().getAbsolutePath();
            if(!path.endsWith(extension)) {
                path += extension;
            }
            return new File(path);
        }
        return null;
    }
    
    public void saveMotors(File file) {
        try {
            PrintWriter out = new PrintWriter(file, "UTF-8");
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
            removeMotor(name, true);
        });
    }

    public void initializeFilesystem() {
        createFolder("projects");
        createFolder("motors");
        createFolder("js");
    }
    
    private void createFolder(String name) {
        File projects = new File(name);
        if(!projects.isDirectory()) {
            boolean success = projects.mkdir();
            if(!success) {
                System.err.println("Unable to create " + name + " directory.");
                System.exit(14);
            }
        }
    }
    
    private void addExistingMotor(MotorComponent mc, boolean update) {
        synchronized(motorLock) {
            motorContainer.add(mc);
            if(motors.containsKey(mc.getName())) {
                removeMotor(mc.getName(), false);
            }
            motors.put(mc.getName(), mc);
        }
        if(update) updateMotorPanel();
    }
    
    public void addMotor(String name, boolean update) {
        MotorComponent mc = constructMotorComponent(name);
        addExistingMotor(mc, update);
    }
    
    public void removeAllMotors(boolean update) {
        synchronized(motorLock) {
            Collection<MotorComponent> values = motors.values();
            for(MotorComponent mc : values) {
                removeMotor(mc.getName(), false);
            }
        }
        if(update) updateMotorPanel();
    }
    
    public void removeMotor(String name, boolean update) {
        synchronized(motorLock) {
            MotorComponent mc = motors.remove(name);
            motorContainer.remove(mc);
        }
        if(update) updateMotorPanel();
    }
    
    private void updateMotorPanel() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                System.out.println("Updating motors...");
                motorPane.invalidate();
                motorPane.revalidate();
                motorPane.repaint();
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
        INFO,
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
                double version = -1;
                synchronized(readLock) {
                    version = input.readDouble();
                    if(version == EXPECTED_SERVER_VERSION) {
                        logSuccess("Handshaked with expected server version: " + version);
                    } else {
                        logError("Not correct server version. Server has: " + version + ", expected " + EXPECTED_SERVER_VERSION  + "\nSome features may not work.");
                    }
                }
                synchronized(writeLock) {
                    if(version >= 1.41) { //these versions expect a double back
                        output.writeDouble(EXPECTED_SERVER_VERSION);
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
                                UIManager
                                    .getLookAndFeel()
                                    .provideErrorFeedback(console);
                                String stackTrace = input.readUTF();
                                int lineNo = input.readInt();
                                int columnNo = input.readInt();
                                logError("Error running javascript at line " + lineNo + " column " + columnNo + "!");
                                logError(message);
                                logError(stackTrace);
                                break;
                            case SINGLE_ERROR:
                                UIManager
                                    .getLookAndFeel()
                                    .provideErrorFeedback(console);
                                logError(message);
                                break;
                            case PRINT:
                                logPrint(message);
                                break;
                            case INFO:
                                logInfo(message);
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
            socket = new Socket();
            socket.connect(new InetSocketAddress(hostname, port), SOCKET_TIMEOUT);
            logInfo("Socket established.");
            output = new DataOutputStream(socket.getOutputStream());
            input = new DataInputStream(socket.getInputStream());
        }
    }
    
    private void initializeMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        
        JMenu appearance = new JMenu("Appearance");
        appearance.setMnemonic(KeyEvent.VK_A);
        appearance.getAccessibleContext().setAccessibleDescription(
            "Appearance settings");
        
        JMenu laf = new JMenu("Look & Feel");
        laf.setMnemonic(KeyEvent.VK_L);
        
        Function<String, Exception> updateLookAndFeel = (className) -> {
            try {
                //Set System L&F
                UIManager.setLookAndFeel(className);
                SwingUtilities.updateComponentTreeUI(frame);
                config.setAndWrite("lookandfeel", className);
                return null;
            } catch (UnsupportedLookAndFeelException|
                   ClassNotFoundException|
                   InstantiationException|
                   IllegalAccessException e) {
                e.printStackTrace();
                return e;
            }
        };
        
        UIManager.LookAndFeelInfo[] looks = UIManager.getInstalledLookAndFeels();
        for(UIManager.LookAndFeelInfo look : looks) {
            JMenuItem lookandfeel = new JMenuItem(look.getName());
            lookandfeel.addActionListener(lambdaAction((ae) -> updateLookAndFeel.apply(look.getClassName())));
            laf.add(lookandfeel);
        }
        JMenuItem custom = new JMenuItem("Custom...");
        custom.addActionListener(lambdaAction((ae) -> {
            String res = JOptionPane.showInputDialog(frame, "Enter the unqualified class name...");
            if(res == null) return;
            Exception error = updateLookAndFeel.apply(res);
            if(error != null) JOptionPane.showMessageDialog(frame, "Unable to use that L&F: " + error.getClass().getCanonicalName());
        }));
        laf.add(custom);
        
        appearance.add(laf);
        
        JMenuItem consoleMaxLength = new JMenuItem("Console max chars");
        consoleMaxLength.addActionListener(generateMenuConfigActionListenerAndRun("consoleMaxChars",
                                                                       String.valueOf(MAX_CONSOLE_LENGTH),
                                                                       "Enter the maximum number of characters allowed in the console:",
                                                                       str -> str.matches("^\\d+$"), //is positive integer
                                                                       str -> {
                                                                           try {
                                                                                MAX_CONSOLE_LENGTH = Integer.parseInt(str);
                                                                           } catch(NumberFormatException nfe) {
                                                                                System.err.println("Something very wrong has happened.");
                                                                           }
                                                                       }));
        consoleMaxLength.setMnemonic(KeyEvent.VK_M);

        JMenuItem clearConsole = new JMenuItem("Clear console");
        clearConsole.addActionListener(lambdaAction((ae) -> console.setText("")));
        clearConsole.setMnemonic(KeyEvent.VK_C);
        
        JCheckBoxMenuItem wrapTabs = new JCheckBoxMenuItem("Wrap tabs");
        Function<Boolean, Integer> func = (b) -> b ?
                                  JTabbedPane.WRAP_TAB_LAYOUT :
                                  JTabbedPane.SCROLL_TAB_LAYOUT;
        jstabs.setTabLayoutPolicy(func.apply(config.get("wrapTabs", "true").equals("true")));
        wrapTabs.addActionListener(lambdaAction((ae) -> {
            jstabs.setTabLayoutPolicy(func.apply(wrapTabs.getState()));
            config.setAndWrite("wrapTabs", wrapTabs.getState() ? "true" : "false");
        }));
        
        appearance.addSeparator();
        appearance.add(consoleMaxLength);
        appearance.add(clearConsole);
        appearance.addSeparator();
        appearance.add(wrapTabs);
        
        //file
        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);
        
        JMenuItem open = new JMenuItem("Open");
        open.setMnemonic(KeyEvent.VK_O);
        open.addActionListener(lambdaAction((ae) -> openJS()));
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcutMask));
        
        JMenuItem save = new JMenuItem("Save");
        save.setMnemonic(KeyEvent.VK_S);
        save.addActionListener(lambdaAction((ae) -> saveJS()));
        save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcutMask));
        
        JMenuItem saveAs = new JMenuItem("Save as...");
        saveAs.setMnemonic(KeyEvent.VK_A);
        saveAs.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcutMask|Event.SHIFT_MASK));
        
        saveAs.addActionListener(lambdaAction((ae) -> saveAsJS()));
        file.add(open);
        file.add(save);
        file.add(saveAs);
        
        //edit
        JMenu edit = new JMenu("Edit");
        edit.setMnemonic(KeyEvent.VK_E);
        
        JMenuItem find = new JMenuItem("Find");
        find.setMnemonic(KeyEvent.VK_F);
        
        //tabs
        JMenu tabs = new JMenu("Tabs");
        tabs.setMnemonic(KeyEvent.VK_T);
        
        JMenuItem newTab = new JMenuItem("New");
        newTab.setMnemonic(KeyEvent.VK_N);
        newTab.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcutMask));
        
        JMenuItem closeTab = new JMenuItem("Close");
        closeTab.setMnemonic(KeyEvent.VK_C);
        closeTab.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, shortcutMask));
        
        newTab.addActionListener(lambdaAction((ae) -> addEmptyTab()));
        closeTab.addActionListener(lambdaAction((ae) -> closeTab()));
        tabs.add(newTab);
        tabs.add(closeTab);
        
        //control
        JMenu control = new JMenu("Control");
        control.setMnemonic(KeyEvent.VK_C);
        
        JCheckBoxMenuItem hostname = new JCheckBoxMenuItem("Auto reconnect at startup");
        hostname.setMnemonic(KeyEvent.VK_H);
        String autorecon = "autoreconnect";
        hostname.setState("true".equals(config.get(autorecon, "true")));
        hostname.addActionListener(lambdaAction((ae) -> {
            if(hostname.getState()) {
                config.setAndWrite(autorecon, "true");
            } else {
                config.setAndWrite(autorecon, "false");
            }
        }));
        
        JMenuItem reconnect = new JMenuItem("Reconnect");
        reconnect.setMnemonic(KeyEvent.VK_R);
        reconnect.addActionListener(lambdaAction((ae) -> {
            if(!reconnectToServer()) {
                JOptionPane.showMessageDialog(frame, "Unable to reconnect. Are you still connected to the current host?");
            }
        }));
        
        control.add(reconnect);
        control.add(hostname);
        
        //robot
        JMenu robot = new JMenu("Robot");
        robot.setMnemonic(KeyEvent.VK_R);
        
        JMenuItem init = new JMenuItem("Init");
        init.setMnemonic(KeyEvent.VK_I);
        init.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, Event.CTRL_MASK));
        init.addActionListener(lambdaAction((ae) -> sendInit()));
        robot.add(init);
        
        JMenuItem startstop = new JMenuItem("Start/stop");
        startstop.setMnemonic(KeyEvent.VK_S);
        startstop.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, Event.CTRL_MASK));
        startstop.addActionListener(lambdaAction((ae) -> {
            if(isLooping()) {
                sendStop();
            } else {
                sendStart();
            }
        }));
        robot.add(startstop);
        
        JMenuItem send = new JMenuItem("Send");
        send.setMnemonic(KeyEvent.VK_E);
        send.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, Event.CTRL_MASK));
        send.addActionListener(lambdaAction((ae) -> sendEntireFile()));
        robot.add(send);
        
        robot.addSeparator();
        
        JMenuItem sendinitstart = new JMenuItem("Send, init, start");
        sendinitstart.setMnemonic(KeyEvent.VK_N);
        sendinitstart.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, Event.CTRL_MASK));
        sendinitstart.addActionListener(lambdaAction((ae) -> {
            sendEntireFile();
            sendInit();
            sendStart();
        }));
        robot.add(sendinitstart);
        
        JMenuItem initstart = new JMenuItem("Init, start");
        initstart.setMnemonic(KeyEvent.VK_T);
        initstart.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, Event.CTRL_MASK));
        initstart.addActionListener(lambdaAction((ae) -> {
            sendInit();
            sendStart();
        }));
        robot.add(initstart);
        
        menuBar.add(file);
        menuBar.add(tabs);
        menuBar.add(control);
        menuBar.add(robot);
        menuBar.add(appearance);
        
        frame.setJMenuBar(menuBar);
    }

    //generates an actionlistener and runs on whatever config value exists, or the default if none exists
    private ActionListener generateMenuConfigActionListenerAndRun(String propertyName, String defaultValue, String prompt, Function<String, Boolean> validator, Consumer<String> performAction) {
        performAction.accept(config.get(propertyName, defaultValue));
        return generateMenuConfigActionListener(propertyName, prompt, validator, performAction);
    }
    
    public boolean reconnectToServer() {
        if(smThread.isAlive()) {
            //we're still connected
            return false;
        } else {
            try {
                sm = new ServerManager(host, PORT);
                smThread = new Thread(sm);
                smThread.start();
                return true;
            } catch(IOException ioe) {
                ioe.printStackTrace();
                return false;
            }
        }
    }
    
    //generates an actionlistener for a menu item, with a property name, prompt, validator, and code to run
    private ActionListener generateMenuConfigActionListener(String propertyName, String prompt, Function<String, Boolean> validator, Consumer<String> performAction) {
        return new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent ae) {
                String result = JOptionPane.showInputDialog(prompt);
                if(result == null || result.equals("")) {
                    return;
                }
                if(validator.apply(result)) {
                    //valid
                    config.setAndWrite(propertyName, result);
                    performAction.accept(result);
                } else {
                    JOptionPane.showMessageDialog(null, "Invalid input.");
                }
            }
        };
    }
    
    private void initializeGUI() {
        frame.setSize(1200, 700);
        frame.setLocationRelativeTo(null);
        
        mainPanel = new JPanel();
        mainPanel.setLayout(new BorderLayout());
        frame.setContentPane(mainPanel);
        mainPanel.setFocusable(true);
        
        initializeBottomPanel();
        initializeLeftPanel();
        initializeCenterPanel();
        
        addEmptyTab();
        
        initializeKeyEvents();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                jsp.setDividerLocation(0.7);
            }
        });
        
        initializeMenuBar();
        
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent we) {
                String[] btns = {"Yes", "No"};
                int res = JOptionPane.showOptionDialog(frame, "Are you sure you want to exit?", "Make sure to save!", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, btns, btns[1]);
                if(res == JOptionPane.YES_OPTION) {
                    System.exit(0);
                }
            }
        });
        frame.setTitle("JS Robotics Console");
        frame.setVisible(true);
    }
    
    private void initializeKeyEvents() {
        InputMap im = mainPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = mainPanel.getActionMap();
        
        /*im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, Event.CTRL_MASK), "save");
        am.put("save", lambdaAction((ae) -> saveJS()));
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, Event.CTRL_MASK | Event.SHIFT_MASK), "save_as");
        am.put("save_as", lambdaAction((ae) -> saveAsJS()));
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, Event.CTRL_MASK), "close");
        am.put("close", lambdaAction((ae) -> closeTab()));
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, Event.CTRL_MASK), "new");
        am.put("new", lambdaAction((ae) -> addEmptyTab()));
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_O, Event.CTRL_MASK), "open");
        am.put("open", lambdaAction((ae) -> openJS()));*/
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, Event.CTRL_MASK|Event.SHIFT_MASK), "forcequit");
        am.put("forcequit", lambdaAction((ae) -> System.exit(0)));
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Event.CTRL_MASK|Event.SHIFT_MASK), "focusbottom");
        am.put("focusbottom", lambdaAction((ae) -> {
            if(singleLine.isFocusOwner()) {
                getCurrentRTA().requestFocusInWindow();
            } else {
                singleLine.requestFocusInWindow();
            }
        }));
        
        int[] keyEvents = {KeyEvent.VK_0,
                           KeyEvent.VK_1,
                           KeyEvent.VK_2,
                           KeyEvent.VK_3,
                           KeyEvent.VK_4,
                           KeyEvent.VK_5,
                           KeyEvent.VK_6,
                           KeyEvent.VK_7,
                           KeyEvent.VK_8,
                           KeyEvent.VK_9};
        for(int i = 1; i < 10; i++) {
            im.put(KeyStroke.getKeyStroke(keyEvents[i], Event.ALT_MASK), "tab" + i);
            final int index = i-1;
            am.put("tab" + i, lambdaAction((ae) -> {
                if(index < jstabs.getTabCount()) {
                    jstabs.setSelectedIndex(index);
                }
            }));
        }
        im.put(KeyStroke.getKeyStroke(keyEvents[0], Event.ALT_MASK), "lastTab");
        am.put("lastTab", lambdaAction((ae) -> jstabs.setSelectedIndex(jstabs.getTabCount()-1)));
    }
    
    private AbstractAction lambdaAction(Consumer<ActionEvent> consumer) {
        return new AbstractAction() {
            public void actionPerformed(ActionEvent ae) {
                consumer.accept(ae);
            }
        };
    }
    
    private void closeTab() {
        if(jstabs.getTabCount() <= 1) {
            return;
        }
        int idx = jstabs.getSelectedIndex();
        String tooltip = jstabs.getToolTipTextAt(idx);
        jstabs.remove(idx);
        jsfiles.remove(tooltip);
    }
    
    private void logInfo(String msg) {
        coloredLog(msg, Color.BLACK);
    }
    
    private void logError(String msg) {
        coloredLog(msg, errorColor);
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
    private static final Color errorColor = Color.RED;
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

        //ensure that the message can fit
        if(msg.length() > MAX_CONSOLE_LENGTH) {
            console.setText("");
            msg = "Message too large to be displayed.\n";
            c = errorColor;
        }
        
        int len = console.getDocument().getLength();
        if((len+msg.length()) > MAX_CONSOLE_LENGTH) {
            //enforce MAX limit
            try {
                console.getDocument().remove(0, (len+msg.length())-MAX_CONSOLE_LENGTH);
            } catch(BadLocationException ble) {
                System.err.println("Console is going very wrong.");
                console.setText("Something very wrong is occuring with the console. Check MAX_CONSOLE_LENGTH.\n");
            }
            
            //get new length
            len = console.getDocument().getLength();
        }
        
        console.setCaretPosition(len);
        console.setCharacterAttributes(aset, false);
        console.replaceSelection(msg);
        
        //repeat to scroll to bottom
        len = console.getDocument().getLength();
        console.setCaretPosition(len);
    }
    
    private boolean isLooping() {
        return startStopBtn.getText().equals("Stop");
    }
    
    private void setStartButton() {
        startStopBtn.setText("Start");
        startStopBtn.setActionCommand("start");
    }
    private void setStopButton() {
        startStopBtn.setText("Stop");
        startStopBtn.setActionCommand("stop");
    }
    
    private RTextScrollPane createRTextArea(String text) {
        RSyntaxTextArea rta = new RSyntaxTextArea(20, 60);
        rta.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
        rta.setCodeFoldingEnabled(true);
        rta.setText(text);
        return new RTextScrollPane(rta);
    }
    
    private int jstab_id = 0;
    private String generateTooltip(String tooltip) {
        return tooltip + " (" + (jstab_id++) + ")"; //for hashing purposes
    }
    
    private RSyntaxTextArea addRTATab(String filename, File file, String tooltip, String text) {
        tooltip = generateTooltip(tooltip);
        RTextScrollPane rtsp = createRTextArea(text);
        jstabs.addTab(filename, null, rtsp, tooltip);
        jstabs.setSelectedIndex(jstabs.getTabCount()-1); //focus
        jsfiles.put(tooltip, file);
        return (RSyntaxTextArea)rtsp.getTextArea();
    }
    
    private void addEmptyTab() {
        addRTATab("blank", null, "Empty file", DEFAULT_CODE);
    }

    private void initializeCenterPanel() {
        jstabs = new JTabbedPane();
        
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
        jsp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, jstabs, rightSplit);
        mainPanel.add(jsp, BorderLayout.CENTER);
    }

    private void initializeLeftPanel() {
        JPanel motors = new JPanel();
        motors.setLayout(new BorderLayout());
        JPanel top = new JPanel();
        top.setLayout(new GridLayout(1, 3));
        JButton load = new JButton("Load");
        load.setActionCommand("load_motor");
        load.addActionListener(buttonHandler);
        JButton save = new JButton("Save");
        save.setActionCommand("save_motor");
        save.addActionListener(buttonHandler);
        JButton add = new JButton("Add");
        add.setActionCommand("new_motor");
        add.addActionListener(buttonHandler);
        top.add(load);
        top.add(save);
        top.add(add);
        motors.add(top, BorderLayout.NORTH);
        
        motorContainer = new JPanel();
        JPanel motorContainerContainer = new JPanel();
        motorContainer.setLayout(new BoxLayout(motorContainer, BoxLayout.Y_AXIS));
        motorContainerContainer.setLayout(new FlowLayout());
        motorContainerContainer.add(motorContainer);
        motorPane = motors;
        
        motors.add(new JScrollPane(motorContainerContainer), BorderLayout.CENTER);
        
        
        JPanel project = new JPanel();
        project.add(new JLabel("tabs"));
        
        
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Control", null, motors, "Motor and general control using sliders");
        tabs.addTab("Project", null, project, "Project control");
        
        mainPanel.add(tabs, BorderLayout.WEST);
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
        mainPanel.add(bottom, BorderLayout.SOUTH);
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
    
    private static void forEachInFile(File file, Consumer<String> action) {
        try {
            Scanner scan = new Scanner(file);
            while(scan.hasNextLine()) {
                action.accept(scan.nextLine());
            }
            scan.close();
        } catch(FileNotFoundException fnfe) {
            JOptionPane.showMessageDialog(null, "File not found: " + file.getName());
        }
    }
    
    private static void writeToFile(File file, String content) {
        try {
            PrintWriter writer = new PrintWriter(file, "UTF-8");
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
        "}\n\n" +
        "function loop() {\n" +
        "\t//TODO add something here\n" +
        "}\n\n" +
        "function stop() {\n" +
        "\t//TODO add something here\n" +
        "}";

}